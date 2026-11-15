#!/usr/bin/env python3
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import pwd
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'deploy-system/deployops-state.py'
SPEC = importlib.util.spec_from_file_location('deployops_state', str(SOURCE))
state = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(state)


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'Linux root metadata tests')
class StateTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='deploy-state-test-'))
        self.root.chmod(0o700)
        self.home = self.root / 'app'
        self.home.mkdir(mode=0o750)
        self.etc = self.root / 'etc/deploy-system'
        self.etc.mkdir(parents=True, mode=0o750)
        (self.etc / 'tls').mkdir(mode=0o750)
        (self.etc / 'tls/company.p12').write_bytes(b'private-p12')
        (self.etc / 'tls/company.p12').chmod(0o640)
        (self.etc / 'tls-health.json').write_text('{"schema":1}')
        (self.etc / 'tls-health.json').chmod(0o600)
        (self.home / 'data/agent-artifacts').mkdir(parents=True, mode=0o700)
        (self.home / 'data/agent-artifacts/artifact-1.bin').write_bytes(b'immutable-artifact')
        (self.home / 'data/agent-artifacts/artifact-1.bin').chmod(0o600)
        self.archive = self.root / 'state.tar.gz'

    def tearDown(self):
        shutil.rmtree(str(self.root))

    def capture(self):
        return state.snapshot(self.archive, self.home, self.etc)

    def test_stopped_service_allows_only_inactive_and_failed(self):
        for value in (b"inactive\n", b"failed\n"):
            result = state.subprocess.CompletedProcess([], 0, stdout=value)
            with self.subTest(state=value), patch.object(state.subprocess, "run", return_value=result):
                state.require_stopped_service()

    def test_service_transition_or_query_failure_blocks_state_operation(self):
        for code, value in ((0, b"active\n"), (0, b"activating\n"), (0, b"deactivating\n"),
                            (0, b"reloading\n"), (0, b"unknown\n"), (0, b""), (1, b"inactive\n")):
            result = state.subprocess.CompletedProcess([], code, stdout=value)
            with self.subTest(code=code, state=value), patch.object(state.subprocess, "run", return_value=result):
                with self.assertRaises(ValueError):
                    state.require_stopped_service()

    def test_capture_restore_includes_tls_health_and_agent_artifacts(self):
        result = self.capture()
        self.assertEqual({'tls', 'tls-health.json', 'agent-data', 'agent-artifacts'}, set(result['includes']))
        target_home = self.root / 'new-app'
        target_home.mkdir()
        target_etc = self.root / 'new-etc/deploy-system'
        target_etc.parent.mkdir()
        state.restore(self.archive, target_home, target_etc)
        self.assertEqual(b'private-p12', (target_etc / 'tls/company.p12').read_bytes())
        self.assertEqual(b'immutable-artifact', (target_home / 'data/agent-artifacts/artifact-1.bin').read_bytes())
        self.assertEqual(0o640, stat.S_IMODE((target_etc / 'tls/company.p12').stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((target_etc / 'tls-health.json').stat().st_mode))

    def test_legacy_tls_restore_under_private_umask_is_readable_by_service_group(self):
        account = pwd.getpwnam('nobody')
        os.chown(str(self.etc / 'tls'), 0, account.pw_gid)
        os.chown(str(self.etc / 'tls/company.p12'), 0, account.pw_gid)
        self.capture()  # Existing archive shape; no parent entry is required.
        self.root.chmod(0o755)
        target_etc = self.root / 'restored-etc'
        previous = os.umask(0o077)
        try:
            state.restore(self.archive, self.home, target_etc)
        finally:
            os.umask(previous)
        self.assertEqual((0, account.pw_gid, 0o750),
                         (target_etc.stat().st_uid, target_etc.stat().st_gid, stat.S_IMODE(target_etc.stat().st_mode)))
        def become_service():
            os.setgroups([])
            os.setgid(account.pw_gid)
            os.setuid(account.pw_uid)
        result = subprocess.run(['/bin/cat', str(target_etc / 'tls/company.p12')],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, preexec_fn=become_service)
        self.assertEqual(0, result.returncode)
        self.assertEqual(b'private-p12', result.stdout)

    def test_tls_restore_refuses_uncontrolled_parent_before_writing(self):
        self.capture()
        target_etc = self.root / 'unsafe-etc'
        target_etc.mkdir(mode=0o777)
        target_etc.chmod(0o777)
        with self.assertRaises(ValueError):
            state.restore(self.archive, self.home, target_etc)
        self.assertFalse((target_etc / 'tls').exists())
        self.assertEqual(0o777, stat.S_IMODE(target_etc.stat().st_mode))

    def test_snapshot_refuses_symbolic_links(self):
        (self.etc / 'tls/link.p12').symlink_to(self.etc / 'tls/company.p12')
        with self.assertRaises(ValueError):
            self.capture()

    def test_snapshot_does_not_overwrite_existing_backup(self):
        self.capture()
        original = self.archive.read_bytes()
        with self.assertRaises(ValueError):
            self.capture()
        self.assertEqual(original, self.archive.read_bytes())

    def test_restore_rejects_path_traversal_before_mutating(self):
        with tarfile.open(str(self.archive), 'w:gz') as archive:
            member = tarfile.TarInfo('tls/../../outside')
            member.size = 1
            member.mode = 0o600
            archive.addfile(member, io.BytesIO(b'x'))
        self.archive.chmod(0o600)
        with self.assertRaises(ValueError):
            state.restore(self.archive, self.home, self.etc)
        self.assertFalse((self.root / 'outside').exists())

    def test_restore_rejects_symlink_member(self):
        with tarfile.open(str(self.archive), 'w:gz') as archive:
            member = tarfile.TarInfo('tls/link')
            member.type = tarfile.SYMTYPE
            member.linkname = '/etc/shadow'
            archive.addfile(member)
        self.archive.chmod(0o600)
        with self.assertRaises(ValueError):
            state.restore(self.archive, self.home, self.etc)

    def test_restore_rejects_public_archive(self):
        self.capture()
        self.archive.chmod(0o644)
        with self.assertRaises(ValueError):
            state.restore(self.archive, self.home, self.etc)

    def test_restore_rejects_wrong_member_type(self):
        with tarfile.open(str(self.archive), 'w:gz') as archive:
            member = tarfile.TarInfo('tls-health.json')
            member.type = tarfile.DIRTYPE
            member.mode = 0o700
            archive.addfile(member)
        self.archive.chmod(0o600)
        with self.assertRaises(ValueError):
            state.restore(self.archive, self.home, self.etc)

    def test_restore_rejects_tls_nonroot_owner(self):
        with tarfile.open(str(self.archive), 'w:gz') as archive:
            member = tarfile.TarInfo('tls/private.p12')
            member.uid = 65534
            member.uname = 'nobody'
            member.mode = 0o600
            member.size = 1
            archive.addfile(member, io.BytesIO(b'x'))
        self.archive.chmod(0o600)
        with self.assertRaises(ValueError):
            state.restore(self.archive, self.home, self.etc)


if __name__ == '__main__':
    unittest.main()
