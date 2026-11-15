"""Private ownership receipt for the single Nexus data volume of the Rocky installer."""
import argparse
import hashlib
import json
import os
import re
import secrets
import stat
import subprocess
import sys
import tempfile
from pathlib import Path


NAME = 'nexus-data'
LABEL = 'com.portfolio.deploy.installer.volume-owner'
RECEIPT = 'nexus-volume-owner.json'
ENV = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
       'HOME': '/root', 'DOCKER_HOST': 'unix:///var/run/docker.sock', 'LC_ALL': 'C'}


class VolumeError(ValueError):
    pass


def run(args):
    try:
        return subprocess.run(args, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              universal_newlines=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise VolumeError('Docker 또는 로컬 파일시스템 상태를 확인할 수 없습니다.') from exc


def inspect_volume():
    result = run(['docker', 'volume', 'inspect', NAME])
    if result.returncode:
        if 'no such volume' in result.stderr.lower():
            return None
        raise VolumeError('Docker의 volume 조회에 실패했습니다. 기존 볼륨을 보존합니다.')
    try:
        (volume,) = json.loads(result.stdout)
        if volume['Name'] != NAME:
            raise ValueError()
        return volume
    except (ValueError, TypeError, KeyError) as exc:
        raise VolumeError('Docker의 볼륨 식별값을 검증할 수 없습니다.') from exc


def checked_directory(directory):
    directory = Path(directory)
    if not directory.is_absolute() or directory.resolve() != directory:
        raise VolumeError('설치 상태 경로가 절대 경로 또는 실제 경로가 아닙니다.')
    for path in (directory,) + tuple(directory.parents):
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise VolumeError('root가 관리하는 설치 상태 경로가 아닙니다.')
    if directory.stat().st_mode & 0o077:
        raise VolumeError('설치 상태 폴더에 root 외 접근 권한이 있습니다.')
    return directory


def get_receipt(directory):
    path = checked_directory(directory) / RECEIPT
    try:
        info = path.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_nlink != 1:
        raise VolumeError('볼륨 소유 기록 권한 또는 파일 종류를 확인할 수 없습니다.')
    return json.loads(path.read_text('utf-8'))


def write_receipt(directory, record):
    directory = checked_directory(directory)
    fd, name = tempfile.mkstemp(dir=str(directory))
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(record, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, str(directory / RECEIPT))
    finally:
        if os.path.exists(name):
            os.unlink(name)


def machine():
    raw = Path('/etc/machine-id').read_bytes().strip()
    if not re.fullmatch(rb'[0-9a-fA-F]{32}', raw):
        raise VolumeError('이 서버의 머신 식별값을 확인할 수 없습니다.')
    return hashlib.sha256(raw).hexdigest()


def evidence(volume):
    mount = Path(volume['Mountpoint'])
    if (not mount.is_absolute() or mount.resolve() != mount or mount.name != '_data' or
            mount.parent.name != NAME or mount.parent.parent.name != 'volumes' or
            volume.get('Driver') != 'local' or volume.get('Scope') != 'local' or volume.get('Options') or
            not volume.get('CreatedAt')):
        raise VolumeError('외부·공유 드라이버 또는 마운트 경로를 보존합니다.')
    info = mount.stat()
    if not stat.S_ISDIR(info.st_mode):
        raise VolumeError('Docker volume 데이터 경로를 확인할 수 없습니다.')
    return {'name': NAME, 'label': (volume.get('Labels') or {}).get(LABEL), 'created':volume['CreatedAt'],
            'mount': str(mount), 'device':info.st_dev,'inode':info.st_ino, 'machine':machine()}


def prepare(directory, name):
    # Custom names may be site-managed; the component's existing Docker behavior applies.
    if name != NAME:
        return
    directory = checked_directory(directory)
    previous = get_receipt(directory)
    if inspect_volume() is not None or previous is not None:
        return
    token = secrets.token_hex(24)
    p = run(['docker','volume','create','--driver','local','--label',LABEL+'='+token,NAME])
    if p.returncode:
        raise VolumeError('Nexus 새 볼륨을 생성할 수 없습니다.')
    created = inspect_volume()
    if created is None or (created.get('Labels') or {}).get(LABEL) != token:
        raise VolumeError('같은 이름의 다른 volume이 생성됐습니다. 소유로 등록하지 않습니다.')
    write_receipt(directory, evidence(created))


def has_attachment(mount):
    containers = run(['docker', 'ps', '-aq', '--no-trunc'])
    if containers.returncode:
        raise VolumeError('컨테이너 목록을 확인할 수 없습니다.')
    ids = containers.stdout.split()
    for start in range(0, len(ids), 64):
        batch = ids[start:start+64]
        if not all(re.fullmatch(r'[a-f0-9]{64}', identifier) for identifier in batch):
            raise VolumeError('컨테이너 ID 목록을 확인할 수 없습니다.')
        result = run(['docker', 'container', 'inspect', '-f', '{{json .Mounts}}'] + batch)
        lines = result.stdout.splitlines()
        if result.returncode or len(lines) != len(batch):
            raise VolumeError('컨테이너 mount 목록을 확인할 수 없습니다.')
        for line in lines:
            for entry in json.loads(line) or []:
                if entry.get('Name') == NAME:
                    return True
                source = entry.get('Source')
                if not source:
                    continue
                other = Path(os.path.realpath(source))
                if other == mount or other in mount.parents or mount in other.parents:
                    return True
    return False


def assessment(directory):
    try:
        record = get_receipt(directory)
        if not record:
            return None, '설치 전용 생성 기록이 없는 기존 볼륨'
        volume = inspect_volume()
        if volume is None:
            return None, '기록된 볼륨이 현재 없음'
        current = evidence(volume)
        if not re.fullmatch(r'[0-9a-f]{48}', current.get('label') or '') or record != current:
            return None, '머신·설치 라벨·생성 시각·데이터 경로 또는 inode 변경'
        mount = Path(current['mount'])
        if has_attachment(mount):
            return None, '실행 또는 중지된 컨테이너의 볼륨·바인드 마운트 참조가 있음'
        fs = run(['findmnt','-T',str(mount),'-n','-o','FSTYPE'])
        if fs.returncode or fs.stdout.strip() not in ('ext4','xfs','btrfs'):
            return None, '전용 로컬 파일시스템을 확인할 수 없음'
        # A bind mount directly on Docker's _data directory can report ext4/xfs too.
        # Reject it even if its visible tree looks empty.
        mountinfo = Path('/proc/self/mountinfo').read_text('utf-8')
        for line in mountinfo.splitlines():
            point = re.sub(r'\\([0-7]{3})', lambda m: chr(int(m.group(1), 8)), line.split()[4])
            if point == str(mount) or point.startswith(str(mount) + '/'):
                return None, '볼륨 또는 하위 경로에 별도 파일시스템/바인드 마운트가 있음'
        with os.scandir(str(mount)) as entries:
            if next(entries,None) is not None:
                return None, '데이터가 있음 (Nexus DB·blob·백업을 포함해 모두 보존)'
        return current, '전용 로컬 empty volume, 컨테이너 참조 없음'
    except (OSError, ValueError, TypeError, KeyError):
        return None, '볼륨 소유권·연결·자료를 안전하게 확인할 수 없음'


def delete_empty(directory, expected):
    current, reason = assessment(directory)
    if current is None or current != expected:
        raise VolumeError('볼륨 제거 중단: ' + reason)
    result = run(['docker','volume','rm',NAME])
    if result.returncode:
        raise VolumeError('Docker가 볼륨 삭제를 거부했습니다. 강제 삭제하지 않습니다.')
    # Removal succeeded. A stale receipt after a crash is safe and never grants a new volume.
    (checked_directory(directory) / RECEIPT).unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory')
    parser.add_argument('volume')
    args = parser.parse_args()
    if os.geteuid() != 0 or args.directory != '/root/deploy-installer/deploy':
        sys.exit('배포 역할의 root 설정 경로에서만 실행할 수 있습니다.')
    try:
        prepare(args.directory,args.volume)
    except (ValueError,OSError,TypeError,KeyError) as exc:
        sys.exit(str(exc) if isinstance(exc,VolumeError) else '볼륨 기록을 저장할 수 없습니다.')
