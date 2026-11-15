"""Root-only onboarding; readiness remains the broker's measured result."""
import importlib.util
import json
import os
import stat
import tempfile
import uuid
from pathlib import Path


class SetupError(ValueError):
    pass


def reject_external_database(library, config):
    current = library.inspect(config['containerName'])
    values = list(current.get('Config', {}).get('Env', []))
    data = library.volume_path(config['volume'])
    for relative in ('etc/nexus.properties', 'etc/fabric/nexus-store.properties', 'etc/fabric/nexus.properties'):
        path = data / relative
        if path.exists() or path.is_symlink():
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 262144:
                raise SetupError('DB 설정을 안전하게 확인할 수 없습니다. 관리자 확인 후 재개하세요.')
            values.append(path.read_text(encoding='utf-8'))
    text = '\n'.join(values).lower()
    if any(marker in text for marker in ('jdbc:postgresql:', 'jdbc:mysql:', 'jdbc:mariadb:', 'jdbc:oracle:', 'nexus_datastore_nexus_jdbcurl=postgres')):
        raise SetupError('외부 DB 연결 설정이 발견되어 H2 전용 백업 준비를 거부했습니다.')


def broker_module(trusted):
    path = trusted(Path('/usr/local/lib/deployops/nexus-backup/nexus-backup-broker.py'), private=True)
    spec = importlib.util.spec_from_file_location('onboarding_broker', str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(broker, storage_confirmed, writers_confirmed, restore_confirmed, port):
    if not all(x is True for x in (storage_confirmed, writers_confirmed, restore_confirmed)):
        raise ValueError('OPERATOR_CONFIRMATION_REQUIRED')
    original = broker.read_file(broker.CONFIG, 0, 0o600, group=0)
    config = broker.validate_config(broker.parse_unique(original))
    worker = broker.load_restore_worker()
    restore_original = broker.read_file(worker.CONFIG, 0, 0o600, group=0)
    restore = worker.validate_config(broker.parse_unique(restore_original))
    restore.update(enabled=True, portBase=port)
    worker.validate_config(restore)
    candidate = dict(config, externalWritersManaged=True, allStorageInDataVolume=True)
    library = broker.load_library()
    reject_external_database(library, candidate)
    ready, reason = broker.source_readiness(candidate, library)
    if ready is not True:
        raise ValueError(reason or 'SOURCE_NOT_READY')
    return [(broker.CONFIG, original, candidate), (worker.CONFIG, restore_original, restore)]


def atomic_config(path, payload):
    fd, temporary = tempfile.mkstemp(prefix='.nexus-setup-', dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'wb') as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, str(path))
        directory = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def commit(broker, changes, write_private=atomic_config):
    # Recheck both preimages before either mutation; preserve originals for recovery.
    for path, old, value in changes:
        if broker.read_file(path, 0, 0o600, group=0) != old:
            raise ValueError('CONFIG_CHANGED')
    token = uuid.uuid4().hex
    for path, old, value in changes:
        backup = path.with_name(path.name + '.before-onboarding-' + token)
        fd = os.open(str(backup), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as output:
            output.write(old)
            output.flush()
            os.fsync(output.fileno())
    try:
        for path, old, value in changes:
            write_private(path, (json.dumps(value, sort_keys=True) + '\n').encode('utf-8'))
    except BaseException:
        failed = False
        for path, old, value in reversed(changes):
            try:
                write_private(path, old)
            except BaseException:
                failed = True
        if failed:
            raise SetupError('설정 저장 및 원복 일부가 실패했습니다. 같은 폴더의 before-onboarding 사본을 보존했고, 준비 설정을 다시 실행해 두 설정을 확인해야 합니다. 실제 준비 상태는 broker 결과로 확인하세요.')
        raise SetupError('설정 저장에 실패하여 두 설정을 원래 값으로 복구했습니다. 저장 공간/권한을 확인한 후 다시 실행하세요.')


def enable(wizard, trusted):
    import fcntl
    broker = broker_module(trusted)
    wizard.io.say('지원 범위: 설치기 기본 H2 + /nexus-data 내부 파일 blob + 별도 키 파일입니다. 외부 DB/S3/NAS blob은 이 기능으로 준비 완료 처리하지 않습니다.')
    storage = wizard.io.confirm('Nexus 관리 화면에서 모든 blob이 /nexus-data/blobs 아래이고 외부 DB·외부 저장소가 없음을 확인했습니까?')
    writers = wizard.io.confirm('모든 CI·수동 업로드·외부 쓰기 사용자를 파악했고, 매 백업/복원 전에 쓰기 중단을 관리하겠습니까?')
    isolation = wizard.io.confirm('복원은 원본 교체가 아닌 별도 컨테이너/볼륨 검증이며, 별도 메모리·디스크와 관리용 접근이 필요함을 확인했습니까?')
    port = int(wizard.io.ask('격리복원 예약 시작 포트 (1024~30000)', '18000'))
    if not all((storage, writers, isolation)):
        wizard.io.say('확인하지 않은 항목이 있어 기존 설정을 유지합니다.')
        return False
    lock_path = broker.BASE / 'state/broker.lock'
    broker.path_parents(lock_path)
    fd = os.open(str(lock_path), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        broker.require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        changes = prepare(broker, storage, writers, isolation, port)
        wizard.io.say('자동 검사: 컨테이너·이미지·로컬 H2 파일/볼륨 계약·키 결합·HTTP 상태 통과. 현재 DB 종류와 모든 blob 경로는 위 운영자 확인을 함께 사용하며, 자동 검사가 모든 외부 저장소 부재를 증명하지는 않습니다. 변경 대상: /etc/deployops/nexus-backup.json, /etc/deployops/nexus-restore.json. 기존 파일은 같은 폴더에 보존됩니다.')
        if not wizard.io.confirm('위 백업/격리복원 준비 설정을 저장합니까?'):
            return False
        # Operator input may take time: repeat live source checks before commit.
        fresh = prepare(broker, storage, writers, isolation, port)
        if fresh != changes:
            raise ValueError('CONFIG_CHANGED')
        commit(broker, changes)
        return True
    finally:
        os.close(fd)
