#!/usr/bin/python3
"""Root-owned bind-mount contracts. Compatible with Rocky 8 platform Python 3.6."""
import argparse
import fcntl
import hashlib
import json
import os
import re
import select
import signal
import stat
import subprocess
import sys
import tempfile
import time

ROOT = '/var/lib/deploy-system/storage'
RECOVERY_ROOT = '/var/lib/deploy-system/container-recovery'
LABEL = 'com.portfolio.deploy.mount-spec'
LIMIT = 65536
TIMEOUT = 12
NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$')
PATH = re.compile(r'^/[A-Za-z0-9_.+/-]+$')
DENIED = ('/etc', '/proc', '/sys', '/dev', '/run', '/boot', '/root', '/home',
          '/bin', '/sbin', '/usr', '/lib', '/lib64', '/var/run', '/var/lib/docker',
          '/var/lib/containerd', '/var/lib/deploy-system', '/var/lib/kubelet')


def refuse(message='storage contract refused'):
    raise ValueError(message)


def within(path, root):
    return path == root or path.startswith(root.rstrip('/') + '/')


def canonical(path, host=True):
    if not isinstance(path, str) or not PATH.fullmatch(path) or path == '/' or len(path) > 500:
        refuse('invalid absolute storage path')
    if any(p in ('', '.', '..') for p in path.split('/')[1:]):
        refuse('noncanonical storage path')
    if host and any(within(path, p) or within(p, path) for p in DENIED):
        refuse('sensitive host path is forbidden')
    return path


def ancestry(path, private=False):
    current = '/'
    for part in path.strip('/').split('/'):
        current = os.path.join(current, part)
        info = os.lstat(current)
        if not stat.S_ISDIR(info.st_mode):
            refuse('storage directory missing or symlink')
        if private and (info.st_uid != 0 or info.st_mode & 0o022):
            refuse('unsafe root-owned state ancestry')


def private_dir(path):
    if not os.path.exists(path):
        private_dir(os.path.dirname(path))
        os.mkdir(path, 0o700)
    ancestry(path, True)


def encode(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode('ascii')


def read_json(path):
    ancestry(os.path.dirname(path), True)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
            refuse('unsafe state file')
        payload = stream.read(LIMIT + 1)
    if len(payload) > LIMIT:
        refuse('storage metadata too large')
    return json.loads(payload.decode('utf-8'))


def write_json(path, value, immutable=False):
    private_dir(os.path.dirname(path))
    data = encode(value)
    if immutable and os.path.lexists(path):
        if encode(read_json(path)) != data:
            refuse('immutable storage specification differs')
        return
    fd, tmp = tempfile.mkstemp(prefix='.storage-', dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
        directory = os.open(os.path.dirname(path), os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def command(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            encoding='utf-8', timeout=TIMEOUT)
    if result.returncode:
        refuse('storage command failed: ' + args[0])
    return result.stdout.strip()


def identity(root, kind):
    canonical(root)
    ancestry(root)
    if kind not in ('LOCAL', 'NAS'):
        refuse('invalid storage kind')
    raw = json.loads(command(['findmnt', '--json', '--target', root, '--output', 'TARGET,SOURCE,FSTYPE']))
    rows = raw.get('filesystems', [])
    if len(rows) != 1:
        refuse('mount identity is ambiguous')
    row = rows[0]
    target, source, fs = row.get('target'), row.get('source'), row.get('fstype')
    if not all(isinstance(v, str) and v and not any(ord(c) < 32 for c in v) for v in (target, source, fs)):
        refuse('mount identity unavailable')
    if not within(root, target):
        refuse('mount target does not contain root')
    network = fs in ('nfs', 'nfs4', 'cifs', 'smb3')
    if (kind == 'NAS') != network:
        refuse('NAS/local filesystem kind mismatch')
    return dict(rootPath=root, mountTarget=target, mountSource=source, fsType=fs)


def positive(value, zero=False):
    if type(value) is not int or value < (0 if zero else 1):
        refuse('invalid storage identifier')
    return value


def validate_location(location, live=True):
    if not isinstance(location, dict) or not NAME.fullmatch(str(location.get('storageKey', ''))):
        refuse('invalid storage key')
    if type(location.get('enabled')) is not bool:
        refuse('enabled must be boolean')
    root = canonical(location.get('rootPath'))
    if location.get('kind') not in ('LOCAL', 'NAS'):
        refuse('invalid storage kind')
    if live and location['enabled']:
        observed = identity(root, location['kind'])
        if any(location.get(key) != observed[key] for key in observed):
            refuse('registered mount identity changed')


def validate_allowlist(value):
    if value.get('schemaVersion') != 1:
        refuse('unsupported storage schema')
    positive(value.get('serverId'))
    locations = value.get('locations')
    if not isinstance(locations, list) or len(locations) > 100:
        refuse('invalid storage locations')
    keys = set()
    for location in locations:
        validate_location(location)
        if location['storageKey'] in keys:
            refuse('duplicate storage key')
        keys.add(location['storageKey'])
    return value


def probe(path, uid, gid, read_only):
    # Fork keeps supplementary root groups out of the application permission check.
    pid = os.fork()
    if pid == 0:
        try:
            os.setgroups([])
            os.setgid(gid)
            os.setuid(uid)
            if not os.access(path, os.R_OK | os.X_OK):
                os._exit(2)
            if not read_only:
                fd, tmp = tempfile.mkstemp(prefix='.deploy-storage-probe-', dir=path)
                try:
                    os.write(fd, b'deploy-storage-probe\n')
                    os.fsync(fd)
                finally:
                    os.close(fd)
                    os.unlink(tmp)
            os._exit(0)
        except BaseException:
            os._exit(2)
    _, status = os.waitpid(pid, 0)
    if status != 0:
        refuse('container UID/GID storage permission or IO check failed')


def validate_spec(spec):
    if spec.get('schemaVersion') != 1:
        refuse('unsupported storage schema')
    for key in ('serverId', 'projectId'):
        positive(spec.get(key))
    positive(spec.get('requestId'), True)
    positive(spec.get('componentId'), True)
    if spec.get('environment') not in ('TEST', 'PROD') or not NAME.fullmatch(str(spec.get('containerName', ''))):
        refuse('invalid storage deployment identity')
    user = re.fullmatch(r'([1-9][0-9]{0,8}):([1-9][0-9]{0,8})', str(spec.get('containerUser', '')))
    if not user:
        refuse('storage requires a non-root numeric UID:GID')
    allowed = read_json(ROOT + '/allowlist.json')
    if spec['serverId'] != allowed.get('serverId'):
        refuse('storage server identity mismatch')
    locations = {row['storageKey']: row for row in allowed.get('locations', [])}
    mounts = spec.get('mounts')
    if not isinstance(mounts, list) or not 1 <= len(mounts) <= 32:
        refuse('one to 32 mounts required')
    result, targets = [], []
    for mount in mounts:
        registered = locations.get(mount.get('storageKey'))
        if not registered or not registered.get('enabled'):
            refuse('storage location disabled or missing')
        validate_location(registered)
        if any(mount.get(key) != registered.get(key) for key in
               ('storageKey', 'kind', 'rootPath', 'mountTarget', 'mountSource', 'fsType')):
            refuse('approved storage identity differs from allowlist')
        relative = mount.get('relativePath')
        if relative in ('', '.'):
            source = registered['rootPath']
        elif isinstance(relative, str) and re.fullmatch(r'[A-Za-z0-9_.+/-]+', relative) and not relative.startswith('/') and all(p not in ('', '.', '..') for p in relative.split('/')):
            source = registered['rootPath'] + '/' + relative
        else:
            refuse('invalid relative storage path')
        canonical(source)
        ancestry(source)
        # The application may write inside the leaf, but must not be able to
        # rename/replace that leaf or any ancestor between validation and bind.
        # NAS exports must enforce the same ownership against remote writers.
        ancestry(os.path.dirname(source), True)
        # A nested mount must retain the registered mount identity too.
        actual = identity(source, registered['kind'])
        if any(actual[key] != registered.get(key) for key in ('mountTarget', 'mountSource', 'fsType')):
            refuse('nested mount identity changed')
        target = canonical(mount.get('containerPath'), False)
        if any(within(target, p) or within(p, target) for p in ('/proc', '/sys', '/dev', '/etc', '/usr', '/bin', '/sbin', '/lib', '/lib64', '/app/logs', '/tmp')):
            refuse('container target conflicts with runtime or system path')
        if any(within(target, p) or within(p, target) for p in targets):
            refuse('overlapping container mount targets')
        targets.append(target)
        if type(mount.get('readOnly')) is not bool:
            refuse('readOnly must be boolean')
        probe(source, int(user.group(1)), int(user.group(2)), mount['readOnly'])
        result.append('type=bind,src=' + source + ',dst=' + target + (',readonly' if mount['readOnly'] else ''))
    return result


def spec_path(spec):
    return ROOT + '/specs/{projectId}-{environment}-{componentId}-{requestId}-{containerName}.json'.format(**spec)


def load_spec(path):
    if not path.startswith(ROOT + '/specs/') or '/' in path[len(ROOT + '/specs/'):] or not path.endswith('.json'):
        refuse('mount specification must be a prepared immutable file')
    spec = read_json(path)
    if path != spec_path(spec):
        refuse('mount specification filename identity mismatch')
    return spec


def inspect_container(name):
    original = name[:-len('.deploy-previous')] if name.endswith('.deploy-previous') else name
    if not NAME.fullmatch(original):
        refuse('invalid container name')
    rows = json.loads(command(['docker', 'container', 'inspect', name]))
    if len(rows) != 1:
        refuse('container missing')
    return rows[0]


def container_spec(name):
    row = inspect_container(name)
    path = (row.get('Config', {}).get('Labels') or {}).get(LABEL)
    if not path:
        return row, None
    spec = load_spec(path)
    labels = row['Config'].get('Labels') or {}
    if labels.get('com.portfolio.deploy.project') != str(spec['projectId']) or row['Config'].get('User') != spec['containerUser']:
        refuse('container storage ownership/user mismatch')
    if name not in (spec['containerName'], spec['containerName'] + '.deploy-previous'):
        refuse('container storage name mismatch')
    if row.get('HostConfig', {}).get('RestartPolicy', {}).get('Name') not in ('no', ''):
        refuse('mounted container must use guarded restart policy')
    expected = {}
    for mount in spec.get('mounts', []):
        relative = mount.get('relativePath')
        source = mount['rootPath'] + ('/' + relative if relative not in ('', '.') else '')
        expected[mount['containerPath']] = (source, not mount['readOnly'])
    actual = {mount.get('Destination'): (mount.get('Source'), mount.get('RW'))
              for mount in row.get('Mounts', []) if mount.get('Type') == 'bind'}
    if expected != actual:
        refuse('actual container bind mounts differ from immutable specification')
    return row, spec


def bounded(function):
    # An unavailable NFS server can block stat/fsync. The caller returns on deadline;
    # no local-directory fallback is permitted. The isolated process group is killed.
    read_fd, write_fd = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(read_fd)
        os.setsid()
        try:
            result = {'ok': True, 'value': function()}
        except BaseException as error:
            result = {'ok': False, 'error': str(error)}
        data = encode(result)
        os.write(write_fd, data)
        os.close(write_fd)
        os._exit(0)
    os.close(write_fd)
    try:
        chunks = []
        deadline = time.monotonic() + TIMEOUT + 2
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([read_fd], [], [], remaining)[0]:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                refuse('storage check timed out; container was not started')
            chunk = os.read(read_fd, 8192)
            if not chunk:
                break
            chunks.append(chunk)
            if sum(map(len, chunks)) > LIMIT * 2:
                refuse('storage response too large')
        data = b''.join(chunks)
        result = json.loads(data.decode('utf-8'))
        if not result['ok']:
            refuse(result['error'])
        return result['value']
    finally:
        os.close(read_fd)
        os.waitpid(pid, os.WNOHANG)


def desired(name, enabled):
    row, spec = container_spec(name)
    if spec is None:
        return
    write_json(ROOT + '/desired/' + spec['containerName'] + '.json',
               dict(containerName=spec['containerName'], containerId=row['Id'], desired=enabled,
                    specPath=spec_path(spec)))


def boot():
    private_dir(ROOT + '/desired')
    failures = []
    for filename in sorted(os.listdir(ROOT + '/desired')):
        if not filename.endswith('.json'):
            continue
        try:
            state = read_json(ROOT + '/desired/' + filename)
            if state.get('desired') is not True:
                continue
            name = state['containerName']
            if not NAME.fullmatch(name):
                refuse()
            # Same lock as the deployment agent prevents boot start during replacement.
            lock_path = RECOVERY_ROOT + '/' + name + '.lock'
            private_dir(os.path.dirname(lock_path))
            fd = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'w') as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    continue
                # A manual stop may have committed desired=false while we waited
                # to acquire this container lock. Never act on the earlier read.
                state = read_json(ROOT + '/desired/' + filename)
                if state.get('desired') is not True:
                    continue
                if state.get('containerName') != name:
                    refuse('desired filename identity changed')
                row, spec = container_spec(name)
                if not spec or row['Id'] != state.get('containerId') or spec_path(spec) != state.get('specPath'):
                    refuse('desired container generation differs')
                bounded(lambda: validate_spec(spec))
                if not row.get('State', {}).get('Running'):
                    command(['docker', 'start', name])
        except BaseException as error:
            failures.append(filename + ': ' + str(error))
    return dict(ok=not failures, failures=failures)


def main():
    if os.geteuid() != 0:
        refuse('root required')
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['inspect', 'register', 'check', 'prepare', 'arguments', 'check-container', 'desired', 'boot'])
    parser.add_argument('--root')
    parser.add_argument('--kind')
    parser.add_argument('--spec')
    parser.add_argument('--sha256')
    parser.add_argument('--container')
    parser.add_argument('--enabled', choices=['true', 'false'])
    parser.add_argument('--project')
    parser.add_argument('--component')
    parser.add_argument('--environment')
    parser.add_argument('--user')
    args = parser.parse_args()
    private_dir(ROOT)
    # Serialize all metadata writers; immutable preparation cannot race an overwrite.
    lock_fd = None
    if args.action in ('register', 'prepare', 'desired'):
        lock_fd = os.open(ROOT + '/metadata.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
    if args.action == 'inspect':
        result = bounded(lambda: identity(args.root, args.kind))
    elif args.action in ('register', 'check', 'prepare'):
        data = sys.stdin.buffer.read(LIMIT + 1)
        if not data or len(data) > LIMIT:
            refuse('storage JSON must be 1..65536 bytes')
        value = json.loads(data.decode('utf-8'))
        if args.action == 'register':
            bounded(lambda: validate_allowlist(value))
            write_json(ROOT + '/allowlist.json', value)
            result = dict(ok=True)
        else:
            bounded(lambda: validate_spec(value))
            result = dict(ok=True)
            if args.action == 'prepare':
                positive(value.get('requestId'))
                path = spec_path(value)
                write_json(path, value, True)
                result = dict(path=path, sha256=hashlib.sha256(encode(value)).hexdigest())
    elif args.action == 'arguments':
        spec = load_spec(args.spec)
        if not args.sha256 or hashlib.sha256(encode(spec)).hexdigest() != args.sha256:
            refuse('mount specification SHA-256 mismatch')
        for field, expected in [('projectId', args.project), ('componentId', args.component),
                                ('environment', args.environment), ('containerName', args.container),
                                ('containerUser', args.user)]:
            if expected is None or str(spec.get(field)) != expected:
                refuse('mount spec does not match container deployment: ' + field)
        result = bounded(lambda: validate_spec(spec))
        print('\n'.join(result))
        return
    elif args.action == 'check-container':
        row, spec = container_spec(args.container)
        if spec:
            bounded(lambda: validate_spec(spec))
        result = dict(ok=True, mounted=bool(spec))
    elif args.action == 'desired':
        if args.enabled is None:
            refuse('desired state required')
        desired(args.container, args.enabled == 'true')
        result = dict(ok=True)
    else:
        result = boot()
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('STORAGE_REFUSED: ' + str(error), file=sys.stderr)
        sys.exit(1)
