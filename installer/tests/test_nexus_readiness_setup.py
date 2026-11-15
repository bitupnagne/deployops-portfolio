import importlib.util
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock
from unittest.mock import patch
import contextlib
import tempfile
import os
import unittest
import json

spec = importlib.util.spec_from_file_location('setup', str(Path(__file__).resolve().parents[1] / 'lib/nexus-readiness-setup.py'))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.config = {'externalWritersManaged': False, 'allStorageInDataVolume': False}
        self.restore = {'schema': 1, 'enabled': False, 'portBase': 18000}
        self.b = Mock(CONFIG=Path('/config'))
        self.b.read_file.side_effect = lambda path, *a, **k: json.dumps(self.config if path == Path('/config') else self.restore).encode()
        self.b.parse_unique.side_effect = json.loads
        self.b.validate_config.side_effect = lambda value: value
        self.worker = Mock(CONFIG=Path('/restore'))
        def validate(value):
            if not 1024 <= value['portBase'] <= 30000:
                raise ValueError('PORT')
            return value
        self.worker.validate_config.side_effect = validate
        self.b.load_restore_worker.return_value = self.worker
        self.b.source_readiness.return_value = (True, None)
        self.config.update(containerName='nexus', volume='data')
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.data = Path(directory.name)
        self.library = self.b.load_library.return_value
        self.library.inspect.return_value = {'Config': {'Env': []}}
        self.library.volume_path.return_value = self.data

    def test_each_confirmation_required_before_probe(self):
        for confirmations in ((False, True, True), (True, False, True), (True, True, False)):
            with self.assertRaises(ValueError):
                setup.prepare(self.b, *confirmations, port=18000)
        self.b.read_file.assert_not_called()

    def test_live_failed_probe_never_returns_change(self):
        self.b.source_readiness.return_value = (False, 'KEY_OR_STORAGE')
        with self.assertRaises(ValueError):
            setup.prepare(self.b, True, True, True, 18000)
        self.assertFalse(self.config['externalWritersManaged'])

    def test_invalid_restore_port_rejected(self):
        with self.assertRaises(ValueError):
            setup.prepare(self.b, True, True, True, 80)
        self.b.source_readiness.assert_not_called()

    def test_external_jdbc_rejected_despite_h2_file(self):
        self.library.inspect.return_value = {'Config': {'Env': ['NEXUS_DATASTORE_NEXUS_JDBCURL=jdbc:postgresql://db/nexus']}}
        with self.assertRaises(setup.SetupError):
            setup.prepare(self.b, True, True, True, 18000)
        self.b.source_readiness.assert_not_called()

    def test_valid_candidate_uses_actual_probe_and_preserves_preimages(self):
        changes = setup.prepare(self.b, True, True, True, 18000)
        self.assertEqual(2, len(changes))
        self.assertTrue(changes[0][2]['externalWritersManaged'])
        self.assertTrue(changes[1][2]['enabled'])
        self.assertFalse(json.loads(changes[0][1])['allStorageInDataVolume'])
        self.b.source_readiness.assert_called_once()

    def test_changed_preimage_refuses_before_any_write(self):
        changes = setup.prepare(self.b, True, True, True, 18000)
        self.config['externalWritersManaged'] = True
        write = Mock()
        with self.assertRaises(ValueError):
            setup.commit(self.b, changes, write)
        write.assert_not_called()

    def test_atomic_config_preserves_public_parent_and_private_file(self):
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory, 0o755)
            path = Path(directory) / 'config.json'
            setup.atomic_config(path, b'{"enabled":true}')
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual(0o755, Path(directory).stat().st_mode & 0o777)
            self.assertEqual(b'{"enabled":true}', path.read_bytes())

    def test_commit_retains_exact_preimages_for_both_configs(self):
        with tempfile.TemporaryDirectory() as directory:
            changes = []
            for name in ('backup.json', 'restore.json'):
                path = Path(directory) / name
                old = b'{"enabled":false}\n'
                path.write_bytes(old)
                changes.append((path, old, {'enabled': True}))
            self.b.read_file.side_effect = lambda path, *a, **k: path.read_bytes()
            setup.commit(self.b, changes)
            for path, old, value in changes:
                copies = list(path.parent.glob(path.name + '.before-onboarding-*'))
                self.assertEqual(1, len(copies))
                self.assertEqual(old, copies[0].read_bytes())
                self.assertEqual(0o600, copies[0].stat().st_mode & 0o777)
                self.assertEqual(value, json.loads(path.read_text()))

    def test_second_write_failure_rolls_back_both(self):
        with tempfile.TemporaryDirectory() as directory:
            changes = [(Path(directory) / name, b'{"enabled":false}', {'enabled': True}) for name in ('backup', 'restore')]
            for path, old, value in changes:
                path.write_bytes(old)
            self.b.read_file.side_effect = lambda path, *a, **k: path.read_bytes()
            count = [0]
            def write(path, payload):
                count[0] += 1
                if count[0] == 2:
                    raise OSError('simulated')
                setup.atomic_config(path, payload)
            with self.assertRaises(setup.SetupError):
                setup.commit(self.b, changes, write)
            for path, old, value in changes:
                self.assertEqual(old, path.read_bytes())

    def test_keyboard_interrupt_during_second_write_restores_both(self):
        with tempfile.TemporaryDirectory() as directory:
            changes = [(Path(directory) / name, b'{"enabled":false}', {'enabled': True}) for name in ('backup', 'restore')]
            for path, old, value in changes:
                path.write_bytes(old)
            self.b.read_file.side_effect = lambda path, *a, **k: path.read_bytes()
            count = [0]
            def write(path, payload):
                count[0] += 1
                if count[0] == 2:
                    raise KeyboardInterrupt()
                setup.atomic_config(path, payload)
            with self.assertRaises(setup.SetupError):
                setup.commit(self.b, changes, write)
            for path, old, value in changes:
                self.assertEqual(old, path.read_bytes())


class BackupMenuTests(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / 'interactive_install.py'
        spec = importlib.util.spec_from_file_location('wizard_test', str(path))
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)
        self.w = Mock(state={'role': 'deploy'})
        self.w.io.ask.return_value = '2'
        self.w.io.confirm.return_value = True

    def test_other_role_never_launches_backup(self):
        self.w.state['role'] = 'was'
        with patch.object(self.mod.subprocess, 'run') as run:
            self.mod.Wizard.backup_menu(self.w)
            run.assert_not_called()

    def test_declined_drain_never_launches_backup(self):
        self.w.io.confirm.return_value = False
        with patch.object(self.mod, 'baseline_guard', return_value=contextlib.ExitStack()), patch.object(self.mod.subprocess, 'run') as run:
            self.mod.Wizard.backup_menu(self.w)
            run.assert_not_called()

    def test_script_failure_not_reported_complete(self):
        with patch.object(self.mod, 'baseline_guard', return_value=contextlib.ExitStack()), patch.object(self.mod, 'trusted_path', side_effect=lambda p: p), patch.object(self.mod.subprocess, 'run', return_value=Mock(returncode=1)) as run:
            with self.assertRaises(self.mod.Refused):
                self.mod.Wizard.backup_menu(self.w)
            self.assertEqual(['bash', '/usr/local/deploy/bin/deployops-backup.sh'], run.call_args[0][0])


if __name__ == '__main__':
    unittest.main()
