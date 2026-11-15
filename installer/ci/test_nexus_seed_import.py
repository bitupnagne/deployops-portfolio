import hashlib
import contextlib
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import maven_seed
import nexus_seed_import as importer


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.seed = Path(self.temp.name) / 'seed'
        self.relative = 'org/example/demo/1.0/demo-1.0.jar'
        self.put(self.seed / 'repository' / self.relative, b'jar fixture')
        self.put(self.seed / 'sources/demo/pom.xml', b'<project/>')
        self.lock()
        self.remote, self.calls = {}, []
        self.redirect = False
        self.corrupt_put = False
        self.policy = 'ALLOW_ONCE'
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                fixture.calls.append(('GET', self.path))
                if fixture.redirect:
                    self.send_response(302)
                    self.send_header('Location', f'http://127.0.0.1:{self.server.server_port}/leak')
                    self.end_headers()
                    return
                if self.path.startswith('/service/'):
                    data = json.dumps({'name': 'maven-offline-seed', 'format': 'maven2', 'type': 'hosted', 'online': True,
                                       'storage': {'writePolicy': fixture.policy, 'strictContentTypeValidation': True},
                                       'maven': {'versionPolicy': 'RELEASE', 'layoutPolicy': 'STRICT'}}).encode()
                elif self.path in fixture.remote:
                    data = fixture.remote[self.path]
                else:
                    self.send_response(404)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_PUT(self):
                fixture.calls.append(('PUT', self.path))
                if self.path in fixture.remote or self.headers.get('If-None-Match') != '*':
                    self.send_response(409)
                    self.end_headers()
                    return
                data = self.rfile.read(int(self.headers['Content-Length']))
                fixture.remote[self.path] = b'corrupt' if fixture.corrupt_put else data
                self.send_response(201)
                self.end_headers()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.client = importer.Nexus(self.url, ('fixture-user', 'fixture-password'), allow_http=True, timeout=2)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def put(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def lock(self):
        files = [maven_seed.record(self.seed, p.relative_to(self.seed).as_posix()) for p in sorted(self.seed.rglob('*'))
                 if p.is_file() and p.name != 'seed.lock.json']
        value = {'schema': 1, 'identity': {'fixture': 'not-real-build'}, 'files': files,
                 'projects': [{'name': 'demo', 'pom_sha256': maven_seed.digest(self.seed / 'sources/demo/pom.xml'), 'files': ['pom.xml']}]}
        self.put(self.seed / 'seed.lock.json', json.dumps(value).encode())
        self.pin = maven_seed.digest(self.seed / 'seed.lock.json')

    def test_dry_run_then_upload_then_identical_skip(self):
        dry = importer.import_seed(self.seed, self.pin, self.client)
        self.assertEqual('dry-run', dry['mode'])
        self.assertFalse(any(method == 'PUT' for method, _ in self.calls))
        applied = importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual('uploaded-and-verified', applied['artifacts'][0]['action'])
        self.calls.clear()
        again = importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual('same-bytes-skip', again['artifacts'][0]['action'])
        self.assertFalse(any(method == 'PUT' for method, _ in self.calls))

    def test_remote_conflict_prevents_all_writes(self):
        other = 'org/example/z/1.0/z-1.0.jar'
        self.put(self.seed / 'repository' / other, b'other fixture')
        self.lock()
        self.remote[self.client.artifact_path(other)] = b'different'
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertFalse(any(method == 'PUT' for method, _ in self.calls))

    def test_entire_seed_tamper_and_pin_fail_before_http(self):
        self.put(self.seed / 'sources/demo/pom.xml', b'tampered source')
        with self.assertRaises(maven_seed.SeedError):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual([], self.calls)
        self.lock()
        with self.assertRaises(maven_seed.SeedError):
            importer.import_seed(self.seed, '0' * 64, self.client, apply=True)
        self.assertEqual([], self.calls)

    def test_snapshot_fails_before_http(self):
        self.put(self.seed / 'repository/org/example/local/1.0-SNAPSHOT/local-1.0-SNAPSHOT.pom', b'<project/>')
        self.lock()
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual([], self.calls)

    def test_explicit_snapshot_omission_includes_metadata_and_checksums(self):
        snapshot = 'org/example/local/1.0-SNAPSHOT/local-1.0-SNAPSHOT.pom'
        self.put(self.seed / 'repository' / snapshot, b'<project/>')
        self.put(self.seed / 'repository' / (snapshot + '.sha1'), b'not-used-snapshot-checksum')
        metadata = 'org/example/local/1.0-SNAPSHOT/maven-metadata-local.xml'
        self.put(self.seed / 'repository' / metadata, b'<metadata/>')
        self.lock()
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual([], self.calls)
        result = importer.import_seed(self.seed, self.pin, self.client, apply=True, omit_snapshots=True)
        self.assertTrue(result['passed'])
        self.assertEqual({snapshot, snapshot + '.sha1', metadata}, {row['path'] for row in result['omitted']})
        self.assertTrue(all('--omit-snapshots' in row['reason'] for row in result['omitted']))
        self.assertIn('not-attested', result['build_completeness'])
        self.assertFalse(any('SNAPSHOT' in path for _, path in self.calls))
        self.assertEqual(3, len(self.remote))

    def test_sidecar_validated_omitted_and_metadata_reported(self):
        checksum = hashlib.sha1(b'jar fixture').hexdigest().encode()
        self.put(self.seed / 'repository' / (self.relative + '.sha1'), checksum)
        self.put(self.seed / 'repository/org/example/demo/maven-metadata-local.xml', b'<metadata/>')
        self.lock()
        result = importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual(2, len(result['omitted']))
        self.assertEqual(3, len(self.remote))
        self.put(self.seed / 'repository' / (self.relative + '.sha1'), b'0' * 40)
        self.lock()
        self.calls.clear()
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual([], self.calls)

    def test_redirect_never_followed_and_proxy_ignored(self):
        self.redirect = True
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client)
        self.assertEqual(1, len(self.calls))
        self.assertNotEqual('/leak', self.calls[0][1])
        self.redirect = False
        with patch.dict(os.environ, {'http_proxy': 'http://127.0.0.1:1', 'HTTP_PROXY': 'http://127.0.0.1:1', 'no_proxy': ''}):
            client = importer.Nexus(self.url, ('user', 'password'), allow_http=True)
            self.assertTrue(importer.import_seed(self.seed, self.pin, client)['passed'])

    def test_contract_drift_and_post_upload_corruption_fail(self):
        self.policy = 'ALLOW'
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertFalse(any(method == 'PUT' for method, _ in self.calls))
        self.policy = 'ALLOW_ONCE'
        self.corrupt_put = True
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)

    def test_https_and_protected_auth_file(self):
        with self.assertRaises(importer.ImportErrorSafe):
            importer.Nexus(self.url, ('user', 'password'))
        path = Path(self.temp.name) / 'auth.json'
        self.put(path, b'{"username":"user","password":"password"}')
        if os.name == 'nt':
            with self.assertRaisesRegex(importer.ImportErrorSafe, 'use environment variables on Windows'):
                importer.credentials(path)
            return
        path.chmod(0o644)
        with self.assertRaises(importer.ImportErrorSafe):
            importer.credentials(path)
        path.chmod(0o600)
        self.assertEqual(('user', 'password'), importer.credentials(path))

    def test_final_pin_recheck_detects_mutation_during_upload(self):
        original = self.client.request

        def mutate_after_put(method, path, *args):
            response = original(method, path, *args)
            if method == 'PUT':
                lock = self.seed / 'seed.lock.json'
                lock.write_bytes(lock.read_bytes() + b' ')
            return response

        with patch.object(self.client, 'request', side_effect=mutate_after_put):
            with self.assertRaises(maven_seed.SeedError):
                importer.import_seed(self.seed, self.pin, self.client, apply=True)

    def test_unknown_credential_argument_is_not_echoed(self):
        stderr = io.StringIO()
        with patch('sys.argv', ['nexus_seed_import.py', '--password', 'do-not-echo-secret']), \
                contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            importer.main()
        self.assertNotIn('do-not-echo-secret', stderr.getvalue())

    def test_existing_primary_missing_checksums_dryrun_then_apply(self):
        self.remote[self.client.artifact_path(self.relative)] = b'jar fixture'
        result = importer.import_seed(self.seed, self.pin, self.client)
        self.assertEqual(['same-bytes-skip', 'missing', 'missing'], [r['action'] for r in result['artifacts']])
        self.assertFalse(any(method == 'PUT' for method, _ in self.calls))
        result = importer.import_seed(self.seed, self.pin, self.client, apply=True)
        writes = [path for method, path in self.calls if method == 'PUT']
        self.assertEqual(2, len(writes))
        self.assertTrue(all(path.endswith(('.sha1', '.sha256')) for path in writes))
        self.assertEqual(hashlib.sha1(b'jar fixture').hexdigest().encode() + b'\n', self.remote[self.client.artifact_path(self.relative + '.sha1')])
        self.assertEqual(hashlib.sha256(b'jar fixture').hexdigest().encode() + b'\n', self.remote[self.client.artifact_path(self.relative + '.sha256')])
        self.assertEqual(self.relative, result['artifacts'][1]['derived_from_locked_artifact'])

    def test_checksum_semantic_match_and_conflict_without_overwrite(self):
        sha1_path = self.client.artifact_path(self.relative + '.sha1')
        self.remote[sha1_path] = b' \r\n' + hashlib.sha1(b'jar fixture').hexdigest().upper().encode() + b'\r\n'
        result = importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertEqual('same-checksum-skip', result['artifacts'][1]['action'])
        self.assertFalse(any(method == 'PUT' and path == sha1_path for method, path in self.calls))
        self.remote[sha1_path] = b'0' * 40
        self.calls.clear()
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        self.assertFalse(any(method == 'PUT' for method, _ in self.calls))

    def test_uploaded_checksum_corruption_fails_get_verification(self):
        self.remote[self.client.artifact_path(self.relative)] = b'jar fixture'
        self.corrupt_put = True
        with self.assertRaises(importer.ImportErrorSafe):
            importer.import_seed(self.seed, self.pin, self.client, apply=True)
        writes = [path for method, path in self.calls if method == 'PUT']
        self.assertEqual([self.client.artifact_path(self.relative + '.sha1')], writes)


if __name__ == '__main__':
    unittest.main()
