#!/usr/bin/python3
"""Linux fixture: filesystem/identity checks, UID IO, immutable state and boot gates."""
import copy
import importlib.util
import os
import pathlib
import shutil
import tempfile
import unittest
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'deploy-agent/was/deploy-storage.py'
loader = importlib.util.spec_from_file_location('storage', str(SOURCE))
s = importlib.util.module_from_spec(loader)
loader.loader.exec_module(s)


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='deploy-storage-fixture-', dir='/var/lib')
        os.chmod(self.tmp, 0o755)
        self.root = self.tmp + '/data'
        os.mkdir(self.root, 0o755)
        os.chown(self.root, 10001, 10001)
        self.state = self.tmp + '/state'
        os.mkdir(self.state, 0o700)
        self.patches = [patch.object(s, 'ROOT', self.state),
                        patch.object(s, 'RECOVERY_ROOT', self.tmp + '/recovery'),
                        patch.object(s, 'command', self.command)]
        for p in self.patches:
            p.start()
        self.observed = dict(rootPath=self.root, mountTarget='/mnt/nas', mountSource='nas:/export', fsType='nfs4')
        self.location = dict(storageKey='files', kind='NAS', enabled=True, **self.observed)
        s.write_json(self.state + '/allowlist.json', dict(schemaVersion=1, serverId=1, locations=[self.location]))
        self.spec = dict(schemaVersion=1, serverId=1, projectId=2, componentId=3, requestId=4,
                         environment='TEST', containerName='deploy-project-2', containerUser='10001:10001',
                         mounts=[dict(storageKey='files', kind='NAS', relativePath='', containerPath='/app/uploads', readOnly=False, **self.observed)])
        self.started = []

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        shutil.rmtree(self.tmp)

    def ancestry(self, path, private=False):
        # Test state lives below /tmp. Still reject symlink/non-directory on each segment.
        current = '/'
        for part in path.strip('/').split('/'):
            current = os.path.join(current, part)
            if os.path.islink(current) or not os.path.isdir(current):
                raise ValueError('fixture symlink/missing directory')

    def command(self, args):
        if args[0] == 'findmnt':
            import json
            return json.dumps(dict(filesystems=[dict(target=self.observed['mountTarget'], source=self.observed['mountSource'], fstype=self.observed['fsType'])]))
        if args[:2] == ['docker', 'start']:
            self.started.append(args[2])
            return args[2]
        raise AssertionError(args)

    def live_identity(self, path, kind):
        return dict(self.observed, rootPath=path)

    def valid(self, spec=None):
        with patch.object(s, 'identity', self.live_identity):
            return s.validate_spec(spec or self.spec)

    def test_rw_probe_uses_application_uid_and_cleans_file(self):
        self.assertEqual(self.valid(), ['type=bind,src=' + self.root + ',dst=/app/uploads'])
        self.assertEqual(os.listdir(self.root), [])
        os.chmod(self.root, 0o555)
        with self.assertRaisesRegex(ValueError, 'permission'):
            self.valid()
        self.spec['mounts'][0]['readOnly'] = True
        self.assertTrue(self.valid()[0].endswith(',readonly'))

    def test_nas_missing_or_replaced_never_falls_back_local(self):
        self.observed.update(mountTarget='/', mountSource='/dev/sda', fsType='ext4')
        with self.assertRaisesRegex(ValueError, 'identity changed'):
            self.valid()

    def test_real_identity_rejects_nas_rootfs_fallback(self):
        self.observed.update(mountTarget='/', mountSource='/dev/sda', fsType='ext4')
        with self.assertRaisesRegex(ValueError, 'kind mismatch'):
            s.identity(self.root, 'NAS')

    def test_disabled_or_identity_drift_rejects(self):
        self.location['enabled'] = False
        s.write_json(self.state + '/allowlist.json', dict(serverId=1, locations=[self.location]))
        with self.assertRaisesRegex(ValueError, 'disabled'):
            self.valid()

    def test_missing_relative_directory_is_not_created(self):
        self.spec['mounts'][0]['relativePath'] = 'missing'
        with self.assertRaises((ValueError, OSError)):
            self.valid()
        self.assertFalse(os.path.exists(self.root + '/missing'))

    def test_symlink_and_traversal_rejected(self):
        os.symlink('/etc', self.root + '/link')
        for relative in ['../etc', '/etc', 'link', 'a//b']:
            self.spec['mounts'][0]['relativePath'] = relative
            with self.assertRaises(ValueError):
                self.valid()

    def test_sensitive_parent_and_container_overlaps_rejected(self):
        for path in ['/var', '/var/lib', '/var/lib/docker', '/etc', '/home/app', '/']:
            with self.assertRaises(ValueError):
                s.canonical(path)
        self.spec['mounts'].append(dict(self.spec['mounts'][0], containerPath='/app/uploads/child'))
        with self.assertRaisesRegex(ValueError, 'overlapping'):
            self.valid()

    def test_application_writable_source_parent_cannot_be_replaced(self):
        os.mkdir(self.root + '/nested')
        self.spec['mounts'][0]['relativePath'] = 'nested'
        with self.assertRaisesRegex(ValueError, 'unsafe root-owned state ancestry'):
            self.valid()
        os.chown(self.root, 0, 0)
        os.chown(self.root + '/nested', 10001, 10001)
        self.assertTrue(self.valid())

    def test_immutable_spec_does_not_overwrite(self):
        path = s.spec_path(self.spec)
        s.write_json(path, self.spec, True)
        s.write_json(path, self.spec, True)
        changed = copy.deepcopy(self.spec)
        changed['mounts'][0]['readOnly'] = True
        with self.assertRaisesRegex(ValueError, 'immutable'):
            s.write_json(path, changed, True)
        self.assertEqual(s.read_json(path), self.spec)

    def test_nonroot_numeric_identity_required(self):
        for user in ['root', '0:10001', '10001:0', '10001']:
            self.spec['containerUser'] = user
            with self.assertRaisesRegex(ValueError, 'UID:GID'):
                self.valid()

    def test_state_symlink_not_read(self):
        os.symlink('/etc/passwd', self.state + '/evil.json')
        with self.assertRaises(OSError):
            s.read_json(self.state + '/evil.json')

    def test_bounded_stall_returns_without_local_fallback(self):
        import time
        before = time.monotonic()
        with patch.object(s, 'TIMEOUT', -1.8):
            with self.assertRaisesRegex(ValueError, 'timed out'):
                s.bounded(lambda: time.sleep(10))
        self.assertLess(time.monotonic() - before, 2)

    def test_container_exact_mount_and_restart_contract(self):
        path = s.spec_path(self.spec)
        s.write_json(path, self.spec, True)
        row = dict(Id='fixed-id', Config=dict(User='10001:10001', Labels={s.LABEL:path, 'com.portfolio.deploy.project':'2'}),
                   HostConfig=dict(RestartPolicy=dict(Name='no')),
                   Mounts=[dict(Type='bind', Source=self.root, Destination='/app/uploads', RW=True)])
        with patch.object(s, 'inspect_container', return_value=row):
            self.assertEqual(s.container_spec('deploy-project-2')[1], self.spec)
            row['HostConfig']['RestartPolicy']['Name'] = 'always'
            with self.assertRaisesRegex(ValueError, 'restart'):
                s.container_spec('deploy-project-2')
            row['HostConfig']['RestartPolicy']['Name'] = 'no'
            row['Mounts'][0]['Source'] = '/etc'
            with self.assertRaisesRegex(ValueError, 'bind mounts differ'):
                s.container_spec('deploy-project-2')

    def test_desired_manual_stop_records_false_original_identity(self):
        row = dict(Id='fixed-id')
        with patch.object(s, 'container_spec', return_value=(row, self.spec)):
            s.desired('deploy-project-2', True)
            s.desired('deploy-project-2', False)
        state = s.read_json(self.state + '/desired/deploy-project-2.json')
        self.assertFalse(state['desired'])
        self.assertEqual(state['containerId'], 'fixed-id')

    def test_preserved_container_name_accepts_original_maximum_length(self):
        with patch.object(s, 'command', return_value='[{"Id":"old"}]'):
            self.assertEqual(s.inspect_container('a' * 100 + '.deploy-previous')['Id'], 'old')
            with self.assertRaises(ValueError):
                s.inspect_container('a' * 101 + '.deploy-previous')

    def test_boot_start_only_current_desired_generation_after_validation(self):
        row = dict(Id='fixed-id', State=dict(Running=False))
        with patch.object(s, 'container_spec', return_value=(row, self.spec)), patch.object(s, 'identity', self.live_identity):
            s.desired('deploy-project-2', True)
            self.assertTrue(s.boot()['ok'])
            self.assertEqual(self.started, ['deploy-project-2'])
            self.started.clear()
            row['Id'] = 'unexpected-id'
            self.assertFalse(s.boot()['ok'])
            self.assertEqual(self.started, [])

    def test_boot_missing_nas_or_manual_stop_never_starts(self):
        row = dict(Id='fixed-id', State=dict(Running=False))
        with patch.object(s, 'container_spec', return_value=(row, self.spec)), patch.object(s, 'identity', self.live_identity):
            s.desired('deploy-project-2', True)
            self.observed['mountSource'] = 'different:/export'
            self.assertFalse(s.boot()['ok'])
            self.assertEqual(self.started, [])
            s.desired('deploy-project-2', False)
            self.assertTrue(s.boot()['ok'])
            self.assertEqual(self.started, [])

    def test_manual_stop_committed_between_read_and_lock_wins(self):
        row = dict(Id='fixed-id', State=dict(Running=False))
        with patch.object(s, 'container_spec', return_value=(row, self.spec)), patch.object(s, 'identity', self.live_identity):
            s.desired('deploy-project-2', True)
            real_flock = s.fcntl.flock
            def stopped_before_acquire(fd, flags):
                s.desired('deploy-project-2', False)
                return real_flock(fd, flags)
            with patch.object(s.fcntl, 'flock', side_effect=stopped_before_acquire):
                self.assertTrue(s.boot()['ok'])
            self.assertEqual(self.started, [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
