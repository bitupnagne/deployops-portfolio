#!/usr/bin/env python3
"""Root-only single-JAR application/helper generation replacement (Python 3.6)."""
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

PREFIX = 'BOOT-INF/classes/deploy-maintenance/v1/'
MANIFEST = PREFIX + 'deploy-system/maintenance-payload.json'
SERVICE = 'deploy-system'
TIMER = 'deployops-nexus-backup.timer'
ALLOW = {
    'deploy-system/embedded-config.py': ('/usr/local/deploy/bin/deployops-embedded-config.py', 0o750),
    'deploy-system/maintenance.py': ('/usr/local/deploy/bin/deployops-maintenance.py', 0o750),
    'deploy-system/deployops-backup.sh': ('/usr/local/deploy/bin/deployops-backup.sh', 0o750),
    'deploy-system/patch-deployops.sh': ('/usr/local/deploy/bin/patch-deployops.sh', 0o750),
    'deploy-system/install-tls-keystore.py': ('/usr/local/deploy/bin/deployops-tls.py', 0o750),
    'deploy-system/deployops-state.py': ('/usr/local/deploy/bin/deployops-state.py', 0o750),
}
for _name in ('nexus-local-backup-restore.py', 'nexus-isolated-restore-worker.py',
              'nexus-promotion-worker.py', 'nexus-backup-broker.py'):
    ALLOW['nexus-backup/' + _name] = ('/usr/local/lib/deployops/nexus-backup/' + _name, 0o600)
for _name in ('deployops-nexus-backup.service', 'deployops-nexus-backup.timer'):
    ALLOW['nexus-backup/' + _name] = ('/etc/systemd/system/' + _name, 0o644)


def payload(candidate):
    result = []
    with zipfile.ZipFile(str(candidate)) as jar:
        names = jar.namelist()
        if names.count(MANIFEST) != 1 or jar.getinfo(MANIFEST).file_size > 16384:
            raise ValueError('JAR 유지보수 payload가 없습니다. embedded-config 프로필로 빌드하세요.')
        manifest = json.loads(jar.read(MANIFEST).decode('utf-8'))
        files = manifest.get('files')
        if manifest.get('schema') != 1 or not isinstance(files, list) or len(files) != len(ALLOW):
            raise ValueError('지원하지 않는 유지보수 manifest입니다.')
        seen = set()
        for entry in files:
            source = entry.get('source')
            if source not in ALLOW or source in seen:
                raise ValueError('중복 또는 허용되지 않은 유지보수 경로입니다.')
            target, mode = ALLOW[source]
            if entry.get('target') != target or type(entry.get('mode')) is not int or entry['mode'] != mode:
                raise ValueError('유지보수 대상/권한이 허용 목록과 다릅니다.')
            member = PREFIX + source
            if names.count(member) != 1 or jar.getinfo(member).file_size > 4 * 1024 * 1024:
                raise ValueError('유지보수 파일 누락/중복/크기 오류입니다.')
            if stat.S_ISLNK(jar.getinfo(member).external_attr >> 16):
                raise ValueError('유지보수 payload symlink는 허용하지 않습니다.')
            data = jar.read(member)
            if source.endswith('.py'):
                ast.parse(data.decode('utf-8'), filename=source)
            elif source.endswith('.sh'):
                subprocess.run(['bash', '-n'], input=data, check=True, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
            result.append((Path(target), data, mode))
            seen.add(source)
    return result


def safe_path(path):
    for part in (path,) + tuple(path.parents):
        if part.is_symlink():
            raise ValueError('symlink가 포함된 유지보수 경로입니다.')
    if path.exists() and not path.is_file():
        raise ValueError('유지보수 대상은 일반 파일이어야 합니다.')


def secure_lock(path, wait_seconds=0):
    import fcntl
    safe_path(path)
    parent = path.parent.stat()
    if parent.st_uid != 0 or stat.S_IMODE(parent.st_mode) != 0o700:
        raise ValueError('root 전용 유지보수 잠금 디렉터리가 필요합니다.')
    fd = os.open(str(path), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not (stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600):
            raise ValueError('유지보수 잠금 파일의 소유자/권한/링크가 잘못되었습니다.')
        deadline = time.monotonic() + wait_seconds
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                # Only the broker's short timer polls are eligible for bounded waiting.
                # Keep concurrent patch attempts fail-fast and never remove a lock file.
                if wait_seconds <= 0:
                    raise
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ValueError('Nexus 백업·복구 또는 유지보수 작업이 잠금을 사용 중입니다. '
                                     '대기 시간이 초과되어 패치를 시작하지 않았습니다. '
                                     '작업 완료 후 다시 실행하세요.') from None
                time.sleep(min(0.2, remaining))
        return os.fdopen(fd, 'r+')
    except Exception:
        os.close(fd)
        raise


def atomic(path, data, mode, uid=0, gid=0):
    safe_path(path)
    fd, temp = tempfile.mkstemp(prefix='.deploy-patch-', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            os.fchmod(stream.fileno(), mode)
            os.fchown(stream.fileno(), uid, gid)
        os.replace(temp, str(path))
        directory = os.open(str(path.parent), os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def queue_idle(base):
    inbox = base / 'inbox'
    if not inbox.exists():
        return
    for request in inbox.iterdir():
        if request.is_symlink() or not request.is_file() or request.suffix != '.json':
            raise ValueError('확인할 수 없는 Nexus 대기열 파일이 있습니다.')
        result = base / 'results' / request.name
        if not result.is_file() or result.is_symlink():
            raise ValueError('완료되지 않은 Nexus 대기열 작업이 있습니다.')
        value = json.loads(result.read_text(encoding='utf-8'))
        name = request.name
        if name.startswith('promotion-'):
            okay = value.get('status') in ('COMPLETED', 'FAILED')
        elif name.startswith('restore-'):
            okay = (value.get('status') in ('HEALTH_READY', 'FAILED')
                    and value.get('mutationUncertain') is False
                    and value.get('originalProjectionPreserved') is True)
        else:
            okay = (value.get('status') in ('COMPLETED', 'FAILED')
                    and value.get('originalStateRestored') is True)
        if not okay:
            raise ValueError('복구가 확정되지 않은 Nexus 작업이 있습니다.')
    state = base / 'state'
    if state.is_dir():
        for ledger in state.glob('*.mutations.json'):
            if ledger.is_symlink():
                raise ValueError('Nexus 변경 이력 경로가 잘못되었습니다.')
            value = json.loads(ledger.read_text(encoding='utf-8'))
            events = value.get('events')
            if not isinstance(events, list) or any(not isinstance(e, dict) or e.get('state') != 'completed' for e in events):
                raise ValueError('종료가 확정되지 않은 Nexus 변경 이력이 있습니다.')


class Operations:
    def __init__(self, config, helper, home):
        self.home = home
        self.config_helper = helper
        self.port = int(helper.get(config, 'server.port'))
        match = re.fullmatch(r'jdbc:mariadb://([^/:?]+):([0-9]+)/([A-Za-z0-9_]+)(?:\?.*)?',
                             helper.get(config, 'spring.datasource.url'))
        self.db_args = ['-h', match[1], '-P', match[2], '-u', helper.get(config, 'spring.datasource.username'), match[3]]
        self.db_env = dict(os.environ, MYSQL_PWD=helper.get(config, 'spring.datasource.password'))

    def system(self, *args, **kwargs):
        return subprocess.run(['systemctl'] + list(args), stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, check=kwargs.get('check', True)).returncode

    def sql(self, query):
        command = shutil.which('mariadb') or shutil.which('mysql')
        if not command:
            raise ValueError('MariaDB client가 없습니다.')
        answer = subprocess.run([command, '--batch', '--skip-column-names'] + self.db_args + ['-e', query],
                                env=self.db_env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True)
        return answer.stdout.decode('ascii').strip()

    def idle(self):
        tables = set(self.sql('SHOW TABLES').splitlines())
        if 'deployment_execution_guard' not in tables:
            raise ValueError('배포 실행 잠금 테이블이 없습니다.')
        if self.sql('SELECT COUNT(*) FROM deployment_execution_guard WHERE id=1') != '1':
            raise ValueError('배포 실행 잠금 행이 없습니다.')
        columns = self.sql('SHOW COLUMNS FROM deployment_execution_guard').splitlines()
        owners = [line.split('\t')[0] for line in columns if line.split('\t')[0].endswith('_request_id')]
        if not owners or self.sql('SELECT COUNT(*) FROM deployment_execution_guard WHERE ' +
                                  ' OR '.join('`' + col + '` IS NOT NULL' for col in owners)) != '0':
            raise ValueError('실행 중인 배포/Nexus 유지보수 잠금이 있습니다.')
        checks = {
            'deploy_request': "'TEST_REQUESTED','TEST_BUILDING','TEST_DEPLOYING','PROD_DEPLOYING'",
            'nexus_backup_request': "'QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED'",
            'nexus_restore_request': "'QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED'",
            'nexus_promotion_request': "'QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED'",
            'nexus_backup_recovery_check': "'QUEUED','SUBMITTED'",
            'nexus_restore_recovery_check': "'QUEUED','SUBMITTED'",
            'nexus_cleanup_request': "'DELETING','RECONCILING'",
            'nginx_change_request': "'APPLYING'",
            'office_notification_outbox': "'SENDING'",
        }
        for table, states in checks.items():
            if table in tables and self.sql('SELECT COUNT(*) FROM `' + table + '` WHERE status IN (' + states + ')') != '0':
                raise ValueError('완료되지 않은 작업이 있어 패치를 중단합니다: ' + table)
        if 'server_agent_task' in tables and self.sql("SELECT COUNT(*) FROM server_agent_task WHERE state IN ('QUEUED','CLAIMED','RUNNING','UNKNOWN')") != '0':
            raise ValueError('완료 또는 결과 확인이 끝나지 않은 에이전트 작업이 있습니다.')
        queue_idle(Path('/var/lib/deployops/nexus-backup'))

    def backup(self, destination):
        env = dict(os.environ, DEPLOY_APP_HOME=str(self.home), DEPLOYOPS_BACKUP_DIR=str(destination / 'db-pair'), DEPLOYOPS_BACKUP_LOCKS_HELD='1')
        subprocess.run(['/usr/local/deploy/bin/deployops-backup.sh'], env=env, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def schema_generation(self):
        queries = [
            "SELECT installed_rank, IFNULL(version,''), IFNULL(checksum,0), success "
            "FROM flyway_schema_history ORDER BY installed_rank",
            "SELECT HEX(TABLE_NAME), HEX(COLUMN_NAME), ORDINAL_POSITION, HEX(COLUMN_TYPE), "
            "IS_NULLABLE, IFNULL(HEX(COLUMN_DEFAULT),''), HEX(EXTRA), IFNULL(COLLATION_NAME,'') "
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
            "ORDER BY TABLE_NAME, ORDINAL_POSITION",
            "SELECT HEX(TABLE_NAME), HEX(INDEX_NAME), NON_UNIQUE, SEQ_IN_INDEX, "
            "IFNULL(HEX(COLUMN_NAME),''), IFNULL(SUB_PART,0), INDEX_TYPE "
            "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
            "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX",
            "SELECT HEX(TABLE_NAME), HEX(CONSTRAINT_NAME), HEX(COLUMN_NAME), ORDINAL_POSITION, "
            "IFNULL(HEX(REFERENCED_TABLE_NAME),''), IFNULL(HEX(REFERENCED_COLUMN_NAME),'') "
            "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() "
            "ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION",
        ]
        # DDL can commit before Flyway records a failure; inspect structure as well as history.
        rows = '\n--schema-part--\n'.join(self.sql(query) for query in queries)
        return hashlib.sha256(rows.encode('ascii')).hexdigest()

    def healthy(self):
        spec = importlib.util.spec_from_file_location('tls_health', '/usr/local/deploy/bin/deployops-tls.py')
        tls = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tls)
        for _ in range(30):
            if self.system('is-active', '--quiet', SERVICE, check=False) == 0:
                try:
                    if tls.health_check(str(self.home / 'app/deploy-ops.jar'), self.port):
                        return True
                except Exception:
                    pass
            time.sleep(2)
        return False


def replace_generation(current, candidate, entries, backup, ops):
    """All preimages and DB pair must exist before the first overwrite."""
    originals = []
    targets = [(current, candidate.read_bytes(), stat.S_IMODE(current.stat().st_mode))] + entries
    backup.mkdir(mode=0o700)
    for index, (path, data, mode) in enumerate(targets):
        safe_path(path)
        if not path.parent.is_dir():
            raise ValueError('설치되지 않은 유지보수 대상 디렉터리입니다.')
        if path.exists():
            metadata = path.stat()
            content = path.read_bytes()
            with (backup / ('preimage-' + str(index))).open('xb') as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            originals.append((path, content, stat.S_IMODE(metadata.st_mode), metadata.st_uid, metadata.st_gid))
        else:
            originals.append((path, None, None, None, None))
    with (backup / 'preimages.json').open('x') as stream:
        os.fchmod(stream.fileno(), 0o600)
        json.dump([{'path': str(p), 'exists': d is not None, 'mode': m, 'uid': u, 'gid': g}
                   for p, d, m, u, g in originals], stream)
        stream.flush()
        os.fsync(stream.fileno())
    ops.idle()
    timer_active = ops.system('is-active', '--quiet', TIMER, check=False) == 0
    stopped = False
    written = False
    timer_safe = True
    schema_before = None
    try:
        ops.system('stop', TIMER)
        stopped = True
        timer_safe = False
        ops.system('stop', SERVICE)
        ops.idle()
        schema_before = ops.schema_generation()
        ops.backup(backup)
        for path, data, mode in targets:
            old = next(item for item in originals if item[0] == path)
            written = True
            atomic(path, data, mode, old[3] if path == current else 0, old[4] if path == current else 0)
        ops.system('daemon-reload')
        ops.system('start', SERVICE)
        if not ops.healthy():
            raise ValueError('새 JAR의 앱/DB health가 정상화되지 않았습니다.')
        timer_safe = True
    except Exception:
        if stopped:
            ops.system('stop', SERVICE, check=False)
            if written:
                try:
                    same_schema = ops.schema_generation() == schema_before
                except Exception:
                    same_schema = False
                if not same_schema:
                    raise ValueError('DB 변경 또는 상태 미확인으로 자동 JAR 원복을 중단했습니다. '
                                     '앱과 Nexus 실행기는 정지 상태입니다. 보호 백업의 DB+JAR를 함께 복원하세요.') from None
                for path, data, mode, uid, gid in reversed(originals):
                    if data is None:
                        if path.exists():
                            path.unlink()
                    else:
                        atomic(path, data, mode, uid, gid)
                ops.system('daemon-reload')
            ops.system('start', SERVICE)
            if not ops.healthy():
                raise ValueError('이전 세대도 health 복구 실패. 보호 백업의 DB+JAR로 복구하세요.')
            timer_safe = True
        raise
    finally:
        if timer_active and timer_safe:
            ops.system('start', TIMER)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise ValueError('root에서 반입 JAR 경로 하나를 지정하세요.')
    home = Path(os.environ.get('DEPLOY_APP_HOME', '/opt/deploy-system'))
    current = home / 'app/deploy-ops.jar'
    spec = importlib.util.spec_from_file_location('embedded', '/usr/local/deploy/bin/deployops-embedded-config.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    config = helper.read(current)
    ops = Operations(config, helper, home)
    if sys.argv[1] == '--check-idle':
        ops.idle()
        print('Deploy and agent queues are idle')
        return
    candidate = Path(sys.argv[1]).resolve(strict=True)
    if candidate == current.resolve():
        raise ValueError('실행 중인 JAR와 다른 반입 경로가 필요합니다.')
    state = Path('/var/lib/deployops/nexus-backup/state')
    if not state.is_dir():
        raise ValueError('Nexus broker 초기 설치가 필요합니다.')
    # The app can write its app directory; keep serialization in the root-only broker state.
    with secure_lock(state / 'deploy-patch.lock'), secure_lock(state / 'broker.lock', wait_seconds=30):
        backup_root = Path('/var/backups/deploy-system')
        if not backup_root.exists():
            backup_root.mkdir(mode=0o700)
        if backup_root.is_symlink() or backup_root.stat().st_uid != 0 or stat.S_IMODE(backup_root.stat().st_mode) != 0o700:
            raise ValueError('패치 백업 디렉터리는 root 소유 0700이어야 합니다.')
        with tempfile.TemporaryDirectory(prefix='.patch-candidate-', dir=str(backup_root)) as temp:
            os.chmod(temp, 0o700)
            snapshot = Path(temp) / 'candidate.jar'
            fd = os.open(str(snapshot), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as output, candidate.open('rb') as source:
                shutil.copyfileobj(source, output)
                output.flush()
                os.fsync(output.fileno())
            entries = payload(snapshot)
            candidate_config = helper.read(snapshot)
            helper.same(config, candidate_config)
            if bool(helper.get(config, 'server.ssl.enabled', False)) != bool(helper.get(candidate_config, 'server.ssl.enabled', False)):
                raise ValueError('HTTP/HTTPS 사용 방식 전환은 설치기의 회사 TLS 설정 절차로 진행하세요. 일반 JAR 패치로 전환하지 않습니다.')
            tls_spec = importlib.util.spec_from_file_location('tls_preflight', '/usr/local/deploy/bin/deployops-tls.py')
            tls_module = importlib.util.module_from_spec(tls_spec)
            tls_spec.loader.exec_module(tls_module)
            try:
                tls_module.install_keystore(str(snapshot), None, current.stat().st_gid, check_only=True)
                tls_module.health_settings(str(snapshot), ops.port)
            except Exception:
                raise ValueError('회사 TLS 파일·HTTPS 상태 검사 설정을 먼저 준비하세요. 앱은 변경하지 않았습니다.') from None
            backup = backup_root / ('jar-patch-{}-{}'.format(time.strftime('%Y%m%d-%H%M%S'), os.getpid()))
            try:
                replace_generation(current, snapshot, entries, backup, ops)
            except Exception:
                print('패치/복구 실패 시 보존된 이전 세대: ' + str(backup), file=sys.stderr)
                raise
    print('JAR 및 배포시스템 보조 코드 패치 완료. 앱/DB 정상 응답을 확인했습니다.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Subprocess exceptions can contain connection arguments; never print them.
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print('패치 실패: ' + message, file=sys.stderr)
        sys.exit(1)
