"""Input trust boundaries and isolated importer loading; no Docker or Nexus."""
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load('utility_context_tested', 'utility-context.py').prepare
check = load('utility_check_tested', 'utility-check.py')
BASE = 'python@sha256:' + 'a' * 64


class UtilityContextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('UTILITY_TEST_STAGING'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.context = self.root / 'context'
        self.pin = prepare(self.context, BASE)

    def test_valid_locked_context_and_plain_array_lock_is_rejected(self):
        check.verify_context(self.context, self.pin, BASE)
        raw = b'[]\n'
        (self.context / 'INPUTS.lock.json').write_bytes(raw)
        with self.assertRaises(ValueError):
            check.verify_context(self.context, hashlib.sha256(raw).hexdigest(), BASE)

    def test_mutable_base_cannot_be_prepared(self):
        with self.assertRaises(ValueError):
            prepare(self.root / 'mutable', 'python:3.12-slim-bookworm')

    def test_wrong_external_pin_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'pin mismatch'):
            check.verify_context(self.context, 'b' * 64, BASE)

    def test_importer_tampering_is_rejected_before_execution(self):
        with (self.context / 'nexus_seed_import.py').open('ab') as stream:
            stream.write(b'\nraise RuntimeError("must not execute")\n')
        with self.assertRaisesRegex(ValueError, 'Input bytes differ'):
            check.verify_context(self.context, self.pin, BASE)

    def test_unlocked_extra_file_and_missing_module_are_rejected(self):
        extra = self.context / 'unexpected.txt'
        extra.write_text('unexpected', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Unexpected or missing'):
            check.verify_context(self.context, self.pin, BASE)
        extra.unlink()
        (self.context / 'maven_seed.py').unlink()
        with self.assertRaisesRegex(ValueError, 'Unexpected or missing'):
            check.verify_context(self.context, self.pin, BASE)

    def test_base_argument_cannot_drift_from_locked_input(self):
        with self.assertRaisesRegex(ValueError, 'base identity mismatch'):
            check.verify_context(self.context, self.pin, 'python@sha256:' + 'b' * 64)

    def test_duplicate_json_key_is_rejected_even_with_its_pin(self):
        raw = b'{"schemaVersion":1,"schemaVersion":1,"files":[]}\n'
        (self.context / 'INPUTS.lock.json').write_bytes(raw)
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            check.verify_context(self.context, hashlib.sha256(raw).hexdigest(), BASE)

    def test_symlink_input_is_rejected(self):
        outside = self.root / 'outside.py'
        original = self.context / 'maven_seed.py'
        original.rename(outside)
        try:
            original.symlink_to(outside)
        except OSError as error:
            self.skipTest(f'Host cannot create symlinks: {error}')
        with self.assertRaisesRegex(ValueError, 'links and special'):
            check.verify_context(self.context, self.pin, BASE)

    def test_existing_output_is_not_overwritten(self):
        with self.assertRaisesRegex(ValueError, 'new directory'):
            prepare(self.context, BASE)
        check.verify_context(self.context, self.pin, BASE)

    def test_importer_help_ignores_cwd_and_pythonpath_module_injection(self):
        hostile = self.root / 'hostile'
        hostile.mkdir()
        (hostile / 'maven_seed.py').write_text('raise RuntimeError("injected module")\n', encoding='utf-8')
        env = dict(os.environ, PYTHONPATH=str(hostile))
        run = subprocess.run([sys.executable, '-I', '-B',
                              str(self.context / 'utility-entrypoint.py'), '--help'],
                             cwd=hostile, env=env, stdin=subprocess.DEVNULL,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, timeout=30, check=False)
        self.assertEqual(0, run.returncode)
        self.assertIn('--nexus-url', run.stdout)
        self.assertIn('--auth-file', run.stdout)


if __name__ == '__main__':
    unittest.main()
