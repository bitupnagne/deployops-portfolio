#!/usr/bin/env python3
"""Import a pinned Maven seed into the immutable Nexus maven-offline-seed repository."""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

from maven_seed import SeedError, checked_file, digest, safe_path, verify_seed


class ImportErrorSafe(Exception):
    pass


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        super().error('invalid command-line options; credentials must use environment or a protected file')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ImportErrorSafe('HTTP redirect refused; credentials were not forwarded')


def credentials(auth_file: Path | None) -> tuple[str, str]:
    if auth_file is not None:
        if os.name == 'nt':
            raise ImportErrorSafe('Credential file must be a regular POSIX 0600 file; use environment variables on Windows')
        fd = os.open(auth_file, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'r', encoding='utf-8') as stream:
            metadata = os.fstat(stream.fileno())
            if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_size > 65536:
                raise ImportErrorSafe('Credential file must be a regular POSIX 0600 file within 64 KiB')
            value = json.load(stream)
        if set(value) != {'username', 'password'}:
            raise ImportErrorSafe('Credential JSON requires exactly username and password fields')
        user, password = value['username'], value['password']
    else:
        user, password = os.environ.get('NEXUS_MAVEN_USER', ''), os.environ.get('NEXUS_MAVEN_PASSWORD', '')
    if not isinstance(user, str) or not isinstance(password, str) or not user or not password:
        raise ImportErrorSafe('Nexus credentials are required via environment or protected file')
    if ':' in user or any(c in user + password for c in '\r\n\0'):
        raise ImportErrorSafe('Invalid Nexus credential encoding')
    return user, password


class Nexus:
    def __init__(self, url: str, auth: tuple[str, str], allow_http: bool = False, timeout: float = 30):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password \
                or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            raise ImportErrorSafe('Nexus URL must be an origin without credentials, path, query or fragment')
        if parsed.scheme == 'http' and not allow_http:
            raise ImportErrorSafe('HTTPS is required; --allow-http is an explicit isolated-network exception')
        if not 0 < timeout <= 300:
            raise ImportErrorSafe('Timeout must be between 0 and 300 seconds')
        self.base = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, '', '', ''))
        self.auth = 'Basic ' + base64.b64encode(f'{auth[0]}:{auth[1]}'.encode()).decode()
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method: str, path: str, data=None, size: int | None = None):
        headers = {'Authorization': self.auth, 'Accept-Encoding': 'identity'}
        if method == 'PUT':
            headers.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(size), 'If-None-Match': '*'})
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=self.timeout)
            if not 200 <= response.status < 300:
                response.close()
                raise ImportErrorSafe(f'Unexpected HTTP status during {method}')
            return response
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if method == 'GET' and status == 404:
                return None
            raise ImportErrorSafe(f'Nexus {method} failed: HTTP {status}') from None
        except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException):
            raise ImportErrorSafe(f'Nexus {method} network operation failed') from None

    def contract(self):
        response = self.request('GET', '/service/rest/v1/repositories/maven/hosted/maven-offline-seed')
        if response is None:
            raise ImportErrorSafe('maven-offline-seed repository is missing')
        with response:
            raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ImportErrorSafe('Repository contract response exceeds limit')
        try:
            value = json.loads(raw)
            valid = value['name'] == 'maven-offline-seed' and value['format'] == 'maven2' \
                and value['type'] == 'hosted' and value['online'] is True \
                and value['storage']['writePolicy'] == 'ALLOW_ONCE' \
                and value['storage']['strictContentTypeValidation'] is True \
                and value['maven']['versionPolicy'] == 'RELEASE' and value['maven']['layoutPolicy'] == 'STRICT'
        except (ValueError, KeyError, TypeError):
            valid = False
        if not valid:
            raise ImportErrorSafe('Nexus repository must be online Maven RELEASE/STRICT/ALLOW_ONCE hosted')

    @staticmethod
    def artifact_path(relative: str) -> str:
        safe_path(relative)
        return '/repository/maven-offline-seed/' + '/'.join(urllib.parse.quote(p, safe='') for p in relative.split('/'))

    def remote_digest(self, relative: str, expected_size: int) -> tuple[str, int] | None:
        response = self.request('GET', self.artifact_path(relative))
        if response is None:
            return None
        checksum, count = hashlib.sha256(), 0
        try:
            with response:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    count += len(chunk)
                    if count > expected_size:
                        raise ImportErrorSafe(f'Existing Nexus artifact exceeds locked size: {relative}')
                    checksum.update(chunk)
        except (OSError, TimeoutError, http.client.HTTPException):
            raise ImportErrorSafe('Nexus artifact read failed') from None
        return checksum.hexdigest(), count

    def matches(self, item: dict) -> bool | None:
        if 'checksum_hex' not in item:
            remote = self.remote_digest(item['relative'], item['size'])
            return None if remote is None else remote == (item['sha256'], item['size'])
        response = self.request('GET', self.artifact_path(item['relative']))
        if response is None:
            return None
        try:
            with response:
                raw = response.read(4097)
            # Nexus/Maven checksum files commonly differ only in trailing CRLF or hex case.
            return len(raw) <= 4096 and raw.decode('ascii').strip().lower() == item['checksum_hex']
        except (OSError, TimeoutError, UnicodeDecodeError, http.client.HTTPException):
            raise ImportErrorSafe('Nexus checksum read failed') from None


def select_artifacts(seed: Path, lock: dict, omit_snapshots: bool = False) -> tuple[list[dict], list[dict]]:
    artifacts, omitted = [], []
    repository_items = {item['path'][len('repository/'):]: item for item in lock['files'] if item['path'].startswith('repository/')}
    for relative, item in sorted(repository_items.items()):
        parts = safe_path(relative).parts
        if any(not re.fullmatch(r'[A-Za-z0-9._+\-]+', part) for part in parts):
            raise ImportErrorSafe(f'Maven STRICT path needs review: {relative}')
        if any('SNAPSHOT' in part.upper() for part in parts):
            if omit_snapshots:
                omitted.append({'path': relative, 'reason': 'explicit --omit-snapshots: SNAPSHOT artifact/metadata/sidecar excluded; dependency necessity not inferred'})
                continue
            raise ImportErrorSafe(f'SNAPSHOT cannot be imported into RELEASE repository: {relative}')
        if parts[-1].startswith('maven-metadata'):
            omitted.append({'path': relative, 'reason': 'local/repository metadata is not published; dynamic-version resolution is not attested'})
            continue
        match = re.search(r'\.(sha1|sha256|sha512|md5)$', relative)
        if match:
            base = relative[:match.start()]
            if base not in repository_items:
                raise ImportErrorSafe(f'Checksum sidecar has no locked artifact: {relative}')
            raw = checked_file(seed, item['path']).read_text(encoding='ascii').strip().split()
            h = hashlib.new(match.group(1))
            with checked_file(seed, repository_items[base]['path']).open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    h.update(chunk)
            if not raw or raw[0].lower() != h.hexdigest():
                raise ImportErrorSafe(f'Checksum sidecar differs from artifact: {relative}')
            omitted.append({'path': relative, 'reason': 'verified locally; SHA1/SHA256 regenerated from locked artifact; other checksum kinds not published'})
            continue
        artifacts.append({**item, 'relative': relative})
    if not artifacts:
        raise ImportErrorSafe('No publishable RELEASE artifacts in seed')
    generated = []
    for item in artifacts:
        checksum = hashlib.sha1()
        with checked_file(seed, item['path']).open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                checksum.update(chunk)
        for kind, hexadecimal in (('sha1', checksum.hexdigest()), ('sha256', item['sha256'])):
            payload = (hexadecimal + '\n').encode('ascii')
            generated.append({'path': item['path'], 'relative': item['relative'] + '.' + kind,
                              'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest(),
                              'checksum_hex': hexadecimal, 'payload': payload,
                              'derived_from': item['relative'], 'source_size': item['size'],
                              'source_sha256': item['sha256'], 'checksum_algorithm': kind})
    return artifacts + generated, omitted


def import_seed(seed: Path, pin: str, nexus: Nexus, apply: bool = False, omit_snapshots: bool = False) -> dict:
    # Nothing is written until the entire seed and every remote collision have been checked.
    lock = verify_seed(seed, pin)
    artifacts, omitted = select_artifacts(seed, lock, omit_snapshots)
    nexus.contract()
    result = {'mode': 'apply' if apply else 'dry-run', 'seed_lock_sha256': pin,
              'repository': 'maven-offline-seed', 'omit_snapshots': omit_snapshots,
              'build_completeness': 'not-attested: requires both projects to pass fresh-cache verification',
              'omitted': omitted, 'artifacts': [], 'passed': False}
    missing = []
    for item in artifacts:
        remote = nexus.matches(item)
        if remote is None:
            missing.append(item)
        elif not remote:
            raise ImportErrorSafe(f'Existing Nexus artifact differs; overwrite forbidden: {item["relative"]}')
        result['artifacts'].append({'path': item['relative'], 'sha256': item['sha256'], 'size': item['size'],
                                    **({'derived_from_locked_artifact': item['derived_from'],
                                        'source_sha256': item['source_sha256'],
                                        'checksum_algorithm': item['checksum_algorithm'],
                                        'comparison': 'normalized-hex; size/SHA describe canonical generated payload',
                                        'checksum_hex': item['checksum_hex']} if 'derived_from' in item else {}),
                                    'action': 'missing' if remote is None else ('same-checksum-skip' if 'checksum_hex' in item else 'same-bytes-skip')})
    verify_seed(seed, pin)
    if apply:
        for item in missing:
            # Recheck the immutable policy and race window; PUT also carries If-None-Match: *.
            nexus.contract()
            remote = nexus.matches(item)
            if remote is not None:
                if not remote:
                    raise ImportErrorSafe(f'Concurrent artifact differs; overwrite forbidden: {item["relative"]}')
                next(row for row in result['artifacts'] if row['path'] == item['relative'])['action'] = \
                    'same-checksum-skip' if 'checksum_hex' in item else 'same-bytes-skip'
                continue
            path = checked_file(seed, item['path'])
            if path.stat().st_size != item.get('source_size', item['size']) or digest(path) != item.get('source_sha256', item['sha256']):
                raise ImportErrorSafe('Seed changed before upload')
            if 'payload' in item:
                response = nexus.request('PUT', nexus.artifact_path(item['relative']), item['payload'], item['size'])
                response.close()
            else:
                with path.open('rb') as stream:
                    response = nexus.request('PUT', nexus.artifact_path(item['relative']), stream, item['size'])
                    response.close()
            if nexus.matches(item) is not True:
                raise ImportErrorSafe(f'Uploaded artifact did not verify: {item["relative"]}')
            next(row for row in result['artifacts'] if row['path'] == item['relative'])['action'] = 'uploaded-and-verified'
    verify_seed(seed, pin)
    result['passed'] = True
    return result


def main() -> int:
    parser = SafeParser(description=__doc__)
    parser.add_argument('--seed', type=Path, required=True)
    parser.add_argument('--lock-sha256', required=True)
    parser.add_argument('--nexus-url', required=True)
    parser.add_argument('--auth-file', type=Path)
    parser.add_argument('--allow-http', action='store_true')
    parser.add_argument('--timeout', type=float, default=30)
    parser.add_argument('--apply', action='store_true', help='Explicitly upload missing artifacts (default: read-only dry run)')
    parser.add_argument('--omit-snapshots', action='store_true', help='Explicitly exclude all SNAPSHOT paths and report them; does not attest build completeness')
    args = parser.parse_args()
    try:
        if not re.fullmatch(r'[0-9a-f]{64}', args.lock_sha256):
            raise ImportErrorSafe('An independently trusted seed-lock SHA-256 is required')
        nexus = Nexus(args.nexus_url, credentials(args.auth_file), args.allow_http, args.timeout)
        result = import_seed(args.seed.resolve(), args.lock_sha256, nexus, args.apply, args.omit_snapshots)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ImportErrorSafe, SeedError) as error:
        print(f'nexus-seed-import: FAIL: {error}', file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError):
        # Never expose credential file contents, server response bodies, or urllib request details.
        print('nexus-seed-import: FAIL: invalid local input or transport response', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
