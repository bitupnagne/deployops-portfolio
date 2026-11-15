#!/usr/bin/env python3
"""Resolve Rocky Docker packages offline; erase only explicitly approved unused tools."""
from __future__ import print_function

import os
from pathlib import Path
import pwd
import re
import subprocess
import tempfile

ALLOWED_REMOVALS = frozenset(('runc', 'buildah', 'containers-common', 'podman',
                              'podman-catatonit', 'cockpit-podman'))
DOCKER_PACKAGES = ('docker-ce', 'docker-ce-cli', 'containerd.io',
                   'docker-buildx-plugin', 'docker-compose-plugin')


class ConflictRefused(Exception):
    pass


def package_record(package):
    return {'name': package.name, 'nevra': str(package)}


def removal_records(transaction):
    # Updates replace a package with the same name; these are not tool removals.
    installed_names = {p.name for p in transaction.install_set}
    records = sorted((package_record(p) for p in transaction.remove_set
                      if p.name not in installed_names), key=lambda p: p['nevra'])
    unknown = [p['name'] for p in records if p['name'] not in ALLOWED_REMOVALS]
    if unknown:
        raise ConflictRefused('Docker 설치 중 허용 범위 밖 패키지 제거가 필요합니다. 자동 변경하지 않습니다: ' + ', '.join(unknown))
    return records


def docker_specs(repository):
    rows = {}
    for line in (Path(repository) / 'PACKAGES.lock').read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        parts = line.split('|')
        if len(parts) != 5:
            raise ConflictRefused('Docker PACKAGES.lock 형식을 확인하세요.')
        name, version, arch = parts[:3]
        if name in DOCKER_PACKAGES:
            if name in rows or not re.fullmatch(r'[A-Za-z0-9.+:~_-]+', version) or not re.fullmatch(r'[A-Za-z0-9_+-]+', arch):
                raise ConflictRefused('Docker 패키지 버전 잠금이 올바르지 않습니다.')
            rows[name] = '{}-{}.{}'.format(name, version, arch)
    if set(rows) != set(DOCKER_PACKAGES):
        raise ConflictRefused('Docker 필수 패키지 버전 잠금이 누락됐습니다.')
    return [rows[name] for name in DOCKER_PACKAGES]


def make_base(dnf, temporary, repository=None):
    base = dnf.Base()
    base.conf.cachedir = str(Path(temporary) / 'cache')
    base.conf.persistdir = str(Path(temporary) / 'persist')
    base.conf.logdir = str(Path(temporary) / 'log')
    for directory in (base.conf.cachedir, base.conf.persistdir, base.conf.logdir):
        Path(directory).mkdir(mode=0o700, parents=True, exist_ok=True)
    base.conf.clean_requirements_on_remove = False
    base.conf.install_weak_deps = False
    base.conf.assumeyes = True
    # Never read /etc/yum.repos.d: the only available repository is local media.
    if repository is not None:
        repo = base.repos.add_new_repo('deploy-offline-docker', base.conf,
                                       baseurl=[Path(repository).as_uri()])
        repo.module_hotfixes = True
        repo.gpgcheck = False
        repo.repo_gpgcheck = False
        repo.skip_if_unavailable = False
    base.fill_sack(load_system_repo=True, load_available_repos=repository is not None)
    return base


def inspect(repository):
    try:
        import dnf
    except ImportError:
        raise ConflictRefused('Rocky DNF Python 모듈이 없습니다. OS의 python3-dnf/platform-python 구성을 확인하세요.')
    repository = Path(repository)
    if not repository.is_absolute() or not (repository / 'repodata/repomd.xml').is_file():
        raise ConflictRefused('Docker 오프라인 저장소 경로/메타데이터를 확인하세요.')
    with tempfile.TemporaryDirectory(prefix='deploy-docker-plan-') as temporary:
        try:
            with make_base(dnf, temporary, repository) as base:
                for spec in docker_specs(repository):
                    base.install(spec, strict=True)
                base.resolve(allow_erasing=True)
                removals = removal_records(base.transaction)
                return {'remove': removals,
                        'install': sorted(str(p) for p in base.transaction.install_set)}
        except ConflictRefused:
            raise
        except Exception as error:
            raise ConflictRefused('Docker 오프라인 의존성 검사 실패(변경 없음): ' + str(error))


def validate_storage_config(path, rootless=False):
    path = Path(path)
    if not path.exists() and not path.is_symlink():
        return
    if path.is_symlink() or rootless:
        raise ConflictRefused('사용자 지정 컨테이너 저장소 설정이 있습니다. 자동 제거하지 않습니다: ' + str(path))
    defaults = {'graphroot': '/var/lib/containers/storage',
                'runroot': '/run/containers/storage',
                'rootless_storage_path': '$HOME/.local/share/containers/storage'}
    for line in path.read_text(encoding='utf-8').splitlines():
        text = line.strip()
        if not text or text.startswith('#'):
            continue
        key, separator, value = text.partition('=')
        key = key.strip()
        if separator and key in defaults:
            value = value.strip().split('#', 1)[0].strip().strip('"\'')
            if value != defaults[key]:
                raise ConflictRefused('비표준 컨테이너 저장소 경로를 먼저 확인하세요. 자동 제거하지 않습니다: ' + str(path))


def storage_has_data(root):
    root = Path(root)
    if root.is_symlink():
        return True
    if not root.exists():
        return False
    if not root.is_dir():
        return True
    for child in root.iterdir():
        # Rocky's systemd-tmpfiles creates this empty directory at first boot.
        if child.name == 'tmp' and not child.is_symlink() and child.is_dir() and next(child.iterdir(), None) is None:
            continue
        return True
    return False


def ensure_unused():
    validate_storage_config('/etc/containers/storage.conf')
    for service in ('podman.service', 'podman.socket', 'crio.service', 'kubelet.service'):
        result = subprocess.run(['systemctl', 'is-active', '--quiet', service],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode == 0:
            raise ConflictRefused('컨테이너 관련 서비스가 사용 중입니다. 자동 제거하지 않습니다: ' + service)
        if result.returncode not in (3, 4):
            raise ConflictRefused('컨테이너 서비스 사용 여부를 확인하지 못했습니다: ' + service)
    processes = set()
    for process in Path('/proc').iterdir():
        if process.name.isdigit():
            try:
                processes.add((process / 'comm').read_text().strip())
            except FileNotFoundError:
                continue
            except OSError:
                raise ConflictRefused('실행 중인 컨테이너 프로세스를 확인하지 못했습니다.')
    active = {'podman', 'buildah', 'conmon', 'runc', 'crun', 'crio', 'kubelet'} & processes
    if active:
        raise ConflictRefused('컨테이너 프로세스가 실행 중입니다. 자동 제거하지 않습니다: ' + ', '.join(sorted(active)))
    roots = {Path('/var/lib/containers/storage'), Path('/run/containers/storage')}
    for account in pwd.getpwall():
        if account.pw_dir.startswith('/'):
            validate_storage_config(Path(account.pw_dir) / '.config/containers/storage.conf', rootless=True)
            roots.add(Path(account.pw_dir) / '.local/share/containers/storage')
    for root in roots:
        if storage_has_data(root):
            raise ConflictRefused('기존 Podman/Buildah 저장소가 있습니다. 데이터 확인 없이 제거하지 않습니다: ' + str(root))


def remove_approved(repository, approved):
    if not approved:
        return
    if any(item['name'] not in ALLOWED_REMOVALS for item in approved):
        raise ConflictRefused('허용되지 않은 패키지 제거 요청입니다.')
    ensure_unused()
    fresh = inspect(repository)
    if fresh['remove'] != approved:
        raise ConflictRefused('확인 이후 패키지 구성이 변경됐습니다. 제거 목록을 다시 확인하세요.')
    import dnf
    with tempfile.TemporaryDirectory(prefix='deploy-docker-remove-') as temporary:
        with make_base(dnf, temporary) as base:
            for item in approved:
                base.remove(item['nevra'])
            base.resolve()
            actual = removal_records(base.transaction)
            if actual != approved or list(base.transaction.install_set):
                raise ConflictRefused('제거 의존성이 승인 목록과 다릅니다. 패키지는 변경하지 않았습니다.')
            # This is the sole mutating operation; no --allowerasing or forced RPM removal.
            base.do_transaction()
