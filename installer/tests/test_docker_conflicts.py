import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('docker_conflicts', str(Path(__file__).resolve().parents[1] / 'lib/docker-conflicts.py'))
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


class Package:
    def __init__(self, name, version='1'):
        self.name, self.version = name, version
    def __str__(self):
        return self.name + '-' + self.version + '.x86_64'


class ConflictTests(unittest.TestCase):
    def test_removal_allowlist_rejects_security_agent_dependency(self):
        transaction = SimpleNamespace(install_set=[], remove_set=[Package('runc'), Package('company-security-agent')])
        with self.assertRaisesRegex(helper.ConflictRefused, 'company-security-agent'):
            helper.removal_records(transaction)

    def test_upgrade_is_not_an_erasure_but_known_tools_are(self):
        transaction = SimpleNamespace(install_set=[Package('libseccomp', '2')],
                                      remove_set=[Package('libseccomp'), Package('runc')])
        self.assertEqual(helper.removal_records(transaction), [{'name': 'runc', 'nevra': 'runc-1.x86_64'}])

    def test_active_container_service_blocks(self):
        with mock.patch.object(helper.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
            with self.assertRaisesRegex(helper.ConflictRefused, '사용 중'):
                helper.ensure_unused()

    def test_inability_to_inspect_services_blocks(self):
        with mock.patch.object(helper.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(helper.ConflictRefused, '확인하지 못'):
                helper.ensure_unused()

    def test_changed_removal_plan_blocks_before_dnf_transaction(self):
        approved = [{'name': 'runc', 'nevra': 'runc-1.x86_64'}]
        with mock.patch.object(helper, 'ensure_unused'), mock.patch.object(helper, 'inspect', return_value={'remove': []}):
            with self.assertRaisesRegex(helper.ConflictRefused, '변경'):
                helper.remove_approved(Path('/fixture'), approved)

    def test_unknown_requested_erasure_blocks_without_system_operations(self):
        with mock.patch.object(helper, 'ensure_unused') as check:
            with self.assertRaises(helper.ConflictRefused):
                helper.remove_approved(Path('/fixture'), [{'name': 'openssh-server', 'nevra': 'openssh-server-1'}])
            check.assert_not_called()

    def test_lock_requires_every_docker_component(self):
        with tempfile.TemporaryDirectory() as temp:
            (Path(temp) / 'PACKAGES.lock').write_text('containerd.io|1|x86_64|hash|file.rpm\n')
            with self.assertRaisesRegex(helper.ConflictRefused, '누락'):
                helper.docker_specs(temp)

    def test_custom_storage_configuration_refuses_automatic_removal(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'storage.conf'
            path.write_text('[storage]\ngraphroot = "/srv/podman-data"\n')
            with self.assertRaisesRegex(helper.ConflictRefused, '비표준'):
                helper.validate_storage_config(path)
            path.write_text('[storage]\ngraphroot = "/var/lib/containers/storage"\n')
            helper.validate_storage_config(path)
            with self.assertRaisesRegex(helper.ConflictRefused, '사용자 지정'):
                helper.validate_storage_config(path, rootless=True)

    def test_only_empty_systemd_tmp_directory_is_harmless(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'tmp').mkdir()
            self.assertFalse(helper.storage_has_data(root))
            (root / 'tmp' / 'partial-layer').write_text('existing data')
            self.assertTrue(helper.storage_has_data(root))


if __name__ == '__main__':
    unittest.main()
