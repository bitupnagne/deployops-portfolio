#!/usr/bin/env python3
"""Prepare an approved offline release, then start its interactive installation.

Python 3.6 standard library only. This module never connects to another server,
changes access-control policy, or downloads installation material.
"""
from __future__ import print_function

import argparse
import ctypes
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import sys
import tarfile
import tempfile
import time

sys.dont_write_bytecode = True

RELEASE = "DeployOps-Offline-Demo"
DESTINATION = Path("/opt/offline-install/r8")
INDEX = Path("/var/lib/deploy-installer/installation.json")
COMMAND = Path("/usr/local/sbin/deploy-installer")
MAX_MEMBERS = 100000
MAX_BYTES = 128 * 1024 ** 3
REQUIRED = (
    "installer/install.sh",
    "Deploy-System-Interactive-Installer.sh",
    "installer/interactive_lifecycle.py",
    "installer/component_upgrade.py",
    "installer/bundles/RELEASE-BOM.env",
    "installer/bundles/RELEASE-LOCK.env",
    "installer/bundles/common/deploy-system/deploy-ops.jar",
    "installer/bundles/common/jq/jq-linux-amd64",
    "installer/deploy-system/embedded-config.py",
    "configuration/application-install.company.example.yml",
    "operations/configure-deploy-jar.py",
    "operations/jar_configuration.py",
)


class PreparationError(Exception):
    pass


def host_check(os_release_path="/etc/os-release", uid=None, machine=None, system=None):
    if uid is None:
        uid = os.geteuid() if hasattr(os, "geteuid") else -1
    if uid != 0:
        raise PreparationError("설치는 root로 실행해야 합니다. 승인된 root 세션으로 전환한 뒤 다시 시작하세요.")
    if (system or platform.system()) != "Linux":
        raise PreparationError("실제 설치는 Rocky Linux 8.10 서버에서 실행하세요.")
    values = {}
    try:
        with open(os_release_path, encoding="utf-8") as stream:
            for line in stream:
                key, sep, value = line.strip().partition("=")
                if sep:
                    values[key] = value.strip('"\'')
    except OSError:
        raise PreparationError("서버의 /etc/os-release를 읽지 못했습니다.")
    if values.get("ID") != "rocky" or values.get("VERSION_ID") != "8.10":
        raise PreparationError("이 설치 재료는 Rocky Linux 8.10 전용입니다. 다른 OS에 설치하지 않습니다.")
    if (machine or platform.machine()).lower() not in ("x86_64", "amd64"):
        raise PreparationError("이 설치 재료는 x86_64 서버용입니다.")


def absolute_path(value):
    value = str(value)
    if not value or any(ord(c) < 32 for c in value) or "\\" in value:
        raise PreparationError("Linux 절대 경로를 입력하세요. 제어문자와 역슬래시는 허용하지 않습니다.")
    path = Path(value)
    if not path.is_absolute() or any(p in (".", "..") for p in value.split("/")):
        raise PreparationError("/로 시작하는 실제 절대 경로를 입력하세요. . 또는 .. 경로는 사용하지 않습니다.")
    return path


def safe_ancestors(path, owner=0):
    path = absolute_path(path)
    for item in [path] + list(path.parents):
        info = os.lstat(str(item))
        if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
            raise PreparationError("설치 경로에 일반 디렉터리가 아닌 항목이 있습니다: " + str(item))
        if info.st_uid != owner:
            raise PreparationError("설치 폴더와 상위 경로는 root 소유여야 합니다: " + str(item))
        if info.st_mode & 0o022 and not (info.st_mode & stat.S_ISVTX):
            raise PreparationError("설치 경로를 다른 계정이 수정할 수 있습니다: " + str(item))
    return path


def check_package(package, owner=0):
    package = safe_ancestors(package, owner)
    for root, directories, files in os.walk(str(package), followlinks=False):
        for name in directories + files:
            path = Path(root) / name
            info = os.lstat(str(path))
            if stat.S_ISLNK(info.st_mode) or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
                raise PreparationError("설치 폴더에는 링크·특수 파일을 둘 수 없습니다: " + str(path))
            if info.st_uid != owner or info.st_mode & 0o022:
                raise PreparationError("설치 파일은 root 소유이고 다른 계정의 쓰기 권한이 없어야 합니다: " + str(path))
    for name in REQUIRED:
        path = package / name
        if not path.is_file():
            raise PreparationError("설치 재료가 빠졌습니다: " + name)
    bom = (package / "installer/bundles/RELEASE-BOM.env").read_text(encoding="utf-8-sig")
    values = dict(line.split("=", 1) for line in bom.splitlines()
                  if line and not line.lstrip().startswith("#") and "=" in line)
    if values.get("BOM_RELEASE") != "2026.10" or values.get("BOM_DEPLOYOPS_JAR") != "common/deploy-system/deploy-ops.jar":
        raise PreparationError("지원하는 Deploy 설치 패키지 구성이 아닙니다.")
    return package


def validate_member(member, seen, expected_release=RELEASE):
    name = member.name.rstrip("/")
    if not name or len(name) > 4096 or name.startswith("/") or "\\" in name or any(ord(c) < 32 for c in name):
        raise PreparationError("압축 안에 잘못된 파일 경로가 있습니다.")
    parts = name.split("/")
    if any(part in ("", ".", "..") for part in parts) or parts[0] != expected_release:
        raise PreparationError("압축 안의 파일이 지정한 패키지 폴더를 벗어납니다: " + name)
    if name in seen:
        raise PreparationError("압축 안에 중복된 파일 경로가 있습니다: " + name)
    if not (member.isdir() or member.isreg()) or getattr(member, "sparse", None):
        raise PreparationError("압축 안의 링크·장치·특수 파일은 허용하지 않습니다: " + name)
    if member.size < 0 or member.size > MAX_BYTES:
        raise PreparationError("압축 파일의 항목 크기가 잘못됐습니다.")
    seen.add(name)
    return parts


def publish_noreplace(source, target):
    """Publish the complete tree atomically without replacing another installer."""
    if os.name == "nt":
        # Windows rename already rejects an existing destination.
        os.rename(str(source), str(target))
        return
    library = ctypes.CDLL(None, use_errno=True)
    operation = getattr(library, "renameat2", None)
    if operation is None:
        raise PreparationError("덮어쓰기 없는 원자적 게시(renameat2)를 지원하지 않아 중단했습니다.")
    operation.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    operation.restype = ctypes.c_int
    if operation(-100, os.fsencode(str(source)), -100, os.fsencode(str(target)), 1) != 0:
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code), str(target))


def private_directories(path, staging):
    current = staging
    for part in path.relative_to(staging).parts:
        current = current / part
        try:
            os.mkdir(str(current), 0o700)
        except FileExistsError:
            if current.is_symlink() or not current.is_dir():
                raise PreparationError("압축 내부 경로가 디렉터리와 충돌합니다.")
        os.chmod(str(current), 0o700)


def copy_payload(source, output, progress, state):
    """Report bytes actually written even while a single large image is unpacked."""
    while True:
        chunk = source.read(1024 * 1024)
        if not chunk:
            return
        output.write(chunk)
        state['bytes'] += len(chunk)
        now = time.monotonic()
        if progress and (now - state['time'] >= 5 or state['bytes'] - state['reported'] >= 256 * 1024 ** 2):
            progress("압축 해제 중: {0:.2f} GiB 기록 완료 (현재 파일 처리 중)".format(state['bytes'] / 1024 ** 3))
            state['time'] = now
            state['reported'] = state['bytes']


def prepare_archive(archive_path, destination=DESTINATION, owner=0, progress=None):
    archive_path = absolute_path(archive_path)
    destination = absolute_path(destination)
    # A newly created destination is always beneath an already trusted directory.
    if not destination.exists():
        if destination == DESTINATION and not destination.parent.exists():
            safe_ancestors(destination.parent.parent, owner)
            destination.parent.mkdir(mode=0o700, exist_ok=True)
        safe_ancestors(destination.parent, owner)
        os.mkdir(str(destination), 0o700)
    safe_ancestors(destination, owner)
    target = destination / RELEASE
    if os.path.lexists(str(target)):
        raise PreparationError("같은 설치 폴더가 이미 있습니다. 덮어쓰지 않습니다. 처음 메뉴에서 기존 폴더를 선택하세요: " + str(target))
    info = os.lstat(str(archive_path))
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
        raise PreparationError("설치 tar는 일반 파일이어야 합니다.")
    staging = Path(tempfile.mkdtemp(prefix=".deploy-prepare-", dir=str(destination)))
    staged_package = staging / RELEASE
    published = False
    seen = set()
    total = 0
    copy_state = {'bytes': 0, 'reported': 0, 'time': time.monotonic()}
    try:
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(str(archive_path), flags)
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (opened.st_ino, opened.st_dev) != (info.st_ino, info.st_dev):
            os.close(descriptor)
            raise PreparationError("확인 도중 설치 tar 파일이 바뀌었습니다.")
        with os.fdopen(descriptor, "rb", buffering=1024 * 1024) as source:
            with gzip.GzipFile(fileobj=source, mode="rb") as compressed:
                with tarfile.open(fileobj=compressed, mode="r|") as archive:
                    for member in archive:
                        parts = validate_member(member, seen)
                        if len(seen) > MAX_MEMBERS:
                            raise PreparationError("압축 항목 수가 허용 범위를 넘었습니다.")
                        total += member.size
                        if total > MAX_BYTES:
                            raise PreparationError("압축 해제 크기가 허용 범위를 넘었습니다.")
                        target_path = staging.joinpath(*parts)
                        if member.isdir():
                            private_directories(target_path, staging)
                        else:
                            private_directories(target_path.parent, staging)
                            if shutil.disk_usage(str(staging)).free < member.size + 64 * 1024 ** 2:
                                raise PreparationError("압축 해제 공간이 부족합니다. 해당 서버의 설치 디스크를 확인하세요.")
                            stream = archive.extractfile(member)
                            if stream is None:
                                raise PreparationError("압축 파일 내용을 읽지 못했습니다.")
                            with stream:
                                with open(str(target_path), "xb") as output:
                                    copy_payload(stream, output, progress, copy_state)
                            if target_path.stat().st_size != member.size:
                                raise PreparationError("압축 파일의 실제 크기가 맞지 않습니다.")
                            os.chmod(str(target_path), 0o700 if member.mode & 0o111 else 0o600)
                        if progress and len(seen) % 250 == 0:
                            progress("압축 해제 중: {0}개 항목, {1:.1f} GiB".format(len(seen), total / 1024 ** 3))
                # Consume the gzip trailer so incomplete SFTP transfers and CRC errors fail.
                while compressed.read(1024 * 1024):
                    pass
            finished = os.fstat(source.fileno())
            if (finished.st_size, finished.st_mtime_ns, finished.st_ctime_ns) != (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns):
                raise PreparationError("압축 해제 도중 반입 파일이 바뀌었습니다. SFTP 전송이 끝난 뒤 다시 실행하세요.")
        check_package(staged_package, owner)
        os.chmod(str(staged_package / "installer/bundles/common/jq/jq-linux-amd64"), 0o755)
        publish_noreplace(staged_package, target)
        published = True
        return target
    except (tarfile.TarError, EOFError, OSError) as error:
        raise PreparationError("압축 해제를 완료하지 못했습니다 ({0}). 전송 완료·권한·공간을 확인하세요.".format(type(error).__name__))
    finally:
        # Only this invocation's root-private temporary tree is removed.
        if staging.parent == destination and staging.name.startswith(".deploy-prepare-") and not staging.is_symlink():
            shutil.rmtree(str(staging))
        if progress and not published:
            progress("설치 폴더를 게시하지 않았습니다. 기존 파일은 덮어쓰지 않았습니다.")


def banner():
    print("\nDeploy System 대화형 설치 준비")
    print("파일 반입: demo-deploy=deploy / demo-gitlab=gitlab / demo-was=docker / demo-web=nginx")
    print("실행 계정: 해당 서버의 승인된 root 세션. 이미 root이면 계정을 다시 바꾸지 않습니다.")
    print("PC에서 현재 서버에 반입한 파일만 사용합니다. 다른 서버에 접속하거나 정책을 변경하지 않습니다.")


def resume_default(state_base=Path('/root/deploy-installer'), owner=0):
    """Locate the recorded package without displaying saved credentials."""
    recorded = set()
    for role in ('deploy', 'gitlab', 'was', 'web'):
        state_file = Path(state_base) / role / 'state.json'
        try:
            safe_ancestors(state_file.parent, owner)
            info = os.lstat(str(state_file))
            if not stat.S_ISREG(info.st_mode) or info.st_uid != owner or info.st_mode & 0o077:
                continue
            with open(str(state_file), encoding='utf-8') as stream:
                state = json.load(stream)
            package = absolute_path(state.get('package', ''))
            if (package / 'installer/interactive_lifecycle.py').is_file():
                recorded.add(str(package))
        except (PreparationError, OSError, ValueError, TypeError, AttributeError):
            continue
    if len(recorded) == 1:
        return Path(next(iter(recorded)))
    return DESTINATION / RELEASE


def indexed_package(index=INDEX):
    if not os.path.lexists(str(index)):
        return None
    safe_ancestors(index.parent)
    info = index.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise PreparationError("설치기 인덱스 권한을 확인하세요: " + str(index))
    with index.open(encoding="utf8") as stream:
        record = json.load(stream)
    if record.get("format") != 1:
        raise PreparationError("지원하지 않는 설치기 인덱스입니다.")
    return absolute_path(record["package"])


def activate_package(package, index=INDEX, command=COMMAND):
    import interactive_install
    entry = package / "Deploy-System-Interactive-Installer.sh"
    interactive_install.private_directory(index.parent)
    safe_ancestors(command.parent)
    if os.path.lexists(str(command)):
        mode = command.lstat()
        if not stat.S_ISREG(mode.st_mode) or mode.st_uid != 0 or mode.st_mode & 0o022:
            raise PreparationError("재실행 명령의 소유자 또는 권한을 확인하세요: " + str(command))
    # The fixed launcher reads the protected index on every invocation, including after media replacement.
    launcher = """#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'root 세션에서 실행하세요.' >&2; exit 1; }
exec /usr/libexec/platform-python -I -B -c 'import hashlib, json, os, stat, sys
def trusted_open(path, private=False):
    pieces = path.split("/")
    if pieces[0] or len(pieces) < 2 or any(p in ("", ".", "..") for p in pieces[1:]):
        raise ValueError("잘못된 절대 경로")
    flags = os.O_RDONLY | os.O_NOFOLLOW
    fd = os.open("/", flags | os.O_DIRECTORY)
    try:
        for i, part in enumerate(pieces[1:], 1):
            last = i == len(pieces) - 1
            nxt = os.open(part, flags | (0 if last else os.O_DIRECTORY) | os.O_NONBLOCK, dir_fd=fd)
            os.close(fd)
            fd = nxt
            info = os.fstat(fd)
            if info.st_uid or info.st_mode & (0o077 if last and private else 0o022):
                raise ValueError("root 소유 또는 파일 권한 불일치")
            if last and not stat.S_ISREG(info.st_mode):
                raise ValueError("일반 파일 필요")
        return fd
    except BaseException:
        os.close(fd)
        raise
try:
    with os.fdopen(trusted_open(sys.argv[1], True), encoding="utf8") as index_file:
        record = json.load(index_file)
    package = record["package"]
    with os.fdopen(trusted_open(package + "/Deploy-System-Interactive-Installer.sh"), "rb") as script:
        if record["format"] != 1 or hashlib.sha256(script.read()).hexdigest() != record["launcher_sha256"]:
            raise ValueError("등록된 설치기와 일치하지 않습니다.")
        script.seek(0)
        os.set_inheritable(script.fileno(), True)
        os.execv("/bin/bash", ["bash", "/proc/self/fd/" + str(script.fileno()), "--package-root", package] + sys.argv[2:])
except (OSError, ValueError, KeyError, TypeError) as error:
    sys.exit("재개 실패: {}. 승인된 r8 설치 재료에서 대화형 설치기를 다시 실행하세요.".format(error))' "$INDEX_PATH" "$@"
""".replace('"$INDEX_PATH"', "'" + str(index) + "'")
    if not entry.is_file():
        raise PreparationError("재실행할 설치기가 반입본에 없습니다.")
    indexed_package(index)
    if command.exists() and command.read_bytes() != launcher.encode("utf8"):
        raise PreparationError("같은 이름의 기존 명령을 덮어쓸 수 없습니다: " + str(command))
    interactive_install.write_private(index, (json.dumps({"format": 1, "package": str(package), "launcher_sha256": hashlib.sha256(entry.read_bytes()).hexdigest()})+"\n").encode("utf8"))
    if not command.exists():
        with tempfile.NamedTemporaryFile(dir=str(command.parent), delete=False) as temp:
            temporary = Path(temp.name)
            try:
                temp.write(launcher.encode("utf8")); temp.flush(); os.fsync(temp.fileno()); os.fchmod(temp.fileno(), 0o700)
                os.link(str(temporary), str(command))
            finally:
                temporary.unlink()
    print("다음 root 접속에서 설치 재개: " + str(command))


def existing_package(path):
    """Accept the release itself, its extraction parent, or installer folder."""
    path = absolute_path(path)
    if (path / 'installer/install.sh').is_file():
        return check_package(path)
    if (path / RELEASE).is_dir():
        return check_package(path / RELEASE)
    if path.name == 'installer' and (path / 'install.sh').is_file():
        # Check the original input as well, so a symlink is never normalized away.
        safe_ancestors(path)
        return check_package(path.parent)
    raise PreparationError(
        '이 경로는 압축이 풀린 설치 폴더가 아닙니다: ' + str(path) +
        '\n폴더 안에 installer/install.sh가 있어야 합니다. 바깥 r7 폴더나 tar 파일을 고르지 마세요.')


def run_preparation(initial_path=None, reader=input, writer=print):
    path_text = initial_path
    choice = ('2' if Path(path_text).is_dir() else '1') if path_text else None
    default_package = resume_default()
    while True:
        if choice is None:
            writer("\n1. SFTP로 올린 설치 tar.gz 압축 해제")
            writer("2. 이미 압축을 푼 폴더에서 설치 재개 (저장된 설정 유지)")
            writer("   재개 기본 경로: " + str(default_package))
            writer("0. 종료")
            choice = reader("선택: ").strip()
            if choice == "0":
                return None
            if choice not in ("1", "2"):
                writer("0, 1, 2 중에서 선택하세요.")
                choice = None
                continue
        if not path_text:
            if choice == '2':
                writer('기본 경로이면 Enter만 누르세요. /opt/offline-install 상위 폴더도 입력할 수 있습니다.')
                prompt = '설치 폴더 [{0}] (0: 이전 메뉴): '.format(default_package)
            else:
                prompt = '현재 서버의 tar.gz 파일 절대 경로 (0: 이전 메뉴): '
            path_text = reader(prompt).strip()
            if path_text == '0':
                choice, path_text = None, None
                continue
            if not path_text and choice == '2':
                path_text = str(default_package)
        try:
            path = absolute_path(path_text)
            if choice == "2":
                package = existing_package(path)
                writer("기존 설치 폴더 확인 완료: " + str(package))
                writer('다음 화면에서 이전과 같은 서버 역할을 선택하면 저장된 설정과 진행 상태를 불러옵니다.')
                return package
            writer("입력 파일: " + str(path))
            writer("압축 해제 위치: " + str(DESTINATION / RELEASE))
            writer("기존 폴더를 덮어쓰지 않습니다. 프로그램 설치는 다음 역할 확인 이후에 시작합니다.")
            if reader("압축을 해제할까요? [y/N]: ").strip().lower() not in ("y", "yes"):
                choice, path_text = None, None
                continue
            package = prepare_archive(path, progress=writer)
            writer("압축 해제와 설치 폴더 준비가 끝났습니다: " + str(package))
            return package
        except (PreparationError, OSError) as error:
            writer("준비 중단: " + str(error))
            writer('파일과 저장된 설정은 변경하지 않았습니다. 경로를 다시 입력하거나 0으로 이전 메뉴에 돌아가세요.')
            path_text = None


def main(argv=None):
    parser = argparse.ArgumentParser(description="Rocky 8.10 서버의 승인된 root 세션에서 실행하는 대화형 설치기")
    parser.add_argument("--package-root", help="이미 준비된 root 소유 설치 폴더 또는 반입한 tar.gz의 절대 경로")
    args = parser.parse_args(argv)
    banner()
    try:
        host_check()
        recorded = None if args.package_root else indexed_package()
        if not args.package_root and not recorded and (DESTINATION / RELEASE).is_dir():
            recorded = DESTINATION / RELEASE
        package = run_preparation(args.package_root or (str(recorded) if recorded else None))
        if package is None:
            print("설치를 시작하지 않고 종료했습니다.")
            return 0
        import interactive_install
        activate_package(package)
        return int(interactive_install.run(str(package)) or 0)
    except (PreparationError, OSError, ValueError, EOFError, KeyboardInterrupt) as error:
        print("설치 중단: " + (str(error) or "입력이 종료되었습니다."), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
