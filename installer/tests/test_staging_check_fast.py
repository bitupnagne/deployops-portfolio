"""Native root tests for equivalent GNU find checks and exact r6 adaptation."""
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('core', str(ROOT / 'installer/interactive_install.py'))
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)


class FastStagingTests(unittest.TestCase):
    def setUp(self):
        self.assertEqual(os.geteuid(), 0)
        self.temp = tempfile.TemporaryDirectory(prefix='staging-fast-', dir='/root')
        self.root = Path(self.temp.name)
        self.tree = self.root / 'package'
        self.tree.mkdir(mode=0o700)
        self.installer = self.tree / 'installer'
        self.installer.mkdir(mode=0o700)
        self.script = self.installer / 'install.sh'
        self.script.write_text('#!/bin/bash\n' + core.LEGACY_STAGING_CHECK + '\n# tail unchanged\n')
        self.script.chmod(0o700)

    def tearDown(self):
        self.temp.cleanup()

    def run_check(self, block, prelude=''):
        shell = 'set -uo pipefail\nerr() { printf "%s\\n" "$*" >&2; }\n' + prelude + block + '\nassert_trusted_staging_tree "$1" fixture\n'
        return subprocess.run(['bash', '-c', shell, 'check', str(self.tree)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def both(self, success):
        for block in (core.LEGACY_STAGING_CHECK, core.FAST_STAGING_CHECK):
            result = self.run_check(block)
            self.assertEqual(result.returncode == 0, success, result.stderr)

    def test_valid_tree_same_result(self):
        self.both(True)

    def test_group_writable_file_same_rejection(self):
        self.script.chmod(0o620)
        self.both(False)

    def test_world_writable_directory_same_rejection(self):
        self.installer.chmod(0o702)
        self.both(False)

    def test_nonroot_owner_same_rejection(self):
        os.chown(str(self.script), 65534, 0)
        self.both(False)

    def test_dangling_link_same_rejection(self):
        (self.tree / 'link').symlink_to('/does-not-exist')
        self.both(False)

    def test_unsafe_ancestor_same_rejection(self):
        self.root.chmod(0o720)
        self.both(False)

    def test_find_failure_is_not_success(self):
        result = self.run_check(core.FAST_STAGING_CHECK, 'find() { return 1; }\n')
        self.assertNotEqual(result.returncode, 0)

    def test_second_find_failure_is_not_success(self):
        result = self.run_check(core.FAST_STAGING_CHECK, 'find() { if [[ "$*" == *-xdev* ]]; then return 1; else command find "$@"; fi; }\n')
        self.assertNotEqual(result.returncode, 0)

    def test_stat_process_count_does_not_grow_with_file_count(self):
        for n in range(1200):
            (self.tree / ('file-' + str(n))).write_bytes(b'x')
        count = self.root / 'stat-count'
        prelude = 'stat() { printf x >> "' + str(count) + '"; command stat "$@"; }\n'
        result = self.run_check(core.FAST_STAGING_CHECK, prelude)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(len(count.read_bytes()), 20)

    def test_exact_patch_is_atomic_idempotent_and_preserves_mode_tail(self):
        before = self.script.read_bytes()
        inode = self.script.stat().st_ino
        self.assertEqual(core.improve_legacy_staging_check(self.tree), 'updated')
        self.assertNotEqual(inode, self.script.stat().st_ino)
        self.assertEqual(stat.S_IMODE(self.script.stat().st_mode), 0o700)
        self.assertEqual(self.script.read_bytes(), before.replace(core.LEGACY_STAGING_CHECK.encode(), core.FAST_STAGING_CHECK.encode(), 1))
        self.assertEqual(core.improve_legacy_staging_check(self.tree), 'current')
        self.assertEqual(subprocess.run(['bash', '-n', str(self.script)]).returncode, 0)

    def test_unknown_version_keeps_all_original_bytes(self):
        self.script.write_bytes(b'#!/bin/bash\n# custom installer\n')
        original = self.script.read_bytes()
        self.assertEqual(core.improve_legacy_staging_check(self.tree), 'unknown')
        self.assertEqual(self.script.read_bytes(), original)

    def test_publish_failure_preserves_original_and_removes_temporary(self):
        original = self.script.read_bytes()
        with patch.object(core.os, 'replace', side_effect=OSError('fixture')):
            with self.assertRaises(OSError):
                core.improve_legacy_staging_check(self.tree)
        self.assertEqual(self.script.read_bytes(), original)
        self.assertEqual(list(self.installer.glob('.staging-check-*')), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
