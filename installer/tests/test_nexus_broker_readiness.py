"""Linux temporary-file readiness tests; no Docker, credentials, or service changes."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock
from contextlib import ExitStack


SOURCE = Path(__file__).resolve().parents[1] / 'nexus-backup' / 'nexus-backup-broker.py'
spec = importlib.util.spec_from_file_location('readiness_broker', SOURCE)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        # The real broker rejects world-writable ancestors such as /tmp.
        # Keep this root-owned fixture beside the isolated test sources.
        self.temp = tempfile.TemporaryDirectory(dir=str(SOURCE.parent.parent.parent))
        self.base = Path(self.temp.name)
        self.base.chmod(0o755)
        for name, mode in [('results', 0o750), ('state', 0o700)]:
            (self.base / name).mkdir()
            (self.base / name).chmod(mode)
        self.path = self.base / 'results/ready.json'
        self.previous_umask = os.umask(0o077)
        b.Store(self.base, 10001, os.getgid()).write(self.path, {
            'schema': 1, 'ready': True, 'restoreReady': True,
            'promotionReady': True, 'externalWritersManaged': True,
            'readinessCode': None})
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.object(b, 'BASE', self.base))
        self.stack.enter_context(mock.patch.object(b, 'install_signal_handlers'))
        self.stack.enter_context(mock.patch.object(b.Store, 'check_directories'))
        self.account = self.stack.enter_context(mock.patch('pwd.getpwnam',
            return_value=SimpleNamespace(pw_uid=10001, pw_gid=os.getgid())))
        self.stack.enter_context(mock.patch.object(b, 'read_file', return_value=b'{}'))
        self.config = self.stack.enter_context(mock.patch.object(b, 'validate_config',
            return_value={'appUser': 'qa-fixture', 'externalWritersManaged': True}))
        self.library = self.stack.enter_context(mock.patch.object(b, 'load_library', return_value=object()))
        self.restore = SimpleNamespace(CONFIG=self.base / 'restore.json',
            validate_config=mock.Mock(return_value={'enabled': True}),
            run_queue=mock.Mock(return_value=False))
        self.stack.enter_context(mock.patch.object(b, 'load_restore_worker', return_value=self.restore))
        self.promotion = SimpleNamespace(run_queue=mock.Mock(return_value=False))
        self.stack.enter_context(mock.patch.object(b, 'load_promotion_worker', return_value=self.promotion))
        self.probe = self.stack.enter_context(mock.patch.object(b, 'source_readiness', return_value=(True, None)))
        self.backups = self.stack.enter_context(mock.patch.object(b, 'run_queue'))

    def tearDown(self):
        self.stack.close()
        os.umask(self.previous_umask)
        self.temp.cleanup()

    def snapshot(self):
        return json.loads(self.path.read_text())

    def test_concurrent_reader_keeps_last_success_during_successful_probe(self):
        entered, release = threading.Event(), threading.Event()
        failures = []
        def probe(*unused):
            entered.set()
            if not release.wait(5):
                raise RuntimeError('test probe was not released')
            return True, None
        def run():
            try:
                b.main()
            except BaseException as error:
                failures.append(type(error).__name__)
        self.probe.side_effect = probe
        worker = threading.Thread(target=run)
        worker.start()
        try:
            self.assertTrue(entered.wait(5))
            for unused in range(100):
                state = self.snapshot()
                self.assertTrue(state['ready'])
                self.assertTrue(state['promotionReady'])
        finally:
            release.set()
            worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual([], failures)
        self.assertTrue(self.snapshot()['ready'])
        self.backups.assert_called_once()

    def test_failed_source_probe_atomically_invalidates_all_readiness(self):
        self.probe.return_value = (False, 'SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE')
        b.main()
        state = self.snapshot()
        self.assertFalse(state['ready'])
        self.assertFalse(state['restoreReady'])
        self.assertFalse(state['promotionReady'])
        self.assertEqual('SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE', state['readinessCode'])
        self.promotion.run_queue.assert_called_once()
        self.assertFalse(self.promotion.run_queue.call_args[1]['ready'])
        self.backups.assert_not_called()

    def test_missing_config_invalidates_previous_success_before_store_resolution(self):
        self.config.side_effect = ValueError('fixture configuration missing')
        with self.assertRaises(ValueError):
            b.main()
        self.assertFalse(self.snapshot()['ready'])
        self.assertFalse(self.snapshot()['promotionReady'])
        self.account.assert_not_called()
        self.backups.assert_not_called()

    def test_missing_account_invalidates_previous_success(self):
        self.account.side_effect = KeyError('fixture account absent')
        with self.assertRaises(KeyError):
            b.main()
        self.assertFalse(self.snapshot()['ready'])
        self.backups.assert_not_called()

    def test_missing_helper_and_unexpected_probe_error_invalidate_previous_success(self):
        for target in (self.library, self.probe):
            with self.subTest(target='helper' if target is self.library else 'probe'):
                b.Store(self.base, 10001, os.getgid()).write(self.path, {
                    'schema': 1, 'ready': True, 'restoreReady': True,
                    'promotionReady': True, 'externalWritersManaged': True})
                target.side_effect = RuntimeError('fixture unavailable')
                with self.assertRaises(RuntimeError):
                    b.main()
                self.assertFalse(self.snapshot()['ready'])
                self.assertFalse(self.snapshot()['promotionReady'])
                target.side_effect = None
        self.backups.assert_not_called()

    def test_missing_results_directory_is_rejected_without_recreating_trust_boundary(self):
        self.path.unlink()
        self.path.parent.rmdir()
        with self.assertRaises(FileNotFoundError):
            b.main()
        self.assertFalse(self.path.exists())
        self.assertFalse(self.path.parent.exists())
        self.config.assert_not_called()


if __name__ == '__main__':
    unittest.main()
