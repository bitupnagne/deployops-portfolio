#!/usr/bin/env python3
"""Behavioral fixture tests: execute the real GitLab shell scripts with an isolated Docker double.
No Docker daemon, live container, network, or production data is touched.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / "gitlab/backup-gitlab.sh"
RESTORE = ROOT / "gitlab/restore-gitlab.sh"
IMAGE_ID = "sha256:" + "a" * 64
OTHER_ID = "sha256:" + "b" * 64
DOCKER = r'''#!/usr/bin/env python3
import json,os,shutil,sys
from pathlib import Path
args=sys.argv[1:]; root=Path(os.environ['FIXTURE_ROOT']); state=root/'docker-state'
state.mkdir(exist_ok=True)
with (root/'docker.jsonl').open('a') as log: log.write(json.dumps(args)+'\n')
image='sha256:'+'a'*64
if args[0]=='inspect' or args[:2]==['container','inspect']:
    name=args[-1]
    if '-f' in args:
        print('false' if name=='target' and os.environ.get('STOPPED_TARGET') else 'true')
        sys.exit(0)
    if args[0]=='inspect' and os.environ.get('NETWORK_SAME_NAME') and name=='target':
        print(json.dumps([{'Name':'target','Internal':True}]))
        sys.exit(0)
    if name != 'source' and not (state/name).exists(): sys.exit(1)
    print(json.dumps([{'Image':image,'State':{'Running':True},'Config':{
       'Image':'gitlab/gitlab-ce:mutable-tag','Hostname':'original.example',
       'Env':["GITLAB_OMNIBUS_CONFIG=external_url 'http://original.example:8929';\npuma['worker_processes'] = 2;"]}}]))
elif args[:2]==['image','inspect']:
    if '-f' in args:
        print('sha256:'+('b' if os.environ.get('WRONG_IMAGE') else 'a')*64)
    else: print(json.dumps([{'Id':image,'Architecture':'amd64'}]))
elif args[0]=='save':
    assert args[-1]==image, 'must save running immutable image ID'
    Path(args[args.index('-o')+1]).write_bytes(b'offline image archive')
elif args[0]=='cp':
    args=[a for a in args[1:] if a!='-a']; src,dst=args
    if ':/' in src:
        name,path=src.split(':',1)
        if path=='/etc/gitlab/.':
            if os.environ.get('FAIL_CONFIG_COPY'): sys.exit(4)
            shutil.copytree(root/'source-config',dst,dirs_exist_ok=True)
        else: shutil.copy2(state/Path(path).name,Path(dst))
    else:
        assert src.endswith('_gitlab_backup.tar'), 'never copy image.tar as native backup'
        (state/'copied-backup').write_text(src)
elif args[0]=='exec':
    name=args[1]; cmd=args[2:]
    if cmd==['cat','/opt/gitlab/version-manifest.txt']:
        version='19.2.9' if name!='source' and os.environ.get('WRONG_VERSION') else '19.3.0'
        print('gitlab-ce '+version+'\ngitlab-rails 19.3.0')
    elif cmd[:2]==['gitlab-backup','create']:
        if os.environ.get('FAIL_BACKUP'): sys.exit(5)
        backup_id=next(a.split('=',1)[1] for a in cmd if a.startswith('BACKUP='))
        (state/(backup_id+'_gitlab_backup.tar')).write_bytes(b'native backup payload')
    elif cmd[:2]==['gitlab-backup','restore']:
        if os.environ.get('FAIL_RESTORE'):
            print('fixture-private-restore-detail')
            print('fixture-private-stderr', file=sys.stderr)
            sys.exit(6)
        (state/'restored').write_text('yes')
    elif cmd[:2]==['gitlab-ctl','status']: pass
    elif cmd[:2]==['gitlab-ctl','stop']:
        if os.environ.get('FAIL_STOP_PUMA') and cmd[-1]=='puma': sys.exit(7)
    elif cmd[0]=='test':
        if os.environ.get('UNCONFIGURED'): sys.exit(1)
        if cmd[-1]=='/var/opt/gitlab/bootstrapped' and os.environ.get('CONFIGURING'): sys.exit(1)
    elif cmd[0]=='sh':
        if os.environ.get('CHEF_RUNNING'): sys.exit(1)
    elif cmd[0] in ['gitlab-healthcheck','mkdir','chown','chmod','gitlab-rake','gitlab-rails']: pass
    else: raise AssertionError(cmd)
elif args[0]=='run':
    assert args[-1]==image, 'must run immutable source image ID'
    (state/args[args.index('--name')+1]).touch(); print('new-container-id')
elif args[0] in ['load','restart','update','stop']: pass
else: raise AssertionError(args)
'''


class GitlabRecoveryRegression(unittest.TestCase):
    def test_offline_jq_resolver_fallback_and_bom_failure(self):
        with tempfile.TemporaryDirectory(prefix='gitlab-jq-') as folder:
            root = Path(folder)
            (root / 'gitlab').mkdir()
            (root / 'bundles/common/jq').mkdir(parents=True)
            helper = root / 'gitlab/recovery-tools.sh'
            shutil.copy2(ROOT / 'gitlab/recovery-tools.sh', helper)
            binary = root / 'bundles/common/jq/jq-linux-amd64'
            shutil.copy2(shutil.which('jq'), binary)
            command = 'source "$1"; type() { return 1; }; result=$(gitlab_recovery_jq) || exit; "$result" -n \'{ok:true}\''
            result = subprocess.run(['bash', '-c', command, 'test', str(helper)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(json.loads(result.stdout)['ok'])
            (root / 'bundles/RELEASE-BOM.env').write_text('BOM_JQ_SHA256=' + '0' * 64 + '\n')
            result = subprocess.run(['bash', '-c', command, 'test', str(helper)],
                                    env=dict(os.environ, OFFLINE_VERIFY_AUTHENTICITY='1'), capture_output=True)
            self.assertNotEqual(0, result.returncode)
            binary.unlink()
            result = subprocess.run(['bash', '-c', command, 'test', str(helper)], capture_output=True)
            self.assertNotEqual(0, result.returncode)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gitlab-recovery-test-")
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "docker"
        fake.write_text(DOCKER)
        fake.chmod(0o755)
        sleep = bin_dir / 'sleep'
        sleep.write_text('#!/bin/sh\nexit 0\n')
        sleep.chmod(0o755)
        config = self.root / "source-config"
        config.mkdir()
        (config / "gitlab.rb").write_text("# Original config\npuma['worker_processes'] = 3;\n")
        (config / "gitlab-secrets.json").write_text('{"gitlab_rails":{"secret_key_base":"fixture-only"}}')
        (config / "ssl").mkdir()
        (config / "ssl/gitlab.key").write_text("fixture TLS key")
        (config / "ssh_host_rsa_key").write_text("fixture SSH host key")
        self.env = dict(os.environ, FIXTURE_ROOT=str(self.root), PATH=str(bin_dir)+os.pathsep+os.environ['PATH'])
        self.artifact = self.root / "artifact"
        self.target = self.root / "target-data"

    def test_both_scripts_reject_missing_or_broken_jq_before_docker(self):
        package = self.root / 'package'
        gitlab = package / 'gitlab'
        gitlab.mkdir(parents=True)
        bundle = package / 'bundles/common/jq'
        bundle.mkdir(parents=True)
        for source in (BACKUP, RESTORE, ROOT / 'gitlab/recovery-tools.sh'):
            shutil.copy2(source, gitlab / source.name)
        for broken in (False, True):
            if broken:
                binary = bundle / 'jq-linux-amd64'
                binary.write_text('#!/bin/sh\nexit 1\n')
                binary.chmod(0o755)
            for script, flag in ((BACKUP, '--output'), (RESTORE, '--artifact')):
                with self.subTest(script=script.name, broken=broken):
                    result = subprocess.run(['bash', '-c', 'type() { return 1; }; source "$@"',
                        'test', str(gitlab / script.name), flag, str(self.artifact)],
                        env=self.env, capture_output=True)
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse((self.root / 'docker.jsonl').exists())
                    self.assertFalse(self.artifact.exists())

    def run_script(self, script, *args, expected=0, **extra):
        result = subprocess.run(["bash", str(script), *map(str,args)], env=dict(self.env, **extra),
                                text=True, capture_output=True)
        if expected == 0:
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout+result.stderr)
        return result

    def backup(self, **extra):
        return self.run_script(BACKUP, '--container', 'source', '--output', self.artifact, **extra)

    def restore(self, *args, expected=0, **extra):
        return self.run_script(RESTORE, '--artifact', self.artifact, '--container', 'target',
                               '--data', self.target, '--wait-attempts', '1', *args,
                               expected=expected, **extra)

    def calls(self):
        return [json.loads(line) for line in (self.root/'docker.jsonl').read_text().splitlines()]

    def change_metadata(self, **values):
        path = self.artifact / "metadata.json"
        data = json.loads(path.read_text()); data.update(values)
        path.write_text(json.dumps(data))

    def test_full_config_immutable_image_exact_tar_and_official_restore_order(self):
        self.backup()
        metadata = json.loads((self.artifact/'metadata.json').read_text())
        self.assertEqual(metadata['image_id'], IMAGE_ID)
        self.assertEqual(metadata['version'], '19.3.0')
        self.assertEqual(metadata['package'], 'gitlab-ce')
        self.assertTrue((self.artifact/'config/ssl/gitlab.key').exists())
        self.assertTrue((self.artifact/'config/ssh_host_rsa_key').exists())
        (self.artifact/'000-wrong.tar').write_bytes(b'wrong')
        (self.artifact/'metadata.env').write_text('$(touch '+str(self.root/'EVALUATED')+')')
        self.restore('--external-url','http://new.example:18929','--http-port','18929','--ssh-port','0', '--network','isolated-drill')
        self.assertFalse((self.root/'EVALUATED').exists())
        config=(self.target/'config/gitlab.rb').read_text()
        self.assertLess(config.index("= 2"),config.index("= 3"))
        self.assertIn("external_url 'http://new.example:18929'",config)
        self.assertIn("nginx['listen_port'] = 18929",config)
        self.assertEqual((self.target/'config/ssl/gitlab.key').read_text(), 'fixture TLS key')
        self.assertEqual((self.target/'config/gitlab-secrets.json').stat().st_mode & 0o777,0o600)
        calls=self.calls()
        restore=next(i for i,c in enumerate(calls) if c[:4]==['exec','target','gitlab-backup','restore'])
        stop_puma=calls.index(['exec','target','gitlab-ctl','stop','puma'])
        stop_sidekiq=calls.index(['exec','target','gitlab-ctl','stop','sidekiq'])
        chown=calls.index(['exec','target','chown','git:git','/var/opt/gitlab/backups/'+metadata['backup_tar']])
        self.assertLess(chown,stop_puma); self.assertLess(stop_puma,restore); self.assertLess(stop_sidekiq,restore)
        self.assertIn('BACKUP='+metadata['backup_tar'].removesuffix('_gitlab_backup.tar'),calls[restore])
        self.assertIn(['exec','target','gitlab-rake','gitlab:doctor:secrets'],calls)
        self.assertIn(['update','--restart','always','target'],calls)
        run=next(c for c in calls if c[0]=='run')
        self.assertIn('GITLAB_DISABLE_OPENSSH=true',run)
        self.assertIn(['exec','target','gitlab-rails','runner',"ApplicationSetting.current.update!(enabled_git_access_protocol: 'http')"],calls)
        self.assertEqual([run[i+1] for i,arg in enumerate(run) if arg == '-p'], ['18929:18929'])
        self.assertEqual(run[run.index('--network')+1],'isolated-drill')
        self.assertFalse(any(c[0]=='rm' or (c[0]=='stop' and c[-1]=='source') for c in calls))

    def test_backup_missing_secrets_is_failure(self):
        (self.root/'source-config/gitlab-secrets.json').unlink()
        self.run_script(BACKUP,'--container','source','--output',self.artifact,expected=1)
        self.assertFalse(self.artifact.exists())
        self.assertFalse(any(c[0]=='save' for c in self.calls()))

    def test_env_only_configuration_with_empty_gitlab_rb_is_preserved(self):
        (self.root/'source-config/gitlab.rb').write_text('')
        self.backup(); self.restore()
        config=(self.target/'config/gitlab.rb').read_text()
        self.assertIn("external_url 'http://original.example:8929'",config)
        self.assertIn("puma['worker_processes'] = 2",config)

    def test_successful_replacement_retains_previous_backup(self):
        self.backup(); previous=(self.artifact/'metadata.json').read_bytes()
        self.run_script(BACKUP,'--container','source','--output',self.artifact,'--force')
        saved=list(self.root.glob('artifact.previous.*'))
        self.assertEqual(len(saved),1)
        self.assertEqual((saved[0]/'metadata.json').read_bytes(),previous)

    def test_backup_failed_config_copy_is_failure(self):
        self.run_script(BACKUP,'--container','source','--output',self.artifact,expected=1,FAIL_CONFIG_COPY='1')
        self.assertFalse(self.artifact.exists())

    def test_failed_replacement_keeps_previous_artifact(self):
        self.backup(); previous=(self.artifact/'metadata.json').read_bytes()
        self.run_script(BACKUP,'--container','source','--output',self.artifact,'--force',expected=1,FAIL_BACKUP='1')
        self.assertEqual((self.artifact/'metadata.json').read_bytes(),previous)

    def test_missing_secret_rejected_before_restore_mutation(self):
        self.backup(); (self.artifact/'config/gitlab-secrets.json').unlink()
        previous=len(self.calls()); self.restore(expected=1)
        self.assertEqual(len(self.calls()),previous)
        self.assertFalse(self.target.exists())

    def test_backup_tar_path_traversal_rejected_without_execution(self):
        self.backup(); self.change_metadata(backup_tar='../escape_gitlab_backup.tar')
        previous=len(self.calls()); self.restore(expected=1)
        self.assertEqual(len(self.calls()),previous)

    def test_existing_container_not_removed_with_force(self):
        self.backup(); (self.root/'docker-state/target').touch()
        self.restore('--force',expected=1)
        self.assertFalse(any(c[0] in ['rm','run','stop'] for c in self.calls()))

    def test_network_with_same_name_is_not_mistaken_for_existing_container(self):
        self.backup(); self.restore('--network','target',NETWORK_SAME_NAME='1')
        self.assertTrue((self.root/'docker-state/restored').exists())

    def test_unconfigured_image_healthcheck_success_does_not_start_database_restore(self):
        self.backup(); self.restore(expected=1,UNCONFIGURED='1')
        self.assertFalse((self.root/'docker-state/restored').exists())
        self.assertIn(['stop','target'],self.calls())
        self.assertFalse(any(c[0]=='cp' and c[-1].startswith('target:') for c in self.calls()))

    def test_first_bootstrap_in_progress_does_not_start_database_restore(self):
        self.backup(); self.restore(expected=1,CONFIGURING='1')
        self.assertFalse((self.root/'docker-state/restored').exists())
        self.assertIn(['stop','target'],self.calls())

    def test_chef_still_running_with_healthy_puma_does_not_start_database_restore(self):
        self.backup(); self.restore(expected=1,CHEF_RUNNING='1')
        self.assertFalse((self.root/'docker-state/restored').exists())
        self.assertIn(['stop','target'],self.calls())

    def test_exited_target_fails_without_waiting_or_starting_database_restore(self):
        self.backup(); self.restore(expected=1,STOPPED_TARGET='1')
        self.assertFalse((self.root/'docker-state/restored').exists())
        self.assertFalse(any(c[:3]==['exec','target','test'] for c in self.calls()))

    def test_populated_target_never_reused_with_force(self):
        self.backup(); self.target.mkdir(); (self.target/'existing').write_text('keep')
        self.restore('--force',expected=1)
        self.assertEqual((self.target/'existing').read_text(),'keep')
        self.assertFalse(any(c[0]=='run' for c in self.calls()))

    def test_override_image_mismatch_rejected_before_container_creation(self):
        self.backup(); self.restore('--image','wrong-version',expected=1,WRONG_IMAGE='1')
        self.assertFalse(any(c[0]=='run' for c in self.calls()))

    def test_actual_target_version_mismatch_stops_without_database_restore(self):
        self.backup(); self.restore(expected=1,WRONG_VERSION='1')
        self.assertFalse((self.root/'docker-state/restored').exists())
        self.assertIn(['stop','target'],self.calls())
        self.assertFalse(any(c[0]=='update' for c in self.calls()))

    def test_restore_failure_preserves_stopped_target_and_original(self):
        self.backup(); result=self.restore(expected=1,FAIL_RESTORE='1')
        self.assertEqual(result.returncode,6)
        self.assertIn('stage=native-restore exit=6', result.stderr)
        self.assertNotIn('fixture-private', result.stdout+result.stderr)
        logs=list(self.target.glob('restore-diagnostic.*.log'))
        self.assertEqual(len(logs),1)
        self.assertEqual(logs[0].stat().st_mode & 0o777,0o600)
        self.assertIn('fixture-private-restore-detail',logs[0].read_text())
        self.assertIn('fixture-private-stderr',logs[0].read_text())
        self.assertTrue(self.target.exists())
        self.assertIn(['stop','target'],self.calls())
        self.assertFalse(any(c[0]=='rm' or (c[0]=='stop' and c[-1]=='source') for c in self.calls()))
        self.assertFalse(any(c[0]=='update' for c in self.calls()))

    def test_puma_stop_failure_is_identified_without_native_restore(self):
        self.backup(); result=self.restore(expected=1,FAIL_STOP_PUMA='1')
        self.assertEqual(result.returncode,7)
        self.assertIn('stage=stop-puma exit=7',result.stderr)
        self.assertIn(['stop','target'],self.calls())
        self.assertFalse((self.root/'docker-state/restored').exists())
        self.assertFalse(any(c[0] in ['restart','update'] for c in self.calls()))

if __name__ == '__main__':
    unittest.main(verbosity=2)
