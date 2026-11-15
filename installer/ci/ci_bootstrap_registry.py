#!/usr/bin/env python3
"""Small registry contracts used by the opt-in, signed offline bootstrap."""
from __future__ import annotations
import argparse
import json
import re
import sys
import urllib.parse
from pathlib import Path


def immutable_policy(value, name):
    return (value.get('name') == name and value.get('format') == 'docker'
            and value.get('type') == 'hosted' and value.get('online') is True
            and value.get('storage', {}).get('writePolicy') == 'ALLOW_ONCE'
            and value.get('storage', {}).get('strictContentTypeValidation') is True
            and value.get('storage', {}).get('latestPolicy') is False)


def manifest_reference(value, repository, config):
    if not isinstance(value, dict):
        raise ValueError('Single-platform manifest required')
    digest = value.get('Descriptor', {}).get('digest', '')
    manifest = value.get('SchemaV2Manifest', {})
    if (not re.fullmatch(r'sha256:[a-f0-9]{64}', digest)
            or manifest.get('config', {}).get('digest') != config):
        raise ValueError('Remote manifest/config identity mismatch')
    return repository + '@' + digest


def direct_connector(value, nexus_url, registry, allow_http):
    """No inference from DNS aliases/proxies: require the exact direct connector."""
    origin = urllib.parse.urlsplit(nexus_url)
    target = urllib.parse.urlsplit('//' + registry)
    if (not origin.hostname or not target.hostname or target.username or target.password
            or target.path or target.query or target.fragment
            or origin.hostname.lower() != target.hostname.lower()):
        return False
    port = value.get('docker', {}).get('httpPort' if allow_http else 'httpsPort')
    expected = target.port or (80 if allow_http else 443)
    return isinstance(port, int) and not isinstance(port, bool) and port == expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    policy = sub.add_parser('policy')
    policy.add_argument('--nexus-url', required=True)
    policy.add_argument('--repository', required=True)
    policy.add_argument('--registry', required=True)
    policy.add_argument('--auth-file', type=Path, required=True)
    policy.add_argument('--allow-http', action='store_true')
    manifest = sub.add_parser('manifest')
    manifest.add_argument('--file', type=Path, required=True)
    manifest.add_argument('--repository', required=True)
    manifest.add_argument('--config', required=True)
    secret = sub.add_parser('credential')
    secret.add_argument('--auth-file', type=Path, required=True)
    secret.add_argument('--field', choices=['username', 'password'], required=True)
    args = parser.parse_args()
    # The wrapper pins the utility config and signed bundle. Mounts cannot supply
    # importer modules: use only the immutable utility image's isolated tools.
    if not sys.flags.isolated:
        raise ValueError('Python isolated mode required')
    sys.path.insert(0, '/opt/nexus-seed')
    from nexus_seed_import import Nexus, credentials
    if args.operation == 'credential':
        user, password = credentials(args.auth_file)
        # Password mode is used only in a direct password-stdin pipe, never logs.
        sys.stdout.write(user if args.field == 'username' else password)
    elif args.operation == 'manifest':
        print(manifest_reference(json.loads(args.file.read_text()), args.repository, args.config))
    else:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.repository):
            raise ValueError('Unsafe repository name')
        nexus = Nexus(args.nexus_url, credentials(args.auth_file), args.allow_http)
        response = nexus.request('GET', '/service/rest/v1/repositories/docker/hosted/' + args.repository)
        if response is None:
            raise ValueError('Docker hosted repository missing')
        with response:
            raw = response.read(1024 * 1024 + 1)
        value = json.loads(raw)
        if (len(raw) > 1024 * 1024 or not immutable_policy(value, args.repository)
                or not direct_connector(value, args.nexus_url, args.registry, args.allow_http)):
            raise ValueError('Disable redeploy and disable latest-tag override required')
        print('IMMUTABLE_DOCKER_HOSTED_POLICY_PASS')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Never emit passwords, credential JSON, server bodies or auth headers.
        print('offline-ci-bootstrap: local input or remote contract failed', file=sys.stderr)
        sys.exit(1)
