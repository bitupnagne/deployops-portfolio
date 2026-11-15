"""Offline maintenance media/state regressions; no host mutation."""
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import component_upgrade as up


class ComponentUpgradeTest(unittest.TestCase):
    def test_supported_patch_and_unsupported_migration(self):
        up.patch_step('19.3.0','19.3.8')
        up.patch_step('11.4.13','11.4.13')
        for source,target in [('11.4.13','11.8.0'),('19.3.4','19.3.3'),('3.96.1','4.0.0')]:
            with self.subTest(target=target), self.assertRaises(up.Refused):
                up.patch_step(source,target)

    def tar_fixture(self,root,tag,arch='amd64',extra=False):
        p=root/'image.tar'
        conf=json.dumps({'architecture':arch,'os':'linux'}).encode()
        meta={'Config':'config.json','RepoTags':[tag],'Layers':['layer.tar']}
        with tarfile.open(str(p),'w') as t:
            for name,body in [('manifest.json',json.dumps([meta,meta] if extra else [meta]).encode()),('config.json',conf),('layer.tar',b'layer')]:
                item=tarfile.TarInfo(name)
                item.size=len(body)
                t.addfile(item,io.BytesIO(body))
        return p

    def test_tagged_image_and_helper_are_version_bound(self):
        with tempfile.TemporaryDirectory() as d, patch.object(up,'trusted_path',lambda p,**kw:p):
            root=Path(d)
            for repo,tag in [('gitlab/gitlab-ce','19.3.1-ce.0'),('gitlab/gitlab-runner','v19.3.1'),
                             (up.HELPER,'x86_64-v19.3.1'),('sonatype/nexus3','19.3.1')]:
                entry=up.image_tar(self.tar_fixture(root,repo+':'+tag),repo,'19.3.1')
                self.assertEqual(entry['tag'],repo+':'+tag)
                self.assertTrue(entry['imageId'].startswith('sha256:'))
                with self.assertRaises(up.Refused):
                    up.image_tar(root/'image.tar',repo,'19.3.2')

    def test_wrong_image_architecture_or_multi_image_is_refused(self):
        with tempfile.TemporaryDirectory() as d, patch.object(up,'trusted_path',lambda p,**kw:p):
            for arch,extra in [('arm64',False),('amd64',True)]:
                file=self.tar_fixture(Path(d),'sonatype/nexus3:3.96.1',arch,extra)
                with self.assertRaises(up.Refused):
                    up.image_tar(file,'sonatype/nexus3','3.96.1')

    def node(self):
        return {'Id':'a'*64,'Config':{'Hostname':'demo-gitlab','Env':['PATH=/old','GITLAB_DISABLE_OPENSSH=true']},
                'HostConfig':{'NetworkMode':'bridge','RestartPolicy':{'Name':'always'},
                              'Binds':['/srv/gitlab/data:/var/opt/gitlab:Z'],
                              'PortBindings':{'8929/tcp':[{'HostIp':'','HostPort':'8929'}]}},
                'NetworkSettings':{'Networks':{'bridge':{}}},
                'Mounts':[{'Destination':'/var/opt/gitlab','Source':'/srv/gitlab/data','Type':'bind','RW':True}]}

    def test_replacement_retains_runtime_policy_bind_global_environment_and_host_port(self):
        current=self.node()
        result=up.container_payload(current,{'Env':['PATH=/old']},'sha256:test')
        self.assertEqual(result['HostConfig'],current['HostConfig'])
        self.assertEqual(result['Hostname'],'demo-gitlab')
        self.assertEqual(result['Env'],['GITLAB_DISABLE_OPENSSH=true'])
        self.assertEqual(result['Image'],'sha256:test')

    def test_unrepresented_mount_or_static_network_is_rejected_before_change(self):
        for change in ('mount','network','static','link','auto-remove'):
            node=self.node()
            if change=='mount': node['HostConfig']['Binds']=[]
            if change=='network': node['HostConfig']['NetworkMode']='foo'
            if change=='static': node['NetworkSettings']['Networks']['bridge']['IPAMConfig']={'IPv4Address':'198.18.8.1'}
            if change=='link': node['HostConfig']['Links']=['old:db']
            if change=='auto-remove': node['HostConfig']['AutoRemove']=True
            with self.subTest(change=change),self.assertRaises(up.Refused):
                up.container_payload(node,{},'sha256:test')

    def test_named_role_is_bound_to_its_installation_and_original_data(self):
        n=self.node()
        with self.assertRaises(up.Refused):
            up.role_contract('gitlab','lookalike-gitlab',n)
        with self.assertRaises(up.Refused):
            up.role_contract('gitlab','worksystem-gitlab',n)
        for dest,sub in [('/etc/gitlab','config'),('/var/log/gitlab','logs')]:
            n['Mounts'].append({'Destination':dest,'Source':'/srv/gitlab/'+sub,'Type':'bind','RW':True})
        up.role_contract('gitlab','worksystem-gitlab',n)

    def test_local_dnf_transaction_does_not_commit_other_packages(self):
        dnf=MagicMock()
        with patch.dict(sys.modules,{'dnf':dnf}):
            base=dnf.Base.return_value.__enter__.return_value
            alien=MagicMock();alien.name='other';alien.epoch=0;alien.version='3.0.0';alien.release='1'
            base.transaction.install_set=[alien]
            base.transaction.remove_set=[alien]
            with self.assertRaises(up.Refused):
                up.rpm_transaction({'packages':{'nginx':'1:1.30.4-1'},'installed':{'nginx':'1:1.30.3-1'},'rpmPaths':['nginx.rpm']},True)
            base.do_transaction.assert_not_called()
            base.fill_sack.assert_called_once_with(load_available_repos=False)

    def loose_rpm_fixture(self,root):
        rows={}
        for name in up.PACKAGES['mariadb']:
            filename=name+'.rpm'
            (root/filename).write_bytes(('package:'+name).encode())
            rows[filename]=name+'|0:11.4.13-1.el8|x86_64'
        def query(args):
            return rows[Path(args[-1]).name] if '-qp' in args else '0:11.4.12-1.el8'
        return rows,query

    def test_loose_vendor_rpms_bind_identity_to_actual_bytes_without_lock(self):
        with tempfile.TemporaryDirectory() as d,patch.object(up,'trusted_path',lambda p,**kw:p):
            root=Path(d);_,query=self.loose_rpm_fixture(root)
            with patch.object(up,'run',side_effect=query):
                first=up.rpm_plan('mariadb',root,'11.4.13')
                (root/'MariaDB-client.rpm').write_bytes(b'same NEVRA different payload')
                second=up.rpm_plan('mariadb',root,'11.4.13')
                self.assertNotEqual(first['mediaHash'],second['mediaHash'])
                self.assertEqual(set(first['packages']),set(up.PACKAGES['mariadb']))

    def test_loose_vendor_media_rejects_missing_duplicate_or_unrelated_dependency(self):
        for error in ('missing','duplicate','extraneous','symlink'):
            with self.subTest(error=error),tempfile.TemporaryDirectory() as d:
                root=Path(d);rows,query=self.loose_rpm_fixture(root)
                if error=='missing':
                    (root/'MariaDB-shared.rpm').unlink()
                else:
                    name='extra.rpm'
                    if error=='symlink': (root/name).symlink_to(root/'MariaDB-client.rpm')
                    else: (root/name).write_bytes(b'other')
                    rows[name]=rows['MariaDB-client.rpm'] if error in ('duplicate','symlink') else 'other|0:1.1.1-1.el8|x86_64'
                with patch.object(up,'run',side_effect=query),patch.object(up,'trusted_path',lambda p,**kw:p):
                    with self.assertRaises(up.Refused): up.rpm_plan('mariadb',root,'11.4.13')

    def test_undrained_engine_or_always_policy_blocks_backup(self):
        for outputs in [['running'],['','closed',json.dumps([{'HostConfig':{'RestartPolicy':{'Name':'always'}}}])]]:
            with patch.object(up,'run',side_effect=outputs),self.assertRaises(up.Refused):
                up.check_drain('docker')

    def test_galera_replica_and_encrypted_db_require_separate_path(self):
        for values in [['running replica'],['','wsrep_on\tON'],['','wsrep_on\tOFF','file_key_management']]:
            with patch.object(up,'db_command',side_effect=values),self.assertRaises(up.Refused):
                up.mariadb_standalone()

    def test_database_clients_share_anonymous_credential_descriptor_as_first_option(self):
        with tempfile.TemporaryDirectory() as d,patch.object(up,'trusted_path',lambda p,**kw:p):
            state=Path(d)/'state.json'
            value='dummy#"\\password'
            state.write_text(json.dumps({'role':'deploy','values':{'db_root_password':value}}))
            for binary in ['mariadb','mariadb-dump','mariadb-upgrade']:
                with patch.object(up,'DB_STATE',state),up.database_client(binary,['--test']) as (args,fds):
                    self.assertEqual(args[1],'--defaults-file=/proc/self/fd/'+str(fds[0]))
                    self.assertNotIn(value,' '.join(args))
                    self.assertEqual(os.fstat(fds[0]).st_mode&0o777,0o600)
                    self.assertIn(b'user=root',os.read(fds[0],2048))
                with self.assertRaises(OSError): os.fstat(fds[0])

    def test_empty_unrelated_or_substituted_backup_file_does_not_qualify(self):
        with tempfile.TemporaryDirectory() as d, patch.object(up,'trusted_path',lambda p,**kw:p),patch.object(up,'machine',return_value='host'):
            root=Path(d)
            record={'schema':1,'component':'nginx','sourceId':'1.0','machine':'host','contents':{'nginx-config.tar':'0'*64}}
            (root/'upgrade-backup.json').write_text(json.dumps(record))
            (root/'nginx-config.tar').write_text('substituted')
            with self.assertRaises(up.Refused): up.native_backup('nginx',root,{},'1.0')
            with self.assertRaises(up.Refused): up.native_backup('runner',root,{},'1.0')

    def test_runner_helper_edit_preserves_other_toml_contents_and_rejects_multiple_definitions(self):
        with tempfile.TemporaryDirectory() as d:
            cfg=Path(d)/'config.toml'
            cfg.write_text('token = "fixture"\nhelper_image = "before"\n')
            with patch.object(up.os,'fchown'):
                up.update_helper(cfg,'after','before')
                self.assertEqual(cfg.read_text(),'token = "fixture"\nhelper_image = "after"\n')
                up.update_helper(cfg,'after','before')
                cfg.write_text(cfg.read_text()+'helper_image = "other"\n')
                with self.assertRaises(up.Refused): up.update_helper(cfg,'after','before')

    def test_gitlab_backup_seal_survives_source_rename_and_detects_later_changes(self):
        with tempfile.TemporaryDirectory() as d,patch.object(up,'trusted_path',lambda p,**kw:p),patch.object(up,'machine',return_value='lab'):
            root=Path(d)
            (root/'config').mkdir()
            source={'Id':'1'*64,'Name':'/worksystem-gitlab','Image':'sha256:'+'2'*64}
            name='release_gitlab_backup.tar'
            (root/'metadata.json').write_text(json.dumps({'format':'deployops-gitlab','schema':2,'image_id':source['Image'],
                'container':'worksystem-gitlab','backup_tar':name}))
            (root/'config/gitlab.rb').write_text('fixture')
            (root/'config/gitlab-secrets.json').write_text('{"fixture":1}')
            (root/'container-inspect.json').write_text(json.dumps([source]))
            (root/'image-inspect.json').write_text(json.dumps([{'Id':source['Image']}]))
            for f in ['image.tar','omnibus-config.rb','version-manifest.txt']:
                (root/f).write_text('original')
            with tarfile.open(str(root/name),'w') as t:
                info=tarfile.TarInfo('backup_information.yml');info.size=5
                t.addfile(info,io.BytesIO(b'valid'))
            with patch.object(up,'write_private',lambda path,data:path.write_bytes(data)):
                digest=up.native_backup('gitlab',root,source,source['Id'],'worksystem-gitlab',True)
            source['Name']='/worksystem-gitlab-before-'+source['Id'][:12]
            self.assertEqual(up.native_backup('gitlab',root,source,source['Id'],'worksystem-gitlab'),digest)
            (root/'image.tar').write_text('damaged restore image')
            with self.assertRaises(up.Refused): up.native_backup('gitlab',root,source,source['Id'],'worksystem-gitlab')

    def test_jar_replaced_on_disk_does_not_prove_it_is_running(self):
        with tempfile.TemporaryDirectory() as d:
            jar=Path(d)/'live.jar';jar.write_bytes(b'old')
            with jar.open('rb') as original:
                linkdir=Path(d)/'fds';linkdir.mkdir()
                (linkdir/'9').symlink_to('/proc/self/fd/'+str(original.fileno()))
                self.assertTrue(up.jvm_uses_jar(linkdir,jar))
                newer=Path(d)/'pending.jar';newer.write_bytes(b'new');os.replace(str(newer),str(jar))
                self.assertFalse(up.jvm_uses_jar(linkdir,jar))

    def test_done_receipt_checks_hostname_target_and_actual_health(self):
        record={'operation':{'component':'nginx','target':'1.30.4','machine':'lab'},
                'phase':'done','verification':{'packages':{'nginx':'1:1.30.4-1'}}}
        with patch.object(up,'machine',return_value='lab'),patch.object(up,'health') as health:
            self.assertTrue(up.verify_baseline_skip(record))
            health.assert_called_once_with('nginx',None,record['verification'],'1.30.4')
            record['operation']['machine']='other'
            with self.assertRaises(up.Refused): up.verify_baseline_skip(record)


if __name__=='__main__':
    unittest.main()
