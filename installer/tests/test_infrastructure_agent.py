import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('infra', Path(__file__).parents[1] / 'infrastructure/deploy-infrastructure.py')
infra = importlib.util.module_from_spec(spec)
spec.loader.exec_module(infra)


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'Linux root file ownership test')
class InfrastructureAgentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir='/root')
        self.base = Path(self.temporary.name)
        self.old = infra.BROKER
        self.old_jobs = infra.JOBS
        infra.BROKER = self.base
        infra.JOBS = self.base / 'jobs'
        for name in ('inbox', 'results'):
            (self.base / name).mkdir(mode=0o700)

    def tearDown(self):
        infra.BROKER = self.old
        infra.JOBS = self.old_jobs
        self.temporary.cleanup()

    def test_traversal_and_arbitrary_files_are_rejected(self):
        for path in ('../etc/passwd', 'results/../../passwd', 'results/config.json', '/etc/passwd'):
            with self.assertRaises(ValueError):
                infra.broker_path(path)

    def test_publish_retry_preserves_identical_request_and_rejects_replacement(self):
        request = {'operation': 'publish', 'path': 'inbox/1.json', 'payload': {'schema': 1, 'requestId': 1, 'action': 'BACKUP'}}
        infra.nexus_broker(request)
        before = (self.base / 'inbox/1.json').read_bytes()
        infra.nexus_broker(request)
        self.assertEqual(before, (self.base / 'inbox/1.json').read_bytes())
        request['payload']['action'] = 'PROMOTE'
        with self.assertRaises(ValueError):
            infra.nexus_broker(request)
        self.assertEqual(before, (self.base / 'inbox/1.json').read_bytes())

    def test_result_requires_root_restricted_mode_and_reads_exact_bytes(self):
        path = self.base / 'results/ready.json'
        path.write_bytes(b'{"ready":true}')
        path.chmod(0o640)
        request = {'operation': 'read', 'path': 'results/ready.json'}
        self.assertEqual(path.read_bytes(), infra.nexus_broker(request))
        path.chmod(0o666)
        with self.assertRaises(ValueError):
            infra.nexus_broker(request)

    def test_symlink_result_and_write_outside_inbox_rejected(self):
        (self.base / 'results/1.json').symlink_to('/etc/passwd')
        with self.assertRaises(ValueError):
            infra.nexus_broker({'operation': 'read', 'path': 'results/1.json'})
        with self.assertRaises(ValueError):
            infra.nexus_broker({'operation': 'publish', 'path': 'results/2.json', 'payload': {}})

    def test_capture_drains_large_output_with_bounded_return(self):
        output, truncated = infra.capture([sys.executable, '-c', 'print("x" * 3000000)'], limit=1024)
        self.assertTrue(truncated)
        self.assertEqual(1024, len(output))

    def test_service_scope_blocks_other_containers(self):
        config = {'role': 'RUNNER', 'containers': {'gitlab': 'unrelated', 'runner': 'runner'}}
        with self.assertRaises(ValueError):
            infra.target(config, 'gitlab')
        self.assertEqual('runner', infra.target(config, 'runner'))

    def test_nexus_lifecycle_requires_maintenance_workflow(self):
        config = {'role': 'NEXUS', 'containers': {'nexus': 'nexus'}}
        with mock.patch.object(infra, 'host_config', return_value=config), mock.patch.object(infra, 'run') as run:
            with self.assertRaises(ValueError):
                infra.host_operation('stop', {'service': 'nexus'})
            run.assert_not_called()

    def test_interrupted_job_is_durable_and_cannot_repeat(self):
        identity = 'a' * 32
        marker = infra.begin_maintenance('restore', identity, {'backupId': 'b' * 32})
        self.assertEqual('STARTED', json.loads(marker.read_text())['state'])
        with self.assertRaises(FileExistsError):
            infra.begin_maintenance('restore', identity, {})
        self.assertEqual('STARTED', infra.maintenance_history()[0]['state'])
        infra.finish_maintenance(marker, {'completed': True})
        self.assertEqual('COMPLETED', infra.maintenance_history()[0]['state'])
        with self.assertRaises(FileExistsError):
            infra.begin_maintenance('restore', identity, {})

    def test_maintenance_rejects_symlink_ancestor_and_writable_parent(self):
        target = self.base / 'target'
        target.mkdir(mode=0o700)
        link = self.base / 'link'
        link.symlink_to(target)
        with self.assertRaises(ValueError):
            infra.secure_directory(link / 'nested')
        target.chmod(0o777)
        with self.assertRaises(ValueError):
            infra.secure_directory(target / 'nested')

    def test_stopped_required_service_is_not_ready(self):
        config = {'role': 'GITLAB', 'containers': {'gitlab': 'gitlab', 'runner': 'runner'}}
        with mock.patch.object(infra, 'host_config', return_value=config), mock.patch.object(infra, 'run', side_effect=[b'{"Running":false,"Status":"exited"}', ValueError()]):
            result = infra.host_operation('status', {})
        self.assertFalse(result['ready'])
        self.assertEqual('NOT_INSTALLED', result['services'][1]['status'])

    def test_running_gitlab_without_optional_runner_is_ready(self):
        config = {'role': 'GITLAB', 'containers': {'gitlab': 'gitlab', 'runner': 'runner'}}
        with mock.patch.object(infra, 'host_config', return_value=config), mock.patch.object(infra, 'run', side_effect=[b'{"Running":true,"Status":"running"}', ValueError()]):
            self.assertTrue(infra.host_operation('status', {})['ready'])

    def test_concurrent_mutation_cannot_restart_during_backup(self):
        import fcntl
        root = infra.secure_directory(infra.JOBS)
        with (root / 'maintenance.lock').open('w') as held:
            fcntl.flock(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with mock.patch.object(infra, 'perform_host_operation') as perform:
                with self.assertRaises(ValueError):
                    infra.host_operation('restart', {'service': 'gitlab'})
                perform.assert_not_called()


if __name__ == '__main__':
    unittest.main()
