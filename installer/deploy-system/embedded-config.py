#!/usr/bin/env python3
"""Read generated JSON-compatible YAML from the application JAR; never log secrets."""
import argparse
import json
import os
import re
import sys
import zipfile

ENTRY = 'BOOT-INF/classes/application-install.yml'
FIELDS = (
    'spring.datasource.url', 'spring.datasource.username', 'spring.datasource.password',
    'server.port', 'app.crypto.secret-key', 'app.crypto.key-id',
    'app.crypto.previous-keys', 'app.sso.shared-secret', 'app.bootstrap.admin-password',
    'app.nginx.upload-dir', 'app.bootstrap.image-transfer-mode')
ENV = {
    'SPRING_DATASOURCE_URL': FIELDS[0], 'SPRING_DATASOURCE_USERNAME': FIELDS[1],
    'SPRING_DATASOURCE_PASSWORD': FIELDS[2], 'SERVER_PORT': FIELDS[3],
    'APP_CRYPTO_SECRET_KEY': FIELDS[4], 'SECRET_ENCRYPTION_KEY': FIELDS[4],
    'APP_CRYPTO_KEY_ID': FIELDS[5], 'SECRET_ENCRYPTION_KEY_ID': FIELDS[5],
    'APP_CRYPTO_PREVIOUS_KEYS': FIELDS[6], 'SECRET_PREVIOUS_KEYS': FIELDS[6],
    'SSO_SHARED_SECRET': FIELDS[7], 'APP_SSO_SHARED_SECRET': FIELDS[7],
    'APP_BOOTSTRAP_ADMIN_PASSWORD': FIELDS[8], 'NGINX_UPLOAD_DIR': FIELDS[9],
    'APP_BOOTSTRAP_IMAGE_TRANSFER_MODE': FIELDS[10],
    'DEPLOY_BASE_URL': 'app.sso.base-url', 'SESSION_COOKIE_SECURE': 'server.servlet.session.cookie.secure',
    'DEPLOY_BOOT_RECOVERY_ENABLED': 'deploy.boot-recovery.enabled',
    'BOOTSTRAP_SAMPLE_DATA_ENABLED': 'app.bootstrap.sample-data-enabled',
}


def get(data, key, default=''):
    value = data
    for part in key.split('.'):
        if not isinstance(value, dict) or part not in value:
            return default
        value = value[part]
    return value


def put(data, key, value):
    parts = key.split('.')
    for part in parts[:-1]:
        data = data.setdefault(part, {})
    data[parts[-1]] = value


def validate(data):
    for key in FIELDS:
        value = get(data, key)
        valid_type = type(value) in (str, int) if key == 'server.port' else type(value) is str
        if not valid_type or any(c in str(value) for c in ('\0', '\n', '\r', '${')):
            raise ValueError('Unsupported scalar in embedded configuration: ' + key)
    for key in (FIELDS[0], FIELDS[1], FIELDS[2], FIELDS[4], FIELDS[7], FIELDS[8], FIELDS[9]):
        value = str(get(data, key))
        if not value or 'CHANGE_ME' in value:
            raise ValueError('Required configuration value is missing: ' + key)
    if not re.fullmatch(r'jdbc:mariadb://[^/:?]+:[0-9]+/[A-Za-z0-9_]+(?:\?.*)?', str(get(data, FIELDS[0]))):
        raise ValueError('Use an explicit MariaDB host, port and schema in spring.datasource.url')
    port = int(get(data, FIELDS[3]))
    if not 1 <= port <= 65535:
        raise ValueError('server.port is invalid')
    if get(data, 'deploy.configuration.embedded') is not True:
        raise ValueError('deploy.configuration.embedded must be true')
    return data


def read(path):
    if str(path).endswith('.jar'):
        with zipfile.ZipFile(path) as jar:
            if jar.namelist().count(ENTRY) != 1:
                raise ValueError('Build with -Pembedded-config; embedded configuration is missing or duplicated')
            raw = jar.read(ENTRY)
    else:
        with open(path, 'rb') as stream:
            raw = stream.read()
    try:
        return validate(json.loads(raw.decode('utf-8-sig')))
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError('Use the generated JSON-compatible YAML format (no extra Python YAML package is required)')


def legacy(stream):
    data = {}
    for raw in stream:
        line = raw.rstrip('\r\n')
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if sep and key in ENV:
            if value.startswith(('"', "'")) and value.endswith(value[0]):
                value = value[1:-1]
            put(data, ENV[key], value)
    put(data, 'deploy.configuration.embedded', True)
    put(data, 'app.crypto.key-id', get(data, 'app.crypto.key-id', 'primary'))
    put(data, 'app.crypto.previous-keys', get(data, 'app.crypto.previous-keys', ''))
    put(data, 'app.bootstrap.sample-data-enabled', False)
    return validate(data)


def same(current, candidate):
    # DB identity and key ring are not maintenance-patch inputs: changing them loses existing data access.
    for key in FIELDS[:3] + FIELDS[4:8] + (FIELDS[3], FIELDS[9]):
        if str(get(current, key)) != str(get(candidate, key)):
            raise ValueError('Configuration continuity mismatch: ' + key)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['validate', 'fields', 'migrate-env', 'compare', 'compare-env'])
    parser.add_argument('source', nargs='?')
    parser.add_argument('--candidate')
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.mode == 'migrate-env':
        if not args.output:
            raise ValueError('--output is required')
        if args.source:
            with open(args.source, encoding='utf-8-sig') as stream:
                data = legacy(stream)
        else:
            data = legacy(sys.stdin)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        fd = os.open(args.output, flags, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        print('Embedded YAML written (values withheld).')
    elif args.mode in ('compare', 'compare-env'):
        if not args.candidate:
            raise ValueError('--candidate is required')
        if args.mode == 'compare-env':
            with open(args.source, encoding='utf-8-sig') as stream:
                current = legacy(stream)
        else:
            current = read(args.source)
        same(current, read(args.candidate))
        print('Configuration continuity preserved (values withheld).')
    elif args.mode == 'fields':
        data = read(args.source)
        for field in FIELDS:
            sys.stdout.buffer.write(str(get(data, field)).encode('utf-8') + b'\0')
    else:
        read(args.source)
        print('Embedded configuration is valid (values withheld).')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, KeyError) as exc:
        print('embedded-config: ' + str(exc), file=sys.stderr)
        sys.exit(1)
