"""Pure mocked DNF audit/control flow; does not prove actual Rocky solver compatibility."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

helper = Path(__file__).resolve().parents[1] / 'lib' / 'target-curl-variants.py'
spec = importlib.util.spec_from_file_location('variants', str(helper))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def package(name, version='1.0', release='2', repo=module.REPO, path=None):
    value = types.SimpleNamespace(name=name, epoch='0', version=version,
                                  release=release, arch='x86_64', reponame=repo)
    value.localPkg = lambda: str(path)
    return value


def compare(left, right):
    return (left > right) - (left < right)


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.lock = {name: ('0:1.0-2', 'x86_64', '0' * 64, name + '.rpm')
                     for name in ('curl', 'libcurl', 'systemd')}
        self.installed = [package('curl-minimal', release='1'),
                          package('libcurl-minimal', release='1')]
        self.incoming = [package('curl'), package('libcurl')]

    def audit(self, incoming=None, outgoing=None, installed=None):
        transaction = types.SimpleNamespace(install_set=self.incoming if incoming is None else incoming,
                                           remove_set=self.installed if outgoing is None else outgoing)
        return module.audit_transaction(transaction, self.installed if installed is None else installed,
                                        self.lock, compare)

    def test_minimal_pair_and_same_name_upgrade(self):
        self.audit()
        old = package('systemd', release='1', repo='@System')
        self.audit(self.incoming + [package('systemd')], self.installed + [old], self.installed + [old])

    def test_only_libcurl_minimal_with_already_full_curl(self):
        minimal = package('libcurl-minimal', release='1')
        self.audit([package('libcurl')], [minimal], [minimal, package('curl', repo='@System')])

    def test_unrelated_erasure_forbidden(self):
        for name in ('dnf', 'rpm', 'systemd'):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                self.audit(outgoing=self.installed + [package(name)])

    def test_downgrade_arch_repo_and_missing_pair_forbidden(self):
        with self.assertRaises(RuntimeError):
            self.audit(installed=[package('curl-minimal', release='3'), self.installed[1]])
        changed = package('curl'); changed.arch = 'i686'
        for incoming in ([changed, self.incoming[1]], [package('curl', repo='external'), self.incoming[1]], [self.incoming[0]]):
            with self.subTest(incoming=incoming), self.assertRaises(RuntimeError):
                self.audit(incoming=incoming)
        with self.assertRaises(RuntimeError):
            self.audit(outgoing=[self.installed[0]])
        with self.assertRaises(RuntimeError):
            self.audit(installed=[package('curl'), package('libcurl')], outgoing=[])


class ControlFlowTests(unittest.TestCase):
    def setUp(self):
        auth = patch.dict(os.environ, {'OFFLINE_VERIFY_AUTHENTICITY': '1'})
        auth.start()
        self.addCleanup(auth.stop)
        self.temp = tempfile.TemporaryDirectory(prefix='curl-variants-mock-', dir=os.environ.get('TARGET_CURL_TEST_STAGING'))
        self.root = Path(self.temp.name)
        rows = []
        self.incoming = []
        for name in ('curl', 'libcurl'):
            path = self.root / (name + '.rpm')
            path.write_bytes(name.encode())
            rows.append('|'.join([name, '0:1.0-2', 'x86_64', hashlib.sha256(path.read_bytes()).hexdigest(), path.name]))
            self.incoming.append(package(name, path=path))
        (self.root / 'PACKAGES.lock').write_text('\n'.join(rows) + '\n')
        self.installed = [package('curl-minimal', release='1'), package('libcurl-minimal', release='1')]
        self.applies = 0; self.solve_failure = False; self.signature_failure = False; self.callback_failure = False; self.global_trigger_failure = False
        self.extra_remove = None
        self.default_persist_accesses = 0
        owner = self

        class Base:
            def __init__(self):
                self.conf = types.SimpleNamespace(substitutions={})
                self.repos = types.SimpleNamespace(add_new_repo=lambda *a, **kw: types.SimpleNamespace())
                self.sack = types.SimpleNamespace(query=lambda: types.SimpleNamespace(installed=lambda: owner.installed))
                self.transaction = types.SimpleNamespace(install_set=owner.incoming,
                                                        remove_set=owner.installed + ([owner.extra_remove] if owner.extra_remove else []))
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def fill_sack(self):
                if '--plan-only' in sys.argv:
                    if getattr(self.conf, 'persistdir', '/var/lib/dnf') == '/var/lib/dnf':
                        owner.default_persist_accesses += 1
                    owner.assertEqual(self.conf.persistdir, str(Path(self.conf.cachedir) / 'persist'))
                    owner.assertEqual(self.conf.logdir, str(Path(self.conf.cachedir) / 'logs'))
                    owner.assertFalse(self.conf.history_record)
            def remove(self, spec): owner.assertIn('minimal-', spec)
            def install(self, spec, reponame, strict):
                owner.assertEqual(reponame, module.REPO); owner.assertTrue(strict)
            def resolve(self, allow_erasing):
                owner.assertFalse(allow_erasing)
                if owner.solve_failure: raise RuntimeError('Mock solve failure')
            def package_signature_check(self, pkg): return (2, 'mock signature') if owner.signature_failure else (0, '')
            def do_transaction(self, display):
                owner.applies += 1
                if owner.callback_failure: display.error('Mock scriptlet failure')
                if owner.global_trigger_failure:
                    display.scriptout(b'warning: %transfiletriggerin(systemd) scriptlet fai')
                    display.scriptout(b'led, exit status 1\n')

        self.dnf = types.ModuleType('dnf'); self.dnf.Base = Base
        self.callback = types.ModuleType('dnf.callback'); self.callback.TransactionProgress = object
        self.dnf.callback = self.callback
        self.rpm = types.ModuleType('rpm'); self.rpm.labelCompare = lambda a, b: compare(':'.join(a), ':'.join(b))

    def tearDown(self): self.temp.cleanup()

    def run_main(self, mode='--apply'):
        def post_query(args):
            name = args[-1]
            self.assertIn(name, ('curl', 'libcurl'))
            return b'0:1.0-2|x86_64'
        stream = io.StringIO()
        temp_factory = tempfile.TemporaryDirectory
        def own_temp(**kwargs):
            return temp_factory(dir=str(self.root), **kwargs)
        with patch.dict(sys.modules, {'dnf': self.dnf, 'dnf.callback': self.callback, 'rpm': self.rpm}), \
                patch.object(module, 'Path', side_effect=lambda value: self.root if value == '/mock/repo' else Path(value)), \
                patch.object(module.tempfile, 'TemporaryDirectory', side_effect=own_temp), \
                patch.object(module.os, 'geteuid', return_value=0, create=True), \
                patch.object(module.platform, 'machine', return_value='x86_64'), \
                patch.object(sys, 'argv', ['helper', '--repository', '/mock/repo', mode]), \
                patch.object(module.subprocess, 'check_output', side_effect=post_query), \
                patch.object(module.subprocess, 'call', return_value=1), contextlib.redirect_stdout(stream):
            module.main()
        return stream.getvalue()

    def test_safe_plan_no_apply_and_safe_apply(self):
        plan = self.run_main('--plan-only')
        self.assertEqual(self.applies, 0); self.assertEqual(json.loads(plan)['mode'], 'plan-only')
        self.assertEqual(self.default_persist_accesses, 0)
        result = self.run_main()
        self.assertEqual(self.applies, 1); self.assertIn('TARGET_CURL_VARIANTS_LOCKED_TRANSACTION_PASS', result)

    def test_solver_signature_hash_lock_and_extra_removal_apply_zero(self):
        self.solve_failure = True
        with self.assertRaises(RuntimeError): self.run_main()
        self.solve_failure = False; self.signature_failure = True
        with self.assertRaises(RuntimeError): self.run_main()
        self.signature_failure = False
        for name in ('dnf', 'rpm', 'systemd'):
            self.extra_remove = package(name)
            with self.subTest(name=name), self.assertRaises(RuntimeError): self.run_main()
        self.extra_remove = None
        (self.root / 'curl.rpm').write_bytes(b'tampered')
        with self.assertRaises(RuntimeError): self.run_main()
        (self.root / 'PACKAGES.lock').write_text((self.root / 'PACKAGES.lock').read_text() + 'bad\n')
        with self.assertRaises(RuntimeError): self.run_main()
        self.assertEqual(self.applies, 0)

    def test_default_mode_ignores_publisher_signature_and_reference_sha(self):
        self.signature_failure = True
        (self.root / 'curl.rpm').write_bytes(b'changed-but-readable-fixture')
        with patch.dict(os.environ, {'OFFLINE_VERIFY_AUTHENTICITY': '0'}):
            result = self.run_main()
        self.assertEqual(self.applies, 1)
        self.assertIn('TARGET_CURL_VARIANTS_LOCKED_TRANSACTION_PASS', result)

    def test_callback_error_prevents_false_completion(self):
        self.callback_failure = True
        with self.assertRaises(RuntimeError): self.run_main()
        self.assertEqual(self.applies, 1)

    def test_global_trigger_output_error_with_dnf_success_prevents_completion(self):
        self.global_trigger_failure = True
        with self.assertRaises(RuntimeError): self.run_main()
        self.assertEqual(self.applies, 1)

    def test_plan_missing_existing_signature_key_fails_without_apply(self):
        self.signature_failure = True
        with self.assertRaises(RuntimeError): self.run_main('--plan-only')
        self.assertEqual(self.applies, 0)
        self.assertEqual(self.default_persist_accesses, 0)


if __name__ == '__main__':
    unittest.main()
