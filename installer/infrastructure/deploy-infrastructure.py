"""Allowlisted host operations invoked by the outbound Deploy agent."""
import base64
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import threading

BROKER = Path('/var/lib/deployops/nexus-backup')
LIMIT = 8192
CONFIG = Path('/etc/deploy-system/infrastructure.json')
AGENT_CONFIG = Path('/etc/deploy-system/agent.json')
JOBS = Path('/var/lib/deploy-system/infrastructure-jobs')
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'LANG': 'C.UTF-8', 'DOCKER_HOST': 'unix:///var/run/docker.sock'}


def host_config():
    data = json.loads(regular(CONFIG, 0, 0o600))
    agent = json.loads(regular(AGENT_CONFIG, 0, 0o600))
    role = agent.get('role')
    if role not in ('DEPLOY', 'NEXUS', 'GITLAB', 'RUNNER') or data.get('role') != role:
        raise ValueError('Infrastructure role mismatch')
    return data


def capture(command, timeout=60, limit=1048576):
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, env=ENV, start_new_session=True)
    output = bytearray()
    total = [0]
    def drain():
        for block in iter(lambda: process.stdout.read(16384), b''):
            total[0] += len(block)
            output.extend(block)
            if len(output) > limit:
                del output[:-limit]
    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    try:
        code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        import signal
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        raise ValueError('Infrastructure command outcome requires inspection')
    finally:
        reader.join(timeout=5)
        if not reader.is_alive():
            process.stdout.close()
    if reader.is_alive():
        raise ValueError('Infrastructure output did not close')
    if code:
        raise ValueError('Infrastructure command failed')
    return bytes(output), total[0] > limit


def run(command, timeout=60):
    output, truncated = capture(command, timeout)
    if truncated:
        raise ValueError('Infrastructure output too large')
    return output


def target(config, service):
    roles = {'DEPLOY': {'nexus'}, 'NEXUS': {'nexus'}, 'GITLAB': {'gitlab', 'runner'}, 'RUNNER': {'runner'}}
    if service not in roles[config['role']]:
        raise ValueError('Service outside agent role')
    name = config.get('containers', {}).get(service)
    if not isinstance(name, str) or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', name):
        raise ValueError('Service is not configured')
    return name


def secure_directory(root):
    # Check existing ancestors before mkdir: never create a directory through a link.
    for item in [root] + list(root.parents):
        try:
            info = item.lstat()
        except FileNotFoundError:
            continue
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022:
            raise ValueError('Unsafe maintenance state directory')
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    for item in [root] + list(root.parents):
        info = item.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022:
            raise ValueError('Unsafe maintenance state directory')
    if stat.S_IMODE(root.stat().st_mode) != 0o700:
        raise ValueError('Private maintenance directory required')
    return root


def begin_maintenance(action, identity, request):
    root = secure_directory(JOBS)
    marker = root / (action + '-' + identity + '.json')
    # An interrupted job must be inspected. Starting another agent task with the
    # same identity never repeats a backup or creates another restore target.
    fd = os.open(str(marker), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump({'state': 'STARTED', 'request': request}, stream)
        stream.flush()
        os.fsync(stream.fileno())
    return marker


def finish_maintenance(marker, result):
    fd, temporary = tempfile.mkstemp(prefix='.result-', dir=str(marker.parent))
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump({'state': 'COMPLETED', 'result': result}, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, str(marker))
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return result


def maintenance_history():
    if not JOBS.exists():
        return []
    root = secure_directory(JOBS)
    paths = sorted(root.glob('*.json'), key=lambda p: p.lstat().st_mtime, reverse=True)[:20]
    return [{'id': path.stem, **json.loads(regular(path, 0, 0o600))} for path in paths]


def host_operation(action, request):
    if action in ('backup', 'restore', 'start', 'stop', 'restart'):
        import fcntl
        root = secure_directory(JOBS)
        fd = os.open(str(root / 'maintenance.lock'), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError('Another infrastructure maintenance operation is running')
            return perform_host_operation(action, request)
    return perform_host_operation(action, request)


def perform_host_operation(action, request):
    config = host_config()
    if action == 'status':
        services = []
        for service in config.get('containers', {}):
            name = target(config, service)
            try:
                result = json.loads(run(['/usr/bin/docker', 'inspect', '--format', '{{json .State}}', name]))
            except ValueError:
                if config['role'] == 'GITLAB' and service == 'runner':
                    services.append({'service': service, 'container': name, 'running': False, 'status': 'NOT_INSTALLED'})
                    continue
                raise
            services.append({'service': service, 'container': name, 'running': result.get('Running') is True,
                             'status': result.get('Status', 'unknown')})
        required = [row for row in services if not (config['role'] == 'GITLAB' and row['service'] == 'runner')]
        return {'ready': bool(required) and all(row['running'] for row in required), 'role': config['role'],
                'services': services, 'maintenance': maintenance_history()}
    service = request.get('service')
    name = target(config, service)
    if action == 'logs':
        lines = request.get('lines', 200)
        if type(lines) is not int or not 1 <= lines <= 1000:
            raise ValueError('Invalid line count')
        # Docker logs writes stderr logs to stderr. Merge locally, bound returned output.
        output, truncated = capture(['/usr/bin/docker', 'logs', '--tail', str(lines), '--timestamps', name], 30, 262144)
        return {'text': output.decode('utf-8', errors='replace'), 'truncated': truncated}
    if action in ('start', 'stop', 'restart'):
        if service == 'nexus':
            raise ValueError('Use the approved Nexus maintenance workflow')
        run(['/usr/bin/docker', action, name], timeout=180)
        return {'service': service, 'action': action, 'completed': True}
    if action == 'backup' and service == 'gitlab':
        backup_id = request.get('backupId')
        if not isinstance(backup_id, str) or not re.fullmatch(r'[0-9a-f]{32}', backup_id):
            raise ValueError('Invalid backup identity')
        marker = begin_maintenance('backup', backup_id, request)
        root = secure_directory(Path('/var/backups/deploy-system/gitlab'))
        artifact = root / backup_id
        run(['/bin/bash', '/usr/local/deploy/lib/gitlab/backup-gitlab.sh', '--container', name,
             '--output', str(artifact)], timeout=7000)
        return finish_maintenance(marker, {'backupId': backup_id, 'completed': True})
    if action == 'restore' and service == 'gitlab':
        backup_id = request.get('backupId')
        restore_id = request.get('restoreId')
        if any(not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{32}', value) for value in (backup_id, restore_id)):
            raise ValueError('Invalid restore identity')
        port = config.get('restoreHttpPort')
        if type(port) is not int or not 1024 <= port <= 65535:
            raise ValueError('Isolated restore HTTP port must be configured')
        marker = begin_maintenance('restore', restore_id, request)
        root = secure_directory(Path('/var/lib/deploy-system/gitlab-restores'))
        backup_root = secure_directory(Path('/var/backups/deploy-system/gitlab'))
        artifact = backup_root / backup_id
        if not artifact.exists():
            raise ValueError('Backup artifact is missing')
        secure_directory(artifact)
        network = 'deploy-gitlab-restore-' + restore_id
        run(['/usr/bin/docker', 'network', 'create', '--internal', network])
        run(['/bin/bash', '/usr/local/deploy/lib/gitlab/restore-gitlab.sh',
             '--artifact', '/var/backups/deploy-system/gitlab/' + backup_id,
             '--container', 'deploy-gitlab-restore-' + restore_id,
             '--data', str(root / restore_id), '--http-port', str(port), '--ssh-port', '0',
             '--network', network], timeout=7000)
        return finish_maintenance(marker, {'backupId': backup_id, 'restoreId': restore_id, 'httpPort': port, 'isolated': True, 'completed': True})
    raise ValueError('Unsupported host operation')


def regular(path, owner, mode):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != owner or stat.S_IMODE(info.st_mode) != mode or info.st_nlink != 1:
        raise ValueError('Unsafe broker file')
    with path.open('rb') as stream:
        data = stream.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError('Broker file too large')
    return data


def broker_path(value):
    if not isinstance(value, str) or not re.fullmatch(r'(inbox|results)/(ready|(?:restore-|promotion-)?[1-9][0-9]*(?:-[1-9][0-9]*\.check)?)\.json', value):
        raise ValueError('Invalid broker path')
    path = BROKER / value
    for parent in [BROKER, path.parent]:
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError('Unsafe broker directory')
    for parent in BROKER.parents:
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022:
            raise ValueError('Unsafe broker ancestry')
    if BROKER.stat().st_uid != 0:
        raise ValueError('Unsafe broker owner')
    return path


def nexus_broker(request):
    path = broker_path(request.get('path'))
    operation = request.get('operation')
    if operation == 'exists':
        try:
            info = path.lstat()
        except FileNotFoundError:
            return b'false'
        if not stat.S_ISREG(info.st_mode):
            raise ValueError('Unsafe broker file')
        return b'true'
    if operation == 'read':
        if path.parent.name != 'results':
            raise ValueError('Only results are readable')
        return regular(path, 0, 0o640)
    if operation not in ('publish', 'publish-exact') or path.parent.name != 'inbox':
        raise ValueError('Unsupported broker operation')
    payload = request.get('payload')
    if not isinstance(payload, dict) or payload.get('schema') != 1 or payload.get('action') not in (
            'BACKUP', 'CHECK_RECOVERY', 'RESTORE', 'CHECK_RESTORE_FAILURE', 'CHECK_EXISTING_CLONE_READINESS', 'PROMOTE'):
        raise ValueError('Unsupported broker payload')
    data = json.dumps(payload, separators=(',', ':')).encode('utf-8')
    if len(data) > LIMIT:
        raise ValueError('Broker payload too large')
    owner = path.parent.stat()
    if stat.S_IMODE(owner.st_mode) != 0o700:
        raise ValueError('Unsafe broker inbox')
    # Same immutable request can be safely retried after a lost response.
    if path.exists():
        if json.loads(regular(path, owner.st_uid, 0o600)) != payload:
            raise ValueError('Broker request identity changed')
        return b''
    fd, temporary = tempfile.mkstemp(prefix='.agent-', dir=str(path.parent))
    try:
        os.fchown(fd, owner.st_uid, owner.st_gid)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, str(path))
        except FileExistsError:
            if json.loads(regular(path, owner.st_uid, 0o600)) != payload:
                raise ValueError('Concurrent broker request differs')
    finally:
        os.unlink(temporary)
    return b''


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise ValueError('Unsupported infrastructure command')
    raw = sys.stdin.buffer.read(65537)
    if len(raw) > 65536:
        raise ValueError('Request too large')
    request = json.loads(raw)
    if sys.argv[1] == 'nexus-broker':
        if host_config()['role'] not in ('DEPLOY', 'NEXUS'):
            raise ValueError('Nexus broker role required')
        result = nexus_broker(request)
        print(json.dumps({'ok': True, 'dataBase64': base64.b64encode(result).decode('ascii')}))
    else:
        print(json.dumps(host_operation(sys.argv[1], request)))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print(json.dumps({'ok': False, 'error': 'INFRASTRUCTURE_OPERATION_FAILED'}))
        sys.exit(1)
