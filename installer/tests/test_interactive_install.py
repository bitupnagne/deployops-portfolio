#!/usr/bin/env python3
"""Independent behavior checks; temporary files only, no product services/network.

Run on Linux as root because the production state store enforces actual owner/mode.
External installation commands are replaced at the subprocess boundary. JAR editing
uses the real r6 configuration helpers against small ZIP fixtures.
"""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('interactive_install', str(ROOT / 'installer/interactive_install.py'))
core = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(core)


class Console:
    def __init__(self, answers=(), confirmations=()):
        self.answers = iter(answers)
        self.confirmations = iter(confirmations)
        self.messages = []

    def say(self, message=''):
        self.messages.append(message)

    def ask(self, label, default='', validator=None):
        value = next(self.answers)
        value = default if value == '' else value
        return validator(str(value)) if validator else value

    def secret(self, label, previous='', minimum=1):
        return next(self.answers) or previous

    def confirm(self, label):
        return next(self.confirmations)


def values(role):
    result = {'server_name': 'qa-' + role, 'server_ip': '198.18.9.12'}
    if role == 'deploy':
        result.update(deploy_port=19090, nexus_port=18081, registry_port=15000,
                      db_root_password='root-password-fixture', db_password='app-password-fixture',
                      nexus_password='nexus-password-fixture', admin_password='web-password-fixture',
                      crypto_key='ab' * 32, sso_secret='cd' * 32)
    elif role == 'gitlab':
        result.update(gitlab_port=18929, git_ssh_port=12222, nexus_ip='198.18.9.11',
                      nexus_port=18081, registry_port=15000)
    else:
        result.update(control_ip='198.18.9.11', control_port=19090, server_id=37,
                      health_port=18080, health_path='/health', enrollment_code='one-use-code-fixture')
        if role == 'web':
            result.update(was_ip='198.18.9.15', was_port=28080, web_port=18080, proxy_port=18082)
    return result


class InteractiveTests(unittest.TestCase):
    def setUp(self):
        if os.name != 'posix' or os.geteuid() != 0:
            self.fail('This permission-sensitive suite requires native Linux root; no tests are skipped.')
        self.temp = tempfile.TemporaryDirectory(prefix='deploy-interactive-tests-', dir='/root')
        self.root = Path(self.temp.name)
        upgrade_dir = mock.patch.object(core, 'UPGRADE_STATE', self.root / 'upgrades')
        upgrade_dir.start()
        self.addCleanup(upgrade_dir.stop)
        self.package = self.root / 'package'
        self.package.mkdir(mode=0o700)
        (self.package / 'installer').mkdir(mode=0o700)
        (self.package / 'installer/install.sh').write_text(core.FAST_STAGING_CHECK, encoding='utf-8')
        self.console = Console()
        self.lifecycle_module = SimpleNamespace(before_install=mock.Mock(), after_install=mock.Mock(), LifecycleError=ValueError)
        hooks = mock.patch.object(core.Wizard, 'lifecycle', return_value=self.lifecycle_module)
        hooks.start()
        self.addCleanup(hooks.stop)

    def tearDown(self):
        self.temp.cleanup()

    def wizard(self, role):
        wizard = core.Wizard(self.package, self.root / 'state', self.console)
        wizard.identity = {'machine': 'test-machine', 'hostname': 'test-host'}
        wizard.directory = wizard.base / role
        wizard.state = {'version': core.VERSION, 'role': role, 'identity': wizard.identity,
                        'package': str(self.package), 'values': values(role), 'stages': {}}
        return wizard

    def map_system_files(self, agent=None, installed=None):
        """Only production file locations are redirected; all I/O remains real."""
        replacements = {'/etc/deploy-system/agent.json': agent or self.root / 'absent-agent.json',
                        '/opt/deploy-system/app/deploy-ops.jar': installed or self.root / 'absent.jar',
                        '/etc/machine-id': self.root / 'machine-id'}
        replacements['/etc/machine-id'].write_text('a' * 32 + '\n')
        real_path = Path
        return mock.patch.object(core, 'Path', side_effect=lambda value, *parts: real_path(replacements.get(str(value), value), *parts))

    def test_infrastructure_registration_passes_agent_and_restore_port(self):
        wizard = self.wizard('gitlab')
        self.console.answers = iter(['192.0.2.81', '9090', '42', '19929', 'one-use-code'])
        with self.map_system_files(), mock.patch.object(wizard, 'component', return_value=0) as component:
            wizard.enroll_infrastructure()
        args = component.call_args[0][2]
        self.assertEqual(args['DEPLOY_AGENT_TRANSPORT'], 'agent')
        self.assertEqual(args['DEPLOY_AGENT_TYPE'], 'gitlab')
        self.assertEqual(args['DEPLOY_INFRA_RESTORE_HTTP_PORT'], 19929)
        self.assertEqual(wizard.state['values']['restore_http_port'], 19929)

    def test_infrastructure_registration_refuses_live_restore_port(self):
        wizard = self.wizard('gitlab')
        self.console.answers = iter(['192.0.2.81', '9090', '42', '18929'])
        with self.map_system_files(), mock.patch.object(wizard, 'component') as component:
            with self.assertRaises(core.Refused):
                wizard.enroll_infrastructure()
        component.assert_not_called()

    def test_ip_and_port_reject_invalid_values_without_shell_interpretation(self):
        self.assertEqual(core.validate_ip('203.0.113.68'), '203.0.113.68')
        self.assertEqual(core.validate_port('65535'), 65535)
        for address in ('999.1.2.3', '127.0.0.1', '0.0.0.0', '224.0.0.1', '::1', '10.0.0.1;id'):
            with self.subTest(address=address), self.assertRaises(core.Refused):
                core.validate_ip(address)
        for port in ('0', '65536', '-1', '8080;id', '3.5', ''):
            with self.subTest(port=port), self.assertRaises(core.Refused):
                core.validate_port(port)

    def test_private_child_accepts_root_owned_0550_parent_without_chmod(self):
        parent = self.root / 'rocky-root-mode'
        parent.mkdir(mode=0o550)
        child = parent / 'installer' / 'gitlab'
        try:
            core.private_directory(child)
            self.assertEqual(stat.S_IMODE(parent.stat().st_mode), 0o550)
            self.assertEqual(stat.S_IMODE(child.parent.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(child.stat().st_mode), 0o700)
        finally:
            parent.chmod(0o700)

    def test_private_child_rejects_group_writable_existing_parent(self):
        parent = self.root / 'untrusted-parent'
        parent.mkdir(mode=0o700)
        parent.chmod(0o770)
        child = parent / 'installer'
        try:
            with self.assertRaises(core.Refused):
                core.private_directory(child)
            self.assertFalse(child.exists())
        finally:
            parent.chmod(0o700)

    def test_root_guard_prevents_any_wizard_state_write(self):
        wizard = self.wizard('web')
        with mock.patch.object(core.os, 'geteuid', return_value=1000):
            with self.assertRaises(core.Refused):
                wizard.start()
        self.assertFalse(wizard.base.exists())

    def test_host_guard_rejects_ubuntu_before_commands(self):
        with mock.patch.object(core.subprocess, 'run') as command:
            with mock.patch.object(core.Path, 'read_text', return_value='ID=ubuntu\nVERSION_ID="24.04"\n'):
                with self.assertRaises(core.Refused):
                    core.require_host()
            command.assert_not_called()

    def test_secret_input_uses_getpass_preserves_spaces_and_masks_summary(self):
        secret = '  preserved-password  '
        with mock.patch.object(core.getpass, 'getpass', return_value=secret) as hidden:
            with mock.patch('builtins.input', side_effect=AssertionError('secret entered with visible input')):
                self.assertEqual(core.Console().secret('암호', minimum=12), secret)
            hidden.assert_called_once()
        wizard = self.wizard('deploy')
        wizard.summary()
        output = '\n'.join(self.console.messages)
        for key in ('db_root_password', 'db_password', 'nexus_password', 'admin_password', 'crypto_key', 'sso_secret'):
            self.assertNotIn(wizard.state['values'][key], output)

    def test_answer_parser_roundtrips_metacharacters_as_data(self):
        sentinel = self.root / 'must-not-exist'
        payload = '$(touch {}) ; `id` # literal'.format(sentinel)
        answer = self.root / 'answers.env'
        answer.write_text('MARIADB_ROOT_PASSWORD={}\n'.format(core.encode_answer(payload)))
        parser = self.root / 'ui.sh'
        parser.write_text((ROOT / 'installer/lib/ui.sh').read_text().replace('\r\n', '\n'))
        script = self.root / 'parse.sh'
        script.write_text('source "$1"\ninfo() { :; }\nload_answers "$2" || exit $?\nprintf "%s" "$MARIADB_ROOT_PASSWORD"\n')
        completed = subprocess.run(['bash', str(script), str(parser), str(answer)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(completed.returncode, 0, completed.stderr.decode())
        self.assertEqual(completed.stdout.decode(), payload)
        self.assertFalse(sentinel.exists())
        for payload in ('two\nlines', 'bad\0value', 'both\'and"quotes'):
            with self.assertRaises(core.Refused):
                core.encode_answer(payload)

    def test_resume_after_upgrade_refuses_baseline_before_company_jar_writes(self):
        wizard = self.wizard('deploy')
        core.private_directory(core.UPGRADE_STATE)
        (self.package / 'installer/component_upgrade.py').write_text(
            'class Refused(Exception): pass\ndef verify_baseline_skip(record): raise Refused("runtime drift")\n')
        file = core.UPGRADE_STATE / 'nexus-worksystem-nexus.json'
        for phase in ('applying', 'verify', 'done'):
            core.write_private(file, json.dumps({'operation': {'component': 'nexus', 'target': 'new'},
                                                 'phase': phase}).encode())
            with mock.patch.object(wizard, 'configure_company_jar') as configure:
                with self.assertRaisesRegex(core.Refused, '10번'):
                    wizard.execute()
                configure.assert_not_called()
        with core.baseline_guard('nginx'):
            pass

    def test_web_resume_verifies_upgraded_nginx_and_installs_only_missing_agent(self):
        wizard = self.wizard('web')
        core.private_directory(core.UPGRADE_STATE)
        core.write_private(core.UPGRADE_STATE / 'nginx.json', json.dumps(
            {'operation': {'component': 'nginx', 'target': '1.30.4'}, 'phase': 'done'}).encode())
        (self.package / 'installer/component_upgrade.py').write_text(
            'class Refused(Exception): pass\ndef verify_baseline_skip(record): return record["operation"]["target"]=="1.30.4"\n')
        with mock.patch.object(wizard, 'install_components', return_value=0) as install:
            self.assertEqual(wizard.component('nginx,deploy-agent', 'target'), 0)
        install.assert_called_once_with('deploy-agent','target',None)
        self.assertEqual((wizard.directory / 'component-nginx.status').read_text(), 'VERIFIED\n')

    def test_upgrade_backup_uses_container_and_preserves_credentials(self):
        wizard = self.wizard('gitlab')
        wizard.io = Console(['runner', 'backup', 'custom-runner', '/root/backups/runner'], [True])
        (self.package / 'installer/component_upgrade.py').write_text('')
        with mock.patch.object(core.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as invoke:
            wizard.upgrade_menu()
        command = invoke.call_args[0][0]
        self.assertEqual(command[2:], ['backup', '--component', 'runner', '--container', 'custom-runner',
                                      '--backup', '/root/backups/runner', '--drained'])
        self.assertNotIn('--media', command)

    def test_relocation_accepts_registered_new_media_without_copying_credentials(self):
        wizard = self.wizard('gitlab')
        wizard.save()
        old = json.loads((wizard.directory / 'state.json').read_text())
        previous = self.package.parent / 'old-package'
        old['package'] = str(previous)
        core.write_private(wizard.directory / 'state.json', json.dumps(old).encode())
        index = self.root / 'index.json'
        core.write_private(index, json.dumps({'format': 1, 'package': str(self.package)}).encode())
        original_path = Path
        def locate(path, *rest):
            return original_path(index if str(path) == '/var/lib/deploy-installer/installation.json' else path, *rest)
        wizard.io = Console(confirmations=[True])
        with mock.patch.object(core, 'Path', side_effect=locate):
            updated = wizard.load('gitlab')
        self.assertEqual(old['values'], updated['values'])
        self.assertEqual(str(previous), updated['previous_package'])
        self.assertEqual(str(self.package), updated['package'])
        self.assertEqual(json.loads(index.read_text()), {'format': 1, 'package': str(self.package)})

    def test_state_is_root_private_and_role_isolated(self):
        deploy, web = self.wizard('deploy'), self.wizard('web')
        deploy.save()
        web.save()
        for wizard in (deploy, web):
            self.assertEqual(stat.S_IMODE(wizard.directory.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE((wizard.directory / 'state.json').stat().st_mode), 0o600)
            self.assertEqual((wizard.directory / 'state.json').stat().st_uid, 0)
        reloaded = self.wizard('deploy')
        self.assertEqual(reloaded.load('deploy')['values']['crypto_key'], deploy.state['values']['crypto_key'])
        self.assertNotIn('crypto_key', web.load('web')['values'])

    def test_resume_refuses_different_machine_role_or_package(self):
        wizard = self.wizard('deploy')
        wizard.save()
        path = wizard.directory / 'state.json'
        original = json.loads(path.read_text())
        for key, value in (('identity', {'machine': 'another'}), ('role', 'web'), ('package', '/another/package')):
            changed = copy.deepcopy(original)
            changed[key] = value
            core.write_private(path, json.dumps(changed).encode())
            with self.subTest(key=key), self.assertRaises(core.Refused):
                self.wizard('deploy').load('deploy')

    def test_secret_store_refuses_symlink_and_world_readable_existing_file(self):
        target = self.root / 'target'
        target.write_text('retain')
        target.chmod(0o600)
        link = self.root / 'link'
        link.symlink_to(target)
        with self.assertRaises(core.Refused):
            core.write_private(link, b'replace')
        self.assertEqual(target.read_text(), 'retain')
        target.chmod(0o644)
        with self.assertRaises(core.Refused):
            core.write_private(target, b'replace')
        self.assertEqual(target.read_text(), 'retain')

    def test_deploy_values_reach_answers_and_embedded_configuration(self):
        wizard = self.wizard('deploy')
        answer_path = wizard.prepare_answers()
        answer = answer_path.read_text()
        self.assertIn("NEXUS_WEB_PORT='18081'", answer)
        self.assertIn("NEXUS_REGISTRY_PORT='15000'", answer)
        self.assertIn('198.18.9.12:15000', answer)
        self.assertIn("NEXUS_MAVEN_REPAIR_DRIFT='n'", answer)
        configuration = core.company_config(wizard.state['values'])
        self.assertEqual(configuration['server']['port'], 19090)
        self.assertEqual(configuration['app']['sso']['base-url'], 'http://198.18.9.12:19090')
        self.assertEqual(configuration['spring']['datasource']['password'], 'app-password-fixture')

    def test_all_role_answers_are_accepted_by_the_actual_bash_parser(self):
        parser = self.root / 'ui.sh'
        parser.write_text((ROOT / 'installer/lib/ui.sh').read_text().replace('\r\n', '\n'))
        script = self.root / 'parse.sh'
        script.write_text('source "$1"\ninfo() { :; }\nload_answers "$2"\n')
        for role in ('deploy', 'gitlab', 'was', 'web'):
            wizard = self.wizard(role)
            answers = wizard.prepare_answers()
            completed = subprocess.run(['bash', str(script), str(parser), str(answers)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(completed.returncode, 0, role + ': ' + completed.stderr.decode())

    def test_web_parameters_reach_proxy_and_agent_answers(self):
        wizard = self.wizard('web')
        answer = wizard.prepare_answers().read_text()
        self.assertIn('http://198.18.9.11:19090', answer)
        self.assertIn("DEPLOY_AGENT_SERVER_ID='37'", answer)
        self.assertIn('18082|28080|198.18.9.15|no', answer)
        self.assertEqual((wizard.directory / 'enrollment-code').read_text(), 'one-use-code-fixture\n')
        self.assertEqual(stat.S_IMODE((wizard.directory / 'enrollment-code').stat().st_mode), 0o600)

    def test_component_validates_before_install_and_passes_no_secrets_in_argv(self):
        wizard = self.wizard('deploy')
        calls = []
        def completed(argv, **kwargs):
            calls.append((argv, kwargs))
            return subprocess.CompletedProcess(argv, 0)
        with mock.patch.object(core.subprocess, 'run', side_effect=completed), mock.patch.object(wizard, 'docker_preflight') as preflight:
            self.assertEqual(wizard.component('docker,mariadb', 'infrastructure'), 0)
        preflight.assert_called_once_with()
        self.assertEqual(calls[0][0][-1], '--validate')
        self.assertEqual(calls[1][0][-1], '--unattended')
        self.assertIn('--component-state', calls[1][0])
        self.lifecycle_module.before_install.assert_called_once_with(wizard)
        self.lifecycle_module.after_install.assert_called_once_with(wizard)
        for argv, kwargs in calls:
            self.assertNotIn('shell', kwargs)
            self.assertNotIn('app-password-fixture', ' '.join(argv))
            self.assertNotIn('PYTHONPATH', kwargs['env'])
        self.assertEqual(core.read_private(wizard.directory / 'state.json')['stages']['infrastructure'], 'SUCCEEDED')

    def test_docker_conflict_cancel_does_not_remove_or_run_installer(self):
        wizard = self.wizard('deploy')
        wizard.io = Console(confirmations=[False])
        helper = SimpleNamespace(ConflictRefused=type('ConflictRefused', (Exception,), {}),
                                 inspect=mock.Mock(return_value={'remove': [{'name': 'runc', 'nevra': 'runc-1.x86_64'}]}),
                                 ensure_unused=mock.Mock(), remove_approved=mock.Mock())
        with mock.patch.dict(core.sys.modules, {'deploy_docker_conflicts': helper}), mock.patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as command:
            with self.assertRaisesRegex(core.Refused, '취소'):
                wizard.component('docker,mariadb', 'infrastructure')
        helper.remove_approved.assert_not_called()
        self.assertEqual(command.call_count, 1)
        self.assertEqual(wizard.state['stages']['infrastructure'], 'FAILED')

    def test_docker_conflict_explicit_confirmation_and_recheck(self):
        wizard = self.wizard('gitlab')
        wizard.io = Console(confirmations=[True])
        removals = [{'name': 'runc', 'nevra': 'runc-1.x86_64'}]
        helper = SimpleNamespace(ConflictRefused=type('ConflictRefused', (Exception,), {}),
                                 inspect=mock.Mock(side_effect=[{'remove': removals}, {'remove': []}]),
                                 ensure_unused=mock.Mock(), remove_approved=mock.Mock())
        with mock.patch.dict(core.sys.modules, {'deploy_docker_conflicts': helper}):
            wizard.docker_preflight()
        helper.remove_approved.assert_called_once_with(wizard.package / 'installer/bundles/rocky8/docker', removals)
        self.assertTrue(any('runc-1.x86_64' in line for line in wizard.io.messages))

    def test_docker_failure_does_not_request_nexus_web_setup(self):
        wizard = self.wizard('deploy')
        with mock.patch.object(wizard, 'configure_company_jar', return_value=True), mock.patch.object(wizard, 'component', return_value=1):
            self.assertEqual(wizard.execute(), 1)
        self.assertFalse(any('약관/초기 암호 처리 후' in line for line in wizard.io.messages))
        self.assertTrue(any('무인 설치 실패' in line for line in wizard.io.messages))

    def test_interruption_after_preflight_never_publishes_infrastructure_success(self):
        wizard = self.wizard('deploy')
        wizard.state['stages'] = {'company-jar': 'SUCCEEDED', 'infrastructure': 'SUCCEEDED'}
        with mock.patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)), \
                mock.patch.object(wizard, 'docker_preflight', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                wizard.component('docker,mariadb,nexus,deploy-system', 'infrastructure')
        recorded = core.read_private(wizard.directory / 'state.json')['stages']
        self.assertEqual(recorded, {'company-jar': 'SUCCEEDED', 'infrastructure': 'PENDING',
                                    'infrastructure-preflight': 'SUCCEEDED'})
        self.lifecycle_module.before_install.assert_not_called()

    def test_validation_failure_does_not_run_install_or_claim_success(self):
        wizard = self.wizard('web')
        with mock.patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 2)) as command:
            self.assertEqual(wizard.component('nginx,deploy-agent', 'target'), 2)
        self.assertEqual(command.call_count, 1)
        self.assertEqual(core.read_private(wizard.directory / 'state.json')['stages']['target'], 'FAILED')

    def test_eula_pending_is_saved_as_waiting_then_can_resume(self):
        wizard = self.wizard('deploy')
        with mock.patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 20)):
            self.assertEqual(wizard.run_commands([['fake-component']], 'infrastructure'), 20)
        saved = self.wizard('deploy')
        saved.load('deploy')
        self.assertEqual(saved.state['stages']['infrastructure'], 'WAITING_NEXUS_WEB')
        with mock.patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)):
            self.assertEqual(saved.run_commands([['fake-component']], 'infrastructure'), 0)
        self.assertEqual(saved.state['stages']['infrastructure'], 'SUCCEEDED')

    def test_changing_configuration_invalidates_previous_success(self):
        wizard = self.wizard('web')
        wizard.state['stages'] = {'target': 'SUCCEEDED', 'health': 'SUCCEEDED'}
        changed = dict(wizard.state['values'])
        changed['web_port'] = 18083
        wizard.replace_values(changed)
        saved = core.read_private(wizard.directory / 'state.json')
        self.assertEqual(saved['stages'], {})
        self.assertEqual(saved['values']['web_port'], 18083)

    def test_partial_install_cannot_change_db_credentials_or_regenerate_keys(self):
        wizard = self.wizard('deploy')
        wizard.state['installation_started'] = True
        original = copy.deepcopy(wizard.state['values'])
        wizard.io = Console(answers=[''] * 9, confirmations=[True, False])
        with self.map_system_files():
            resumed = wizard.collect('deploy', original)
        self.assertEqual(resumed['crypto_key'], original['crypto_key'])
        self.assertEqual(resumed['sso_secret'], original['sso_secret'])
        for position in (5, 6):
            answers = [''] * 9
            answers[position] = 'different-database-password'
            wizard.io = Console(answers=answers, confirmations=[True, False])
            with self.map_system_files(), self.subTest(secret_position=position), self.assertRaises(core.Refused):
                wizard.collect('deploy', original)
        self.assertEqual(wizard.state['values'], original)

    def test_missing_command_marks_failure_and_retains_settings(self):
        wizard = self.wizard('was')
        with mock.patch.object(core.subprocess, 'run', side_effect=FileNotFoundError('private command')):
            with self.assertRaises(OSError):
                wizard.run_commands([['missing']], 'target')
        saved = core.read_private(wizard.directory / 'state.json')
        self.assertEqual(saved['stages']['target'], 'FAILED')
        self.assertEqual(saved['last_return_code'], 127)
        self.assertEqual(saved['values']['server_id'], 37)

    def test_runner_waits_for_web_setup_then_resumes_without_default_token(self):
        wizard = self.wizard('gitlab')
        wizard.io = Console(confirmations=[False])
        with mock.patch.object(wizard, 'component', return_value=0) as component:
            self.assertEqual(wizard.execute(), 20)
        self.assertEqual(component.call_args_list, [mock.call('docker,gitlab', 'gitlab')])
        self.assertEqual(core.read_private(wizard.directory / 'state.json')['stages']['runner'], 'WAITING_GITLAB_WEB')
        wizard.io = Console(answers=['22', '33', 'glrt-fixture-token', 'fixture-policy-pat'])
        wizard.runner_questions()
        answer = wizard.prepare_answers().read_text()
        self.assertIn("GITLAB_RUNNER_REGISTER='y'", answer)
        self.assertIn('glrt-fixture-token', answer)
        with mock.patch.object(wizard, 'component', return_value=0) as component, mock.patch.object(wizard, 'check_health', return_value=0):
            self.assertEqual(wizard.execute(), 0)
        self.assertEqual(component.call_args_list[-1], mock.call('gitlab-runner', 'runner'))

    def test_web_installs_nginx_and_agent_without_docker(self):
        wizard = self.wizard('web')
        with self.map_system_files(), mock.patch.object(wizard, 'component', return_value=0) as component, mock.patch.object(wizard, 'check_health', return_value=0):
            self.assertEqual(wizard.execute(), 0)
        component.assert_called_once_with('nginx,deploy-agent', 'target')

    def test_existing_agent_is_not_reenrolled_and_identity_mismatch_is_refused(self):
        wizard = self.wizard('was')
        agent = self.root / 'agent.json'
        payload = {'url': 'http://198.18.9.11:19090', 'serverId': 37, 'role': 'WAS', 'machineId': 'a' * 32, 'credential': 'keep-private-token'}
        core.write_private(agent, json.dumps(payload).encode())
        before = agent.read_bytes()
        with self.map_system_files(agent=agent), mock.patch.object(wizard, 'component') as component, mock.patch.object(wizard, 'check_health', return_value=0):
            self.assertEqual(wizard.execute(), 0)
            component.assert_not_called()
            wizard.state['values']['server_id'] = 38
            with self.assertRaises(core.Refused):
                wizard.execute()
        self.assertEqual(agent.read_bytes(), before)

    def test_agent_ready_false_cannot_be_reported_as_success(self):
        wizard = self.wizard('web')
        responses = [subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 0, b'{"ready":false}', b'')]
        with mock.patch.object(core.subprocess, 'run', side_effect=responses):
            self.assertEqual(wizard.check_health(), 1)
        self.assertEqual(core.read_private(wizard.directory / 'state.json')['stages']['health'], 'FAILED')

    def test_missing_agent_probe_cannot_leave_health_success(self):
        wizard = self.wizard('was')
        responses = [subprocess.CompletedProcess([], 0), FileNotFoundError('missing probe')]
        with mock.patch.object(core.subprocess, 'run', side_effect=responses):
            self.assertNotEqual(wizard.check_health(), 0)
        saved = core.read_private(wizard.directory / 'state.json')
        self.assertEqual(saved['stages']['health'], 'FAILED')
        self.assertEqual(saved['last_return_code'], 127)

    def test_public_shell_entry_routes_interactive_and_preserves_legacy_cli(self):
        installer = self.root / 'entry'
        (installer / 'lib').mkdir(parents=True)
        (installer / 'components').mkdir()
        (installer / 'components/fixture.sh').write_text(':\n')
        (installer / 'install.sh').write_bytes((ROOT / 'installer/install.sh').read_bytes())
        (installer / 'interactive-install.sh').write_text('printf "wizard:%s\\n" "$#"\nexit 74\n')
        (installer / 'lib/release-metadata.sh').write_text('printf "legacy:%s\\n" "$*"\nexit 73\n')
        for arguments in ([], ['--wizard']):
            completed = subprocess.run(['bash', str(installer / 'install.sh')] + arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(completed.returncode, 74, completed.stderr.decode())
            self.assertEqual(completed.stdout.decode(), 'wizard:0\n')
        for arguments in (['--legacy-menu'], ['--validate', '--components', 'nginx'], ['--unattended', '--components', 'docker'], ['--help']):
            completed = subprocess.run(['bash', str(installer / 'install.sh')] + arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(completed.returncode, 73, completed.stderr.decode())
            expected = [] if arguments == ['--legacy-menu'] else arguments
            self.assertEqual(completed.stdout.decode(), 'legacy:' + ' '.join(expected) + '\n')

    def test_continuity_rejects_db_key_or_port_changes_but_allows_admin_password(self):
        current = core.company_config(values('deploy'))
        for path in core.CONTINUITY:
            candidate = copy.deepcopy(current)
            target = candidate
            parts = path.split('.')
            for part in parts[:-1]:
                target = target[part]
            target[parts[-1]] = 'changed'
            with self.subTest(path=path), self.assertRaises(core.Refused):
                core.validate_continuity(current, candidate)
        candidate = copy.deepcopy(current)
        candidate['app']['bootstrap']['admin-password'] = 'new-initial-password'
        core.validate_continuity(current, candidate)

    def jar_fixture(self, wizard):
        release = ROOT if (ROOT / 'operations/configure-deploy-jar.py').is_file() else ROOT / 'dist/DeployOps-Offline-Demo'
        for relative in ('operations/configure-deploy-jar.py', 'operations/jar_configuration.py', 'installer/deploy-system/embedded-config.py'):
            source = release / relative
            destination = self.package / relative
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            destination.write_bytes(source.read_bytes())
            destination.chmod(0o600)
        bundle = self.package / 'installer/bundles/common/deploy-system/deploy-ops.jar'
        bundle.parent.mkdir(parents=True, mode=0o700)
        with zipfile.ZipFile(str(bundle), 'w') as archive:
            archive.writestr(core.ENTRY, json.dumps(core.company_config(values('deploy'))))
            archive.writestr('BOOT-INF/lib/unchanged.jar', b'fixed-library-bytes', compress_type=zipfile.ZIP_STORED)
        bundle.chmod(0o600)
        return bundle

    def test_real_company_jar_helpers_preserve_original_and_nonconfiguration_bytes(self):
        wizard = self.wizard('deploy')
        bundle = self.jar_fixture(wizard)
        original = bundle.read_bytes()
        with self.map_system_files():
            self.assertTrue(wizard.configure_company_jar())
        self.assertEqual((wizard.directory / 'package-original.jar').read_bytes(), original)
        with zipfile.ZipFile(str(bundle)) as archive:
            self.assertEqual(archive.read('BOOT-INF/lib/unchanged.jar'), b'fixed-library-bytes')
            self.assertEqual(archive.getinfo('BOOT-INF/lib/unchanged.jar').compress_type, zipfile.ZIP_STORED)
            self.assertEqual(json.loads(archive.read(core.ENTRY))['app']['crypto']['secret-key'], 'ab' * 32)
        self.assertTrue(core.read_private(wizard.directory / 'state.json')['company_jar_prepared'])

    def test_existing_deploy_continuity_failure_leaves_all_jar_bytes_unchanged(self):
        wizard = self.wizard('deploy')
        bundle = self.jar_fixture(wizard)
        installed = self.root / 'installed.jar'
        shutil.copyfile(str(bundle), str(installed))
        installed.chmod(0o600)
        original = bundle.read_bytes()
        wizard.state['values']['crypto_key'] = 'ef' * 32
        with self.map_system_files(installed=installed), mock.patch.object(core.subprocess, 'run') as command:
            with self.assertRaises(core.Refused):
                wizard.configure_company_jar()
        command.assert_not_called()
        self.assertEqual(installed.read_bytes(), original)
        self.assertEqual(bundle.read_bytes(), original)

    def test_failed_jar_validation_does_not_publish_or_mark_prepared(self):
        wizard = self.wizard('deploy')
        bundle = self.jar_fixture(wizard)
        original = bundle.read_bytes()
        with self.map_system_files(), mock.patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)):
            self.assertFalse(wizard.configure_company_jar())
        self.assertEqual(bundle.read_bytes(), original)
        self.assertNotIn('company_jar_prepared', wizard.state)
        self.assertEqual(wizard.state['stages']['company-jar'], 'FAILED')

    def test_failed_second_jar_step_removes_its_temporary_output(self):
        wizard = self.wizard('deploy')
        bundle = self.jar_fixture(wizard)
        before = bundle.read_bytes()
        def command(argv, **kwargs):
            if '--output' in argv:
                Path(argv[argv.index('--output') + 1]).write_bytes(b'candidate-output')
                return subprocess.CompletedProcess(argv, 0)
            return subprocess.CompletedProcess(argv, 1)
        with self.map_system_files(), mock.patch.object(core.subprocess, 'run', side_effect=command):
            self.assertFalse(wizard.configure_company_jar())
        self.assertEqual(list(wizard.directory.glob('deploy-company-*.jar')), [])
        self.assertEqual(bundle.read_bytes(), before)


if __name__ == '__main__':
    unittest.main(verbosity=2)
