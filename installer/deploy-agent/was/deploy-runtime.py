#!/usr/bin/python3
"""Scoped runtime files and application logs; no user chosen host paths."""
import argparse
import json
import os
import re
import stat
import subprocess
import sys
import tempfile

ROOT = '/etc/deploy/runtime'
MAX_ENV = 65536
MAX_LOG = 262144

def refuse():
    raise ValueError('RUNTIME_CONTRACT_REFUSED')

def safe_ancestry(path, owner=0):
    path = os.path.abspath(path)
    while True:
        info = os.lstat(path)
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != owner or info.st_mode & 0o022:
            refuse()
        if path == '/':
            break
        path = os.path.dirname(path)

def private_dirs(path):
    if os.path.isdir(path):
        safe_ancestry(path)
        return
    parent = os.path.dirname(path)
    private_dirs(parent)
    os.mkdir(path, 0o700)
    safe_ancestry(path)

def env_content(payload):
    if not payload or len(payload) > MAX_ENV:
        refuse()
    text = payload.decode('utf-8', 'strict')
    keys = set()
    for line in text.split('\n'):
        if not line:
            continue
        key, separator, value = line.partition('=')
        if not separator or not re.match(r'^[A-Za-z_][A-Za-z0-9_]{0,127}$', key) or key in keys or any(ord(c) < 32 or ord(c) == 127 for c in line):
            refuse()
        keys.add(key)
    if not keys:
        refuse()

def write_env(args):
    payload = sys.stdin.buffer.read(MAX_ENV + 1)
    env_content(payload)
    directory = ROOT + '/' + str(args.project) + '/' + args.environment
    private_dirs(directory)
    path = directory + '/' + str(args.revision) + '.env'
    if os.path.lexists(path):
        info = os.lstat(path)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
            refuse()
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as stream:
            if stream.read(MAX_ENV + 1) != payload:
                refuse()
        return
    # Complete and fsync the private inode before absent-only atomic publication.
    fd, temporary = tempfile.mkstemp(prefix='.pending-', dir=directory)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path, follow_symlinks=False)
        os.unlink(temporary)
        temporary = None
        parent = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if temporary is not None:
            os.unlink(temporary)

def docker(arguments):
    result = subprocess.run(['/usr/bin/docker'] + arguments, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
    if result.returncode:
        refuse()
    return result.stdout.decode('utf-8', 'strict').strip()

def volume_name(args):
    return 'deploy-p{}-c{}-{}-logs'.format(args.project, args.component, args.environment.lower())

def volume(args, create):
    name = volume_name(args)
    listing = docker(['volume', 'ls', '--filter', 'name=^' + name + '$', '--format', '{{.Name}}'])
    labels = {'deploy.project': str(args.project), 'deploy.component': str(args.component), 'deploy.environment': args.environment, 'deploy.purpose': 'app-logs'}
    if not listing:
        if not create:
            refuse()
        command = ['volume', 'create', '--driver', 'local']
        for key, value in sorted(labels.items()):
            command += ['--label', key + '=' + value]
        docker(command + [name])
    elif listing != name:
        refuse()
    info = json.loads(docker(['volume', 'inspect', name]))
    if len(info) != 1 or info[0]['Name'] != name or info[0]['Driver'] != 'local' or info[0].get('Options') not in (None, {}) or info[0].get('Labels') != labels:
        refuse()
    root = docker(['info', '--format', '{{.DockerRootDir}}'])
    safe_ancestry(root)
    expected = root + '/volumes/' + name + '/_data'
    if info[0]['Mountpoint'] != expected:
        refuse()
    safe_ancestry(os.path.dirname(expected))
    data = os.lstat(expected)
    if not stat.S_ISDIR(data.st_mode) or stat.S_ISLNK(data.st_mode):
        refuse()
    if create and not os.listdir(expected) and data.st_uid == 0 and data.st_gid == 0:
        os.chmod(expected, 0o750)
        os.chown(expected, args.uid, args.gid)
    data = os.lstat(expected)
    if data.st_uid != args.uid or data.st_gid != args.gid or data.st_mode & 0o022:
        refuse()
    return expected

def logs(args):
    directory = volume(args, False)
    path = directory + '/demo-app.log'
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        refuse()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        size = stream.seek(0, os.SEEK_END)
        start = max(0, size - MAX_LOG)
        stream.seek(start)
        payload = stream.read(MAX_LOG)
    if b'\0' in payload:
        refuse()
    if start:
        first, separator, remainder = payload.partition(b'\n')
        if separator:
            payload = remainder
    lines = payload.decode('utf-8', 'replace').splitlines()
    text = '\n'.join(lines[-args.lines:])
    encoded = text.encode('utf-8')
    clipped = len(encoded) > MAX_LOG
    if clipped:
        text = encoded[-MAX_LOG:].decode('utf-8', 'ignore')
    print(json.dumps({'text': text, 'truncated': bool(start or clipped or len(lines) > args.lines)}, ensure_ascii=True))

def main():
    if os.geteuid() != 0:
        refuse()
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['env', 'volume', 'logs'])
    parser.add_argument('--project', type=int, required=True)
    parser.add_argument('--environment', choices=['TEST', 'PROD'], required=True)
    parser.add_argument('--component', type=int, default=0)
    parser.add_argument('--revision', type=int)
    parser.add_argument('--uid', type=int, default=10001)
    parser.add_argument('--gid', type=int, default=10001)
    parser.add_argument('--lines', type=int, default=200)
    args = parser.parse_args()
    if args.project < 1 or args.component < 0 or not 1 <= args.uid <= 2147483647 or not 1 <= args.gid <= 2147483647 or not 1 <= args.lines <= 1000:
        refuse()
    os.umask(0o077)
    if args.action == 'env':
        if args.revision is None or args.revision < 1:
            refuse()
        write_env(args)
    elif args.action == 'volume':
        volume(args, True)
    else:
        logs(args)
    if args.action != 'logs':
        print('RUNTIME_READY')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('RUNTIME_CONTRACT_REFUSED', file=sys.stderr)
        sys.exit(1)
