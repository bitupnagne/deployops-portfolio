#!/usr/libexec/platform-python
"""Auditable interactive front end for the existing Rocky offline installer.

Python 3.6 plus Rocky's installed DNF module. No remote SSH, shell evaluation, access-control changes,
or automatic acceptance of product terms. All installation uses existing tools.
"""
from __future__ import print_function

import argparse
import contextlib
import datetime
import getpass
import hashlib
import importlib.util
import ipaddress
import io
import json
import os
import re
import secrets
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import uuid
import zipfile
from pathlib import Path

VERSION = '1.0.0'
ENTRY = 'BOOT-INF/classes/application-install.yml'
ROLES = {
    'deploy': ('demo-deploy', '192.0.2.81', 'deploy', 'Docker · MariaDB · Nexus · Deploy'),
    'gitlab': ('demo-gitlab', '192.0.2.82', 'gitlab', 'Docker · GitLab · Runner'),
    'was': ('demo-was', '203.0.113.80', 'docker', 'Docker · WAS 에이전트'),
    'web': ('demo-web', '203.0.113.68', 'nginx', 'nginx · WEB 에이전트'),
}
CONTINUITY = (
    'spring.datasource.url', 'spring.datasource.username', 'spring.datasource.password',
    'app.crypto.secret-key', 'app.crypto.key-id', 'app.crypto.previous-keys',
    'app.sso.shared-secret', 'server.port', 'app.nginx.upload-dir')


LEGACY_STAGING_CHECK = r"""assert_trusted_staging_tree() {
  local tree="$1" label="$2" lexical="$1" cursor=/ part parent owner mode mode_bits entry
  [[ "$lexical" == /* ]] || { err "$label must be an absolute path for a real install"; return 1; }
  case "/$lexical/" in
    *"/../"*|*"/./"*) err "$label must not contain dot path segments"; return 1 ;;
  esac
  lexical="${lexical%/}"
  while [[ -n "$lexical" ]]; do
    part="${lexical%%/*}"
    if [[ -n "$part" ]]; then
      cursor="${cursor%/}/$part"
      [[ ! -L "$cursor" ]] || { err "$label path must not contain symlinks: $cursor"; return 1; }
    fi
    [[ "$lexical" == */* ]] || break
    lexical="${lexical#*/}"
  done
  [[ -d "$tree" ]] || { err "$label directory does not exist: $tree"; return 1; }
  cursor="$(readlink -f "$tree" 2>/dev/null || true)"
  [[ -n "$cursor" ]] || { err "$label cannot be resolved: $tree"; return 1; }

  parent="$cursor"
  while :; do
    owner="$(stat -c '%u' "$parent" 2>/dev/null || true)"
    mode="$(stat -c '%a' "$parent" 2>/dev/null || true)"
    [[ "$owner" == 0 && "$mode" =~ ^[0-7]{3,4}$ ]] \
      || { err "$label ancestors must be owned by root: $parent"; return 1; }
    mode_bits=$((8#$mode))
    if (( (mode_bits & 8#22) != 0 && (mode_bits & 8#1000) == 0 )); then
      err "$label ancestors must not be group/world writable (unless sticky): $parent"
      return 1
    fi
    [[ "$parent" != / ]] || break
    parent="$(dirname "$parent")"
  done

  if find -P "$cursor" -type l -print -quit | grep -q .; then
    err "$label must not contain symbolic links"
    return 1
  fi
  while IFS= read -r -d '' entry; do
    owner="$(stat -c '%u' "$entry" 2>/dev/null || true)"
    mode="$(stat -c '%a' "$entry" 2>/dev/null || true)"
    [[ "$owner" == 0 && "$mode" =~ ^[0-7]{3,4}$ ]] \
      || { err "$label contents must be owned by root: $entry"; return 1; }
    mode_bits=$((8#$mode))
    if (( (mode_bits & 8#22) != 0 )); then
      err "$label contents must not be group/world writable: $entry"
      return 1
    fi
  done < <(find -P "$cursor" -xdev \( -type f -o -type d \) -print0)
}
"""
FAST_STAGING_CHECK = r"""assert_trusted_staging_tree() {
  local tree="$1" label="$2" lexical="$1" cursor=/ part parent owner mode mode_bits entry
  [[ "$lexical" == /* ]] || { err "$label must be an absolute path for a real install"; return 1; }
  case "/$lexical/" in
    *"/../"*|*"/./"*) err "$label must not contain dot path segments"; return 1 ;;
  esac
  lexical="${lexical%/}"
  while [[ -n "$lexical" ]]; do
    part="${lexical%%/*}"
    if [[ -n "$part" ]]; then
      cursor="${cursor%/}/$part"
      [[ ! -L "$cursor" ]] || { err "$label path must not contain symlinks: $cursor"; return 1; }
    fi
    [[ "$lexical" == */* ]] || break
    lexical="${lexical#*/}"
  done
  [[ -d "$tree" ]] || { err "$label directory does not exist: $tree"; return 1; }
  cursor="$(readlink -f "$tree" 2>/dev/null || true)"
  [[ -n "$cursor" ]] || { err "$label cannot be resolved: $tree"; return 1; }

  parent="$cursor"
  while :; do
    owner="$(stat -c '%u' "$parent" 2>/dev/null || true)"
    mode="$(stat -c '%a' "$parent" 2>/dev/null || true)"
    [[ "$owner" == 0 && "$mode" =~ ^[0-7]{3,4}$ ]] \
      || { err "$label ancestors must be owned by root: $parent"; return 1; }
    mode_bits=$((8#$mode))
    if (( (mode_bits & 8#22) != 0 && (mode_bits & 8#1000) == 0 )); then
      err "$label ancestors must not be group/world writable (unless sticky): $parent"
      return 1
    fi
    [[ "$parent" != / ]] || break
    parent="$(dirname "$parent")"
  done

  # Keep the original scopes: links across mount boundaries, ownership/mode on
  # this filesystem. GNU find evaluates metadata without per-file stat forks.
  local unsafe
  if ! unsafe="$(find -P "$cursor" -type l -printf 'unsafe\n' -quit)"; then
    err "$label symbolic-link inspection failed"
    return 1
  fi
  if [[ -n "$unsafe" ]]; then
    err "$label must not contain symbolic links"
    return 1
  fi
  if ! unsafe="$(find -P "$cursor" -xdev \( -type f -o -type d \) \( ! -uid 0 -o -perm /022 \) -printf 'unsafe\n' -quit)"; then
    err "$label ownership/permission inspection failed"
    return 1
  fi
  if [[ -n "$unsafe" ]]; then
    err "$label contents must be root-owned and not group/world writable"
    return 1
  fi
}
"""


class Refused(Exception):
    pass


def validate_ip(value):
    try:
        parsed = ipaddress.IPv4Address(value)
    except (ValueError, TypeError):
        raise Refused('IPv4 주소를 입력하세요. 예: 192.0.2.81')
    if parsed.is_unspecified or parsed.is_multicast or parsed.is_loopback:
        raise Refused('서버에서 실제 사용하는 IPv4 주소를 입력하세요.')
    return str(parsed)


def validate_port(value):
    if not re.fullmatch(r'[0-9]{1,5}', str(value)) or not 1 <= int(value) <= 65535:
        raise Refused('포트는 1~65535의 숫자입니다.')
    return int(value)


def positive(value):
    if not re.fullmatch(r'[0-9]{1,12}', str(value)) or int(value) < 1:
        raise Refused('화면에서 발급받은 양수 ID를 입력하세요.')
    return int(value)


def scalar(value):
    value = str(value)
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise Refused('설정에는 줄바꿈과 제어문자를 넣을 수 없습니다.')
    return value


def encode_answer(value):
    """Match lib/ui.sh's DATA parser; this is deliberately not shell quoting."""
    value = scalar(value)
    if "'" not in value:
        return "'" + value + "'"
    if '"' not in value:
        return '"' + value + '"'
    raise Refused('현재 답변파일은 작은따옴표와 큰따옴표가 함께 있는 값을 지원하지 않습니다.')


def trusted_path(path, private=False):
    path = Path(os.path.abspath(str(path)))
    cursor = path
    first = True
    while True:
        info = os.lstat(str(cursor))
        if stat.S_ISLNK(info.st_mode):
            raise Refused('심볼릭 링크 경로를 사용할 수 없습니다: ' + str(cursor))
        if hasattr(os, 'geteuid'):
            if info.st_uid != 0 or info.st_mode & 0o022:
                raise Refused('root 소유이며 다른 계정이 쓸 수 없는 경로여야 합니다: ' + str(cursor))
            if first and private and info.st_mode & 0o077:
                raise Refused('비공개 설정은 root 전용 권한이어야 합니다: ' + str(cursor))
        first = False
        if cursor.parent == cursor:
            return path
        cursor = cursor.parent


def private_directory(path):
    path = Path(path)
    if not path.exists():
        if path.parent.exists():
            trusted_path(path.parent)
        else:
            private_directory(path.parent)
        os.mkdir(str(path), 0o700)
    trusted_path(path)
    if not path.is_dir():
        raise Refused('설정 경로가 폴더가 아닙니다.')
    if hasattr(os, 'geteuid') and path.stat().st_mode & 0o077:
        raise Refused('설정 폴더는 0700 권한이어야 합니다: ' + str(path))
    return path


def write_private(path, payload):
    path = Path(path)
    private_directory(path.parent)
    if path.exists() or path.is_symlink():
        trusted_path(path, private=True)
        if not path.is_file():
            raise Refused('설정 대상은 일반 파일이어야 합니다.')
    fd, temporary = tempfile.mkstemp(prefix='.install-', dir=str(path.parent))
    try:
        if hasattr(os, 'fchmod'):
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, str(path))
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_private(path):
    path = trusted_path(path, private=True)
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise Refused('비공개 설정 파일의 종류/크기가 올바르지 않습니다.')
    return json.loads(path.read_text(encoding='utf-8'))



def improve_legacy_staging_check(package):
    """Atomically replace only the exact shipped r6 check; unknown versions stay intact."""
    path = trusted_path(Path(package) / 'installer/install.sh')
    info = path.stat()
    if not path.is_file() or info.st_size > 2 * 1024 * 1024:
        raise Refused('설치 실행 파일의 종류/크기를 확인하세요.')
    original = path.read_bytes()
    legacy, fast = LEGACY_STAGING_CHECK.encode('utf-8'), FAST_STAGING_CHECK.encode('utf-8')
    if original.count(fast) == 1 and legacy not in original:
        return 'current'
    if original.count(legacy) != 1 or fast in original:
        return 'unknown'
    replacement = original.replace(legacy, fast, 1)
    fd, temporary = tempfile.mkstemp(prefix='.staging-check-', dir=str(path.parent))
    try:
        os.fchmod(fd, stat.S_IMODE(info.st_mode))
        with os.fdopen(fd, 'wb') as output:
            output.write(replacement)
            output.flush()
            os.fsync(output.fileno())
        current = os.lstat(str(path))
        if (current.st_dev, current.st_ino, current.st_mtime_ns, current.st_size) != (info.st_dev, info.st_ino, info.st_mtime_ns, info.st_size) or path.read_bytes() != original:
            raise Refused('검사 중 설치 파일이 바뀌었습니다. 다른 설치 실행이 끝난 후 재개하세요.')
        os.replace(temporary, str(path))
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return 'updated'


def machine_identity():
    machine = Path('/etc/machine-id')
    raw = machine.read_text().strip() if machine.is_file() else ''
    if not re.fullmatch(r'[a-fA-F0-9]{32}', raw):
        raise Refused('/etc/machine-id를 확인할 수 없습니다. OS 초기 구성을 먼저 확인하세요.')
    return {'machine_id': hashlib.sha256(raw.encode('ascii')).hexdigest(), 'hostname': socket.gethostname()}


def require_host():
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise Refused('설치는 root로 실행해야 합니다. 승인된 SSH 세션에서 root 권한으로 전환한 뒤 다시 실행하세요.')
    release = Path('/etc/os-release').read_text(encoding='utf-8')
    fields = dict(line.split('=', 1) for line in release.splitlines() if '=' in line)
    if fields.get('ID', '').strip('"') != 'rocky' or fields.get('VERSION_ID', '').strip('"') != '8.10':
        raise Refused('이 설치기는 Rocky Linux 8.10 전용입니다.')
    if os.uname().machine != 'x86_64':
        raise Refused('이 패키지는 x86_64 서버용입니다.')


def get(data, key):
    value = data
    for part in key.split('.'):
        value = value.get(part, {}) if isinstance(value, dict) else None
    return value


def validate_continuity(current, candidate):
    for key in CONTINUITY:
        if get(current, key) != get(candidate, key):
            raise Refused('기존 데이터 설정을 바꿀 수 없습니다: ' + key)


def company_config(v):
    return {
        'spring': {'datasource': {'url': 'jdbc:mariadb://127.0.0.1:3306/deployops?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul&restrictedAuth=mysql_native_password',
                                  'username': 'deployops', 'password': v['db_password']}, 'thymeleaf': {'cache': True}},
        'server': {'port': validate_port(v['deploy_port']), 'ssl': {'enabled': False}, 'servlet': {'session': {'cookie': {'secure': False}}}},
        'deploy': {'configuration': {'embedded': True}},
        'app': {'crypto': {'secret-key': v['crypto_key'], 'key-id': 'primary', 'previous-keys': ''},
                'sso': {'shared-secret': v['sso_secret'], 'base-url': 'http://{}:{}'.format(validate_ip(v['server_ip']), v['deploy_port'])},
                'bootstrap': {'admin-password': v['admin_password'], 'sample-data-enabled': False, 'image-transfer-mode': 'push'},
                'nginx': {'upload-dir': '/opt/deploy-system/data/nginx-uploads'}}}


def build_answers(role, v, state_dir):
    a = {'FORCE_REINSTALL': 'n', 'DOCKER_GROUP_USER': ''}
    if role in ('deploy', 'gitlab'):
        registry_ip = v['server_ip'] if role == 'deploy' else v['nexus_ip']
        a.update(DOCKER_SET_INSECURE_REGISTRY='y', DOCKER_INSECURE_REGISTRY='{}:{}'.format(validate_ip(registry_ip), validate_port(v['registry_port'])))
    if role == 'deploy':
        a.update(MARIADB_BIND_ADDRESS='127.0.0.1', MARIADB_ROOT_PASSWORD=v['db_root_password'],
                 NEXUS_WEB_PORT=validate_port(v['nexus_port']), NEXUS_REGISTRY_PORT=validate_port(v['registry_port']),
                 NEXUS_HEAP='1g', NEXUS_SECRETS_KEY_FILE='/etc/deployops/nexus-secrets/secrets.json',
                 NEXUS_AUTO_SETUP='y', NEXUS_REPAIR_DRIFT='n', NEXUS_ADMIN_PASSWORD=v['nexus_password'],
                 NEXUS_DOCKER_REPO_NAME='docker-hosted', NEXUS_MAVEN_SETUP='y', NEXUS_MAVEN_REPAIR_DRIFT='n',
                 DEPLOYOPS_HEAP='2g', DEPLOYOPS_CREATE_DB='y', DEPLOY_TLS_KEYSTORE_FILE='', DEPLOY_TLS_CA_FILE='',
                 DEPLOY_HEALTHCHECK_URL='http://127.0.0.1:{}/health'.format(v['deploy_port']), DEPLOYOPS_MAILBOX_DIR='')
    elif role == 'gitlab':
        url = 'http://{}:{}'.format(validate_ip(v['server_ip']), validate_port(v['gitlab_port']))
        a.update(GITLAB_HTTP_PORT=v['gitlab_port'], GITLAB_SSH_PORT=validate_port(v['git_ssh_port']), GITLAB_EXTERNAL_URL=url,
                 GITLAB_PUMA_WORKERS=2, GITLAB_SIDEKIQ_CONCURRENCY=5, GITLAB_PROMETHEUS='n',
                 GITLAB_RUNNER_REGISTER='y' if v.get('runner_token') else 'n', GITLAB_RUNNER_URL=url,
                 GITLAB_RUNNER_CLONE_URL='', GITLAB_RUNNER_API_TOKEN='', GITLAB_RUNNER_TYPE='project_type',
                 GITLAB_RUNNER_PROJECT_ID=v.get('project_id', ''), GITLAB_RUNNER_GROUP_ID='',
                 GITLAB_RUNNER_TOKEN=v.get('runner_token', ''), GITLAB_RUNNER_ID=v.get('runner_id', ''),
                 GITLAB_RUNNER_VERIFY_API_TOKEN=v.get('runner_pat', ''), GITLAB_RUNNER_NAME=v['server_name'] + '-offline-runner',
                 GITLAB_RUNNER_DOCKER_IMAGE='docker:29.7.2', GITLAB_RUNNER_DOCKER_NETWORK='',
                 GITLAB_RUNNER_TAG_LIST='docker,offline', GITLAB_RUNNER_RUN_UNTAGGED='n', GITLAB_RUNNER_PROTECTED='y', GITLAB_RUNNER_LOCKED='y')
    elif role in ('was', 'web'):
        a.update(DEPLOY_AGENT_TRANSPORT='agent', DEPLOY_AGENT_CONTROL_URL='http://{}:{}'.format(validate_ip(v['control_ip']), validate_port(v['control_port'])),
                 DEPLOY_AGENT_SERVER_ID=positive(v['server_id']), DEPLOY_AGENT_ENROLLMENT_CODE_FILE=str(Path(state_dir) / 'enrollment-code'),
                 DEPLOY_AGENT_CA_FILE='', DEPLOY_AGENT_ALLOW_HTTP='y', DEPLOY_AGENT_TYPE=role, DEPLOY_AGENT_USER='deploy',
                 DEPLOY_AGENT_AUTHORIZED_KEY_FILE='', DEPLOY_AGENT_HEALTH_PORT=validate_port(v['health_port']),
                 DEPLOY_AGENT_HEALTH_PATH=v['health_path'], DEPLOY_AGENT_REGISTRY_URL='', DEPLOY_AGENT_REGISTRY_USERNAME='', DEPLOY_AGENT_REGISTRY_PASSWORD='',
                 DOCKER_SET_INSECURE_REGISTRY='n', DOCKER_INSECURE_REGISTRY='')
        if role == 'web':
            a.update(NGINX_UPSTREAM_HOST=validate_ip(v['was_ip']), NGINX_MAX_BODY='600m', NGINX_PROXY_OFFICE='n', NGINX_PROXY_DEPLOY='n',
                     NGINX_PROXY_GITLAB='n', NGINX_PROXY_NEXUS_WEB='n', NGINX_PROXY_NEXUS_REGISTRY='n', NGINX_STATIC_ROOT='/var/www/app',
                     NGINX_STATIC_LISTEN=validate_port(v['web_port']),
                     NGINX_CUSTOM_SERVICES='업무앱|{}|{}|{}|no'.format(validate_port(v['proxy_port']), validate_port(v['was_port']), v['was_ip']))
    else:
        raise Refused('알 수 없는 서버 역할입니다.')
    for key, value in a.items():
        encode_answer(value)
    return a


UPGRADE_STATE = Path('/var/lib/deploy-installer/upgrades')
ROLE_COMPONENTS = {'deploy': 'docker,mariadb,nexus,deploy-system', 'gitlab': 'docker,gitlab,gitlab-runner',
                   'was': 'docker,deploy-agent', 'web': 'nginx,deploy-agent'}


def upgrade_lock_file(directory):
    private_directory(directory)
    path = directory / 'maintenance.lock'
    fd = os.open(str(path), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_nlink != 1:
            raise Refused('고도화 잠금 파일 소유자나 권한이 올바르지 않습니다.')
        return os.fdopen(fd, 'r+')
    except BaseException:
        os.close(fd)
        raise


def check_baseline(components, directory, package=None):
    verified = set()
    selected = set(components.split(','))
    for entry in sorted(directory.glob('*.json')):
        record = read_private(entry)
        op = record.get('operation', {})
        component = record.get('component') or op.get('component')
        phase = record.get('phase')
        if component not in ('docker','mariadb','nginx','nexus','deploy','gitlab','runner') or phase not in ('done','applying','verify'):
            raise Refused('고도화 진행 기록을 확인할 수 없습니다. 10번에서 기록을 확인하세요.')
        key = {'runner':'gitlab-runner', 'deploy':'deploy-system'}.get(component, component)
        if phase != 'done':
            raise Refused('{} 고도화가 진행 중입니다. 10번 → resume로 먼저 완료하세요.'.format(component))
        if key not in selected and 'all' not in selected:
            continue
        if package is None:
            raise Refused('고도화된 {} 상태 검사에 필요한 반입본을 확인하세요. 10번에서 재개할 수 있습니다.'.format(component))
        helper = trusted_path(Path(package) / 'installer/component_upgrade.py')
        spec = importlib.util.spec_from_file_location('component_upgrade', str(helper))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        try:
            if module.verify_baseline_skip(record) is not True:
                raise Refused('고도화 상태를 확인할 수 없습니다. 메뉴 10에서 확인하세요.')
        except module.Refused as error:
            raise Refused('{} 고도화 상태가 현재 서비스와 다릅니다. 10번에서 확인하세요: {}'.format(component, error))
        expected_names = {'nexus':'worksystem-nexus','gitlab':'worksystem-gitlab','runner':'worksystem-gitlab-runner'}
        if component in expected_names and op.get('name') != expected_names[component]:
            raise Refused('{} 고도화 대상 이름이 이 역할의 기본 설치 대상과 다릅니다. 10번에서 별도 서비스 구성을 확인하세요.'.format(component))
        verified.add(key)
    return verified



@contextlib.contextmanager
def baseline_guard(components, package=None):
    import fcntl
    with upgrade_lock_file(UPGRADE_STATE) as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refused('다른 고도화 작업이 실행 중입니다. 종료 후 10번에서 확인하세요.')
        yield check_baseline(components, UPGRADE_STATE, package)


class Console:
    def say(self, text=''):
        print(text, flush=True)

    def ask(self, label, default='', validator=None):
        while True:
            suffix = ' [{}]'.format(default) if default != '' else ''
            value = input(label + suffix + ': ').strip()
            value = str(default) if value == '' else value
            try:
                scalar(value)
                return validator(value) if validator else value
            except Refused as error:
                self.say(str(error))

    def secret(self, label, previous='', minimum=1):
        while True:
            value = getpass.getpass(label + (' [Enter: 기존 값 유지]' if previous else '') + ': ', stream=sys.stderr)
            if value == '' and previous:
                return previous
            try:
                if len(value) < minimum:
                    raise Refused('{}자 이상 입력하세요.'.format(minimum))
                encode_answer(value)
                return value
            except Refused as error:
                self.say(str(error))

    def confirm(self, label):
        return self.ask(label + ' (y/N)', 'n').lower() == 'y'


class Wizard:
    def __init__(self, package_root, state_base='/root/deploy-installer', console=None):
        self.package = Path(package_root).absolute()
        self.base = Path(state_base)
        self.io = console or Console()
        self.state = None
        self.directory = None
        self.identity = None

    def save(self):
        self.state['updated_at'] = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ')
        write_private(self.directory / 'state.json', (json.dumps(self.state, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))

    def load(self, role):
        self.directory = self.base / role
        state_file = self.directory / 'state.json'
        if not state_file.exists():
            return None
        state = read_private(state_file)
        if state.get('version') != VERSION or state.get('role') != role or state.get('identity') != self.identity:
            raise Refused('다른 서버/역할에서 만든 설정입니다. 기존 설정을 자동 재사용하지 않습니다.')
        if state.get('package') != str(self.package):
            index_path = Path('/var/lib/deploy-installer/installation.json')
            if (not index_path.is_file() or read_private(index_path).get('package') != str(self.package) or
                    not self.io.confirm('새 설치 재료에 기존 역할의 설정과 진행 상태를 연결합니까?')):
                raise Refused('기존 저장 설정은 보존했습니다. 새 설치 재료 연결이 필요합니다.')
            state['previous_package'] = state['package']
            state['package'] = str(self.package)
            self.state = state
            self.save()
        self.state = state
        return state

    def collect(self, role, old=None):
        old = old or {}
        name, ip, user, description = ROLES[role]
        self.io.say('\n역할: {} / SFTP 반입 계정: {} / 설치 계정: root'.format(description, user))
        self.io.say('입력한 이름/IP는 설치·연동 설정입니다. OS 이름/IP/방화벽을 자동 변경하지 않습니다.')
        def ask(key, label, default='', validator=None):
            v[key] = self.io.ask(label, old.get(key, default), validator)
        v = dict(old)
        ask('server_name', '이 서버의 이름', name, lambda x: x if re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,62}', x) else invalid('서버 이름 형식을 확인하세요.'))
        ask('server_ip', '이 서버의 IPv4', ip, validate_ip)
        self.io.say('내부 IP + HTTP 방식입니다. TLS/CA는 사용하지 않으며 인증 정보는 평문 전송됩니다.')
        if not self.io.confirm('회사에서 허용한 HTTP 내부 통신 구성으로 진행합니까?'):
            raise Refused('HTTP 설치 선택을 취소했습니다. HTTPS 구성은 기존 고급 설치 절차를 사용하세요.')
        if role == 'deploy':
            ask('deploy_port', 'Deploy 웹 포트', 9090, validate_port)
            ask('nexus_port', 'Nexus 웹 포트', 8081, validate_port)
            ask('registry_port', 'Nexus 이미지 Registry 포트', 5000, validate_port)
            if len({v['deploy_port'], v['nexus_port'], v['registry_port'], 3306}) != 4:
                raise Refused('Deploy/Nexus/Registry/DB 포트가 겹칩니다.')
            for key, label in [('db_root_password', 'MariaDB root 암호'), ('db_password', 'Deploy DB 접속 암호'), ('nexus_password', 'Nexus admin 암호'), ('admin_password', 'Deploy 웹 admin 초기 암호')]:
                v[key] = self.io.secret(label, old.get(key, ''), 12)
            v['crypto_key'] = old.get('crypto_key') or secrets.token_hex(32)
            v['sso_secret'] = old.get('sso_secret') or secrets.token_hex(32)
            self.io.say('신규 DB용 암호화 키와 SSO 공유값은 생성 후 비공개 설정에 보관합니다. 재개 시 그대로 유지합니다.')
            if self.io.confirm('기존 Worksystem과 SSO 연동할 공유값을 지금 입력합니까?'):
                v['sso_secret'] = self.io.secret('기존 Worksystem과 같은 SSO 공유값', old.get('sso_secret', ''), 12)
        elif role == 'gitlab':
            ask('gitlab_port', 'GitLab 웹 포트', 8929, validate_port)
            v['git_ssh_port'] = 2222  # Legacy settings key retained; HTTP Git is used.
            ask('nexus_ip', 'demo-deploy/Nexus IPv4', '192.0.2.81', validate_ip)
            ask('nexus_port', 'Nexus 웹 포트', 8081, validate_port)
            ask('registry_port', 'Nexus Registry 포트', 5000, validate_port)
            if v['gitlab_port'] == v['git_ssh_port']:
                raise Refused('GitLab 웹 포트와 Git SSH 포트가 겹칩니다.')
        else:
            ask('control_ip', 'Deploy 관리 서버 IPv4', '192.0.2.81', validate_ip)
            ask('control_port', 'Deploy 관리 포트', 9090, validate_port)
            self.io.say('먼저 Deploy 웹에서 이 서버를 등록하고 에이전트 등록 코드를 발급하세요. 서버 ID는 자동 추측하지 않습니다.')
            ask('server_id', 'Deploy 화면의 이 서버 ID', '', positive)
            if not Path('/etc/deploy-system/agent.json').exists():
                v['enrollment_code'] = self.io.secret('이 서버의 에이전트 등록 코드', old.get('enrollment_code', ''))
            ask('health_port', '기본 업무 health 포트', 8080 if role == 'was' else 80, validate_port)
            ask('health_path', '기본 업무 health 경로', '/actuator/health' if role == 'was' else '/', lambda x: x if x.startswith('/') and not any(c.isspace() for c in x) else invalid('/로 시작하는 경로를 입력하세요.'))
            if role == 'web':
                ask('was_ip', '리버스 프록시가 연결할 WAS IPv4', '203.0.113.80', validate_ip)
                ask('was_port', 'WAS 업무 호스트 포트', 8080, validate_port)
                ask('web_port', '정적 WEB 수신 포트', 80, validate_port)
                ask('proxy_port', '업무 프록시 수신 포트', 8080, validate_port)
                if v['web_port'] == v['proxy_port']:
                    raise Refused('초기 정적 사이트와 프록시는 다른 포트가 필요합니다. 같은 포트의 /api 분기는 설치 후 nginx UI에서 설정하세요.')
        build_answers(role, v, self.directory)
        if role == 'deploy' and old and (Path('/opt/deploy-system/app/deploy-ops.jar').exists() or self.state.get('installation_started') or self.state.get('stages', {}).get('infrastructure')):
            validate_continuity(company_config(old), company_config(v))
            if old.get('db_root_password') != v.get('db_root_password'):
                raise Refused('DB 설치를 시작한 뒤에는 기존 root 암호를 유지해야 합니다. 암호 변경은 별도 변경 절차로 진행하세요.')
        return v

    def replace_values(self, values):
        if values != self.state['values']:
            self.state['stages'] = {}
            self.state.pop('last_return_code', None)
            self.state['values'] = values
            self.io.say('설정이 변경되어 이전 검사 성공 상태를 초기화했습니다. 1번 설치/재개로 다시 확인하세요.')
        self.save()

    def prepare_answers(self, overrides=None):
        answers = build_answers(self.state['role'], self.state['values'], self.directory)
        answers.update(overrides or {})
        data = ''.join('{}={}\n'.format(k, encode_answer(v)) for k, v in sorted(answers.items()))
        path = self.directory / 'answers.env'
        write_private(path, data.encode('utf-8'))
        code = self.state['values'].get('enrollment_code')
        if code:
            write_private(self.directory / 'enrollment-code', (code + '\n').encode('utf-8'))
        return path

    def run_commands(self, commands, stage):
        self.state['stages'][stage] = 'RUNNING'
        self.save()
        for command in commands:
            try:
                result = subprocess.run([str(x) for x in command], cwd=str(self.package), env=clean_environment())
            except OSError:
                self.state['stages'][stage] = 'FAILED'
                self.state['last_return_code'] = 127
                self.save()
                raise
            if result.returncode != 0:
                self.state['stages'][stage] = 'WAITING_NEXUS_WEB' if result.returncode == 20 else 'FAILED'
                self.state['last_return_code'] = result.returncode
                self.save()
                self.io.say('단계가 끝나지 않았습니다: {} / 반환값 {}'.format(stage, result.returncode))
                return result.returncode
        self.state['stages'][stage] = 'SUCCEEDED'
        self.state['last_return_code'] = 0
        self.save()
        return 0

    def docker_preflight(self):
        name = 'deploy_docker_conflicts'
        helper = sys.modules.get(name)
        if helper is None:
            helper_path = self.package / 'installer/lib/docker-conflicts.py'
            trusted_path(helper_path)
            spec = importlib.util.spec_from_file_location(name, str(helper_path))
            helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(helper)
        repository = self.package / 'installer/bundles/rocky8/docker'
        self.io.say('Docker 오프라인 설치 의존성과 기존 컨테이너 패키지를 검사합니다. 인터넷 저장소는 사용하지 않습니다.')
        try:
            plan = helper.inspect(repository)
            if not plan['remove']:
                self.io.say('Docker 의존성 검사 통과: 제거할 기존 패키지가 없습니다.')
                return
            helper.ensure_unused()
            self.io.say('Docker와 충돌하여 제거가 필요한 패키지:')
            for package in plan['remove']:
                self.io.say('  - ' + package['nevra'])
            self.io.say('이 패키지는 Podman/Buildah 계열 도구입니다. rootless 사용자와 별도 저장소를 포함하여 사용 중이면 취소하세요.')
            if not self.io.confirm('이 서버에서 위 도구를 사용하지 않음을 확인했고, 표시된 패키지를 제거한 뒤 Docker를 설치합니까?'):
                raise Refused('Docker 충돌 패키지 제거를 취소했습니다. 패키지는 변경하지 않았습니다.')
            helper.remove_approved(repository, plan['remove'])
            after = helper.inspect(repository)
            if after['remove']:
                raise Refused('충돌 정리 후 제거 대상이 남았습니다. 자동 설치를 중단합니다.')
            self.io.say('승인한 충돌 패키지 정리가 끝났습니다. 기존 설치 설정으로 계속 진행합니다.')
        except helper.ConflictRefused as error:
            raise Refused(str(error))

    def lifecycle(self):
        path = trusted_path(self.package / 'installer/interactive_lifecycle.py')
        spec = importlib.util.spec_from_file_location('interactive_lifecycle', str(path))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def backup_menu(self):
        if self.state['role'] != 'deploy':
            self.io.say('Nexus 준비 및 Deploy 백업은 demo-deploy의 root 설치 메뉴에서 실행하세요.')
            return
        self.io.say('1. Nexus 백업·격리복원 준비\n2. Deploy 일관 백업 생성\n0. 돌아가기')
        action = self.io.ask('선택', '0')
        if action == '0':
            return
        if action not in ('1', '2'):
            raise Refused('0~2 중 하나를 선택하세요.')
        with baseline_guard(''):
            if action == '1':
                path = trusted_path(self.package / 'installer/lib/nexus-readiness-setup.py')
                spec = importlib.util.spec_from_file_location('nexus_readiness_setup', str(path))
                helper = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(helper)
                try:
                    changed = helper.enable(self, trusted_path)
                except helper.SetupError as error:
                    raise Refused(str(error))
                except (ValueError, OSError):
                    raise Refused('Nexus 준비 검사가 통과하지 못했습니다. 설치된 broker/설정 권한, 실행 중인 작업, 기본 H2·볼륨·키·건강 상태를 확인하세요. 준비 완료로 표시하지 않습니다.')
                if changed:
                    result = subprocess.run(['systemctl', 'start', '--no-block', 'deployops-nexus-backup.service'], env=clean_environment())
                    if result.returncode:
                        raise Refused('설정은 저장됐지만 broker 측정 시작에 실패했습니다. 서비스 상태를 확인하세요.')
                    self.io.say('설정 저장 완료. 실제 broker가 준비 상태를 다시 측정합니다. Deploy 웹의 Nexus 운영에서 새로고침하여 ready/restoreReady와 실패 사유를 확인하세요. 복구 검증 완료를 뜻하지 않습니다.')
            else:
                self.io.say('Deploy DB·JAR·설정·키·전송 상태를 함께 백업합니다. 앱을 잠시 중단하고 완료 후 원래 실행 상태로 복구합니다. Nexus 데이터 자체는 별도 웹 백업 대상입니다.')
                if not self.io.confirm('신규 작업 유입을 막았고 진행 중인 배포·CI·백업·복원 작업이 없으며, 일시 중단 후 백업합니까?'):
                    return
                script = trusted_path(Path('/usr/local/deploy/bin/deployops-backup.sh'))
                result = subprocess.run(['bash', str(script)], env=clean_environment())
                if result.returncode:
                    raise Refused('Deploy 백업이 완료되지 않았습니다. 위 로그와 앱 복구 상태를 확인하세요. 불완전 백업은 고도화에 사용하지 마세요.')
                self.io.say('백업이 완료됐습니다. 위 Complete backup 경로를 보관하고 메뉴 10의 기존 제품 백업 경로에 입력하세요.')

    def upgrade_menu(self):
        options = {'deploy': ('docker', 'mariadb', 'nexus', 'deploy'), 'gitlab': ('docker', 'gitlab', 'runner'),
                   'was': ('docker',), 'web': ('nginx',)}
        choices = options[self.state['role']]
        self.io.say('고도화 대상: ' + ', '.join(choices))
        component = self.io.ask('제품 이름 (0: 돌아가기)', '0')
        if component == '0':
            return
        if component not in choices:
            raise Refused('이 서버 역할에 포함된 제품을 선택하세요.')
        if component == 'deploy':
            self.io.say('복구 전용: 완료된 Nexus 백업의 읽기 전용 조회만 미확정인 경우 recovery-query를 선택할 수 있습니다. 일반 강제 패치가 아니며 DB 상태를 변경하지 않습니다.')
        operation = self.io.ask('plan 계획 / backup 백업 / apply 적용 / resume 재개', 'plan')
        if component == 'deploy' and operation == 'recovery-query':
            task = self.io.ask('미확정 읽기 전용 조회 UUID')
            candidate = self.io.ask('기존 회사 설정을 그대로 포함한 복구 JAR 절대 경로')
            if not self.io.confirm('진행 중인 CI·배포·외부 쓰기를 중단했으며, 보호 백업 후 앱만 잠시 재시작하고 미확정 DB 상태는 유지함을 확인합니까?'):
                return
            entry = trusted_path(self.package / 'installer/deploy-system/patch-deployops.sh')
            for helper in ('recover-readonly-patch.py', 'maintenance.py'):
                trusted_path(entry.parent / helper)
            result = subprocess.run(['bash', str(entry), '--recover-nexus-query', task, candidate,
                                     '--acknowledge-read-only-recovery'], cwd=str(self.package), env=clean_environment())
            if result.returncode:
                raise Refused('복구 전용 패치 조건을 충족하지 못했습니다. 기존 상태와 보호 백업을 확인하세요.')
            self.io.say('에이전트 화면에서 읽기 전용 조회를 명시적으로 재개하고 기존 백업 결과를 새로 조회하세요.')
            return
        if operation not in ('plan', 'backup', 'apply', 'resume'):
            raise Refused('plan, backup, apply, resume 중 하나를 선택하세요.')
        cli = trusted_path(self.package / 'installer/component_upgrade.py')
        args = [sys.executable, str(cli), operation, '--component', component]
        names = {'gitlab': 'worksystem-gitlab', 'runner': 'worksystem-gitlab-runner', 'nexus': 'worksystem-nexus'}
        if component in names:
            args.extend(['--container', self.io.ask('이 서버의 실제 컨테이너 이름', names[component])])
        if operation != 'backup':
            args.extend(['--target', self.io.ask('목표 버전 (Deploy JAR은 64자리 SHA-256)'),
                         '--media', self.io.ask('반입 RPM·이미지 TAR·JAR 또는 RPM 저장소 절대 경로')])
            if component == 'runner':
                args.extend(['--helper-media', self.io.ask('Runner Helper 이미지 TAR 절대 경로')])
        if operation != 'plan':
            args.extend(['--backup', self.io.ask('백업 디렉터리 절대 경로 (Nexus·Deploy는 기존 제품 백업)')])
            if not self.io.confirm('해당 호스트의 신규 작업 유입과 기존 작업·쓰기를 모두 정리했습니까?'):
                raise Refused('작업 정리 후 고도화를 재개하세요.')
            args.append('--drained')
        result = subprocess.run(args, cwd=str(self.package), env=clean_environment())
        if result.returncode:
            raise Refused('고도화 단계가 끝나지 않았습니다. 위 메시지와 고도화 상태를 확인하세요.')

    def component(self, components, stage, overrides=None):
        with baseline_guard(components, self.package) as verified:
            for key in sorted(verified):
                self.io.say('{}: 고도화 버전과 실제 서비스가 정상입니다. 현재 버전을 유지합니다.'.format(key))
                write_private(self.directory / ('component-' + key + '.status'), b'VERIFIED\n')
            remaining = [key for key in components.split(',') if key not in verified]
            if not remaining:
                self.state['stages'][stage] = 'SUCCEEDED'
                self.save()
                return 0
            return self.install_components(','.join(remaining), stage, overrides)

    def install_components(self, components, stage, overrides):
        compatibility = improve_legacy_staging_check(self.package)
        if compatibility == 'updated':
            self.io.say('r6 설치 파일의 동일 권한 검사를 빠른 방식으로 갱신했습니다.')
        elif compatibility == 'unknown':
            self.io.say('알 수 없는 설치 파일 버전은 변경하지 않습니다. 기존 권한 검사로 진행합니다.')
        answers = self.prepare_answers(overrides)
        self.state['installation_started'] = True
        base = ['bash', self.package / 'installer/install.sh', '--answers', answers, '--components', components]
        self.state['stages'][stage] = 'PENDING'
        self.save()
        result = self.run_commands([base + ['--validate']], stage + '-preflight')
        if result:
            self.state['stages'][stage] = 'FAILED'
            self.save()
            return result
        if 'docker' in components.split(','):
            try:
                self.docker_preflight()
            except (Refused, OSError, ValueError, RuntimeError):
                self.state['stages'][stage] = 'FAILED'
                self.state['last_return_code'] = 1
                self.save()
                raise
        lifecycle = self.lifecycle()
        try:
            lifecycle.before_install(self)
            try:
                return self.run_commands([base + ['--component-state', str(self.directory), '--unattended']], stage)
            finally:
                lifecycle.after_install(self)
        except lifecycle.LifecycleError as error:
            raise Refused(str(error))

    def configure_company_jar(self):
        v = self.state['values']
        candidate = company_config(v)
        installed = Path('/opt/deploy-system/app/deploy-ops.jar')
        if installed.exists():
            info = os.lstat(str(installed))
            if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or info.st_mode & 0o022:
                raise Refused('기존 Deploy JAR의 파일 종류/권한을 확인하세요.')
            with zipfile.ZipFile(str(installed)) as archive:
                validate_continuity(json.loads(archive.read(ENTRY).decode('utf-8-sig')), candidate)
        helper = self.package / 'operations/configure-deploy-jar.py'
        for required in [helper, helper.parent / 'jar_configuration.py', self.package / 'installer/deploy-system/embedded-config.py']:
            trusted_path(required)
        configuration = self.directory / 'application-install.yml'
        write_private(configuration, (json.dumps(candidate, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
        bundle = self.package / 'installer/bundles/common/deploy-system/deploy-ops.jar'
        trusted_path(bundle)
        output = self.directory / ('deploy-company-' + uuid.uuid4().hex + '.jar')
        commands = [[sys.executable, helper, '--template-jar', bundle, '--configuration', configuration, '--output', output],
                    [sys.executable, self.package / 'installer/deploy-system/embedded-config.py', 'validate', output]]
        try:
            prepared = self.run_commands(commands, 'company-jar') == 0
        except (OSError, KeyboardInterrupt):
            if output.exists():
                output.unlink()
            raise
        if not prepared:
            if output.exists():
                output.unlink()
            return False
        # Publish only the validated candidate. Preserve the original package JAR once.
        original = self.directory / 'package-original.jar'
        if not original.exists():
            with open(str(bundle), 'rb') as incoming:
                fd = os.open(str(original), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, 'wb') as outgoing:
                    shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
        fd, temporary = tempfile.mkstemp(prefix='.company-', dir=str(bundle.parent))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, 'wb') as outgoing, open(str(output), 'rb') as incoming:
                shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
                outgoing.flush()
                os.fsync(outgoing.fileno())
            os.replace(temporary, str(bundle))
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
            if output.exists():
                output.unlink()
        self.state['company_jar_prepared'] = True
        self.save()
        return True

    def agent_identity_matches(self):
        path = Path('/etc/deploy-system/agent.json')
        if not path.exists():
            return False
        config = read_private(path)
        v = self.state['values']
        expected = 'http://{}:{}'.format(v['control_ip'], v['control_port'])
        if config.get('url', '').rstrip('/') != expected or str(config.get('serverId')) != str(v['server_id']) or config.get('role', '').upper() != self.state['role'].upper():
            raise Refused('이미 등록된 에이전트의 주소/ID/역할이 입력값과 다릅니다. 기존 인증 정보를 바꾸지 않았습니다.')
        machine = Path('/etc/machine-id').read_text().strip().lower()
        if config.get('machineId') != machine:
            raise Refused('기존 에이전트의 서버 식별값이 현재 OS와 다릅니다. 복제 서버의 인증을 재사용하지 않습니다.')
        return True

    def execute(self):
        with baseline_guard(ROLE_COMPONENTS[self.state['role']], self.package) as verified:
            return self.execute_role(verified)

    def execute_role(self, verified):
        role = self.state['role']
        v = self.state['values']
        if role == 'deploy':
            if 'deploy-system' not in verified and not self.configure_company_jar():
                return 1
            result = self.component('docker,mariadb,nexus,deploy-system', 'infrastructure')
            if result:
                if result == 20:
                    self.io.say('Nexus 웹: http://{}:{} · 약관/초기 암호 처리 후 1번으로 재개하세요.'.format(v['server_ip'], v['nexus_port']))
                    self.io.say('Maven 저장소 정책 차이 오류라면 7번에서 변경 내용을 확인합니다. 기존 데이터는 지우지 않습니다.')
                else:
                    self.io.say('위 로그의 "무인 설치 실패" 항목을 확인하세요. Docker/DB 등 실패 원인을 해결한 뒤 1번으로 재개합니다. Nexus 웹 작업이 필요한 상태로 판정하지 않았습니다.')
                return result
        elif role == 'gitlab':
            if self.component('docker,gitlab', 'gitlab'):
                return 1
            if 'gitlab-runner' not in verified and not v.get('runner_token'):
                self.io.say('GitLab 웹: http://{}:{}'.format(v['server_ip'], v['gitlab_port']))
                self.io.say('웹에서 프로젝트와 Project Runner를 만드세요. tags=docker,offline / Run untagged 끔 / Protected 켬 / Lock 켬.')
                self.io.say('glrt- 토큰, Runner ID, Project ID와 manage_runner + read_api 권한 PAT를 준비하세요.')
                if not self.io.confirm('웹 작업을 끝냈으며 Runner 정보를 입력합니까?'):
                    self.state['stages']['runner'] = 'WAITING_GITLAB_WEB'
                    self.save()
                    return 20
                self.runner_questions()
            if self.component('gitlab-runner', 'runner'):
                return 1
        else:
            registered = self.agent_identity_matches()
            if registered:
                self.io.say('등록된 에이전트 인증으로 상태를 검사합니다.')
                result = self.check_health()
                if not result or not self.io.confirm('현재 등록을 유지하고 중단된 구성요소를 다시 검사·설치합니까?'):
                    return result
            if not registered and not v.get('enrollment_code'):
                raise Refused('신규 설치에는 이 서버의 등록 코드가 필요합니다. 2번 입력 수정에서 입력하세요.')
            if self.component('docker,deploy-agent' if role == 'was' else 'nginx,deploy-agent', 'target'):
                return 1
            self.io.say('Deploy 웹에서 이 서버의 에이전트 등록을 승인한 뒤 연결·준비 검사를 실행하세요. 업무 health는 실제 배포 후 확인합니다.')
        if role in ('deploy', 'gitlab'):
            self.io.say('호스트 제어·로그·백업 연동에는 8번 인프라 에이전트 등록을 완료하세요.')
        self.io.say('선택한 프로그램 설치 단계가 성공했습니다. 웹 연동·CI·업무 배포는 별도 확인 대상입니다.')
        return self.check_health()

    def enroll_infrastructure(self):
        role = self.state['role']
        if role not in ('deploy', 'gitlab'):
            raise Refused('WAS·WEB은 설치/재개 메뉴에서 에이전트를 등록합니다.')
        v = self.state['values']
        self.io.say('Deploy 웹에서 이 서버를 DEPLOY 또는 GITLAB 역할로 등록하고 일회성 코드를 발급하세요.')
        v['control_ip'] = validate_ip(self.io.ask('Deploy IPv4', v.get('control_ip', '192.0.2.81')))
        v['control_port'] = validate_port(self.io.ask('Deploy 포트', str(v.get('control_port', 9090))))
        v['server_id'] = positive(self.io.ask('Deploy 화면의 서버 ID', str(v.get('server_id', ''))))
        if Path('/etc/deploy-system/agent.json').exists():
            self.agent_identity_matches()
            self.io.say('등록된 에이전트 상태를 Deploy 화면에서 확인하세요.')
            return
        if role == 'gitlab':
            v['restore_http_port'] = validate_port(self.io.ask('GitLab 격리 복구 검증 HTTP 포트', str(v.get('restore_http_port', 18929))))
            if v['restore_http_port'] == v.get('gitlab_port', 8929):
                raise Refused('격리 복구 포트는 GitLab 서비스 포트와 달라야 합니다.')
        v['enrollment_code'] = self.io.secret('이 서버의 일회성 등록 코드', '')
        self.save()
        result = self.component('deploy-agent', 'infrastructure-agent', {
            'DEPLOY_AGENT_TRANSPORT': 'agent', 'DEPLOY_AGENT_TYPE': role,
            'DEPLOY_AGENT_CONTROL_URL': 'http://{}:{}'.format(v['control_ip'], v['control_port']),
            'DEPLOY_AGENT_SERVER_ID': v['server_id'], 'DEPLOY_AGENT_ALLOW_HTTP': 'y',
            'DEPLOY_AGENT_ENROLLMENT_CODE_FILE': str(self.directory / 'enrollment-code'),
            'DEPLOY_AGENT_CA_FILE': '', 'DEPLOY_AGENT_USER': 'deploy',
            'DEPLOY_AGENT_HEALTH_PORT': 8080, 'DEPLOY_AGENT_HEALTH_PATH': '/',
            'DEPLOY_AGENT_REGISTRY_URL': '',
            'DEPLOY_INFRA_RESTORE_HTTP_PORT': v.get('restore_http_port', 18929)})
        if result:
            raise Refused('인프라 에이전트 등록이 완료되지 않았습니다. 위 오류를 확인하세요.')
        self.io.say('Deploy에서 등록을 승인하고 연결·준비 검사를 실행하세요.')

    def runner_questions(self):
        v = dict(self.state['values'])
        v['project_id'] = self.io.ask('GitLab Project ID', v.get('project_id', ''), positive)
        v['runner_id'] = self.io.ask('GitLab Runner ID', v.get('runner_id', ''), positive)
        token = self.io.secret('Runner 인증 토큰 glrt-...', v.get('runner_token', ''))
        if not token.startswith('glrt-'):
            raise Refused('Runner 인증 토큰은 glrt-로 시작해야 합니다.')
        v['runner_token'] = token
        v['runner_pat'] = self.io.secret('Runner 정책 검증 PAT (manage_runner + read_api)', v.get('runner_pat', ''))
        build_answers('gitlab', v, self.directory)
        if v != self.state['values']:
            for stage in list(self.state['stages']):
                if stage == 'runner' or stage == 'health' or stage.startswith('ci-'):
                    del self.state['stages'][stage]
            self.state['values'] = v
        self.save()

    def show_runtime(self):
        components = ROLE_COMPONENTS[self.state['role']]
        if ('infrastructure-agent' in self.state['stages'] or Path('/etc/deploy-system/agent.json').is_file()) and 'deploy-agent' not in components:
            components += ',deploy-agent'
        answers = self.prepare_answers()
        result = subprocess.run(['bash', str(self.package / 'installer/install.sh'), '--answers', str(answers),
                                 '--components', components, '--runtime-status'], cwd=str(self.package),
                                env=clean_environment(), stdout=subprocess.PIPE)
        if result.returncode:
            raise Refused('현재 설치 상태 조회 실패. 마지막 기록만으로 완료 처리하지 않았습니다.')
        records = dict(line[len('COMPONENT_STATUS '):].split('=', 1) for line in result.stdout.decode().splitlines()
                       if line.startswith('COMPONENT_STATUS '))
        for key in components.split(','):
            marker = self.directory / ('component-' + key + '.status')
            previous = trusted_path(marker, private=True).read_text().strip() if marker.exists() else '기록 없음'
            self.io.say('{} / 현재 {} / 마지막 작업 {}'.format(key, records.get(key, '조회 실패'), previous))
        if UPGRADE_STATE.exists():
            for entry in sorted(UPGRADE_STATE.glob('*.json')):
                record = read_private(entry)
                operation = record.get('operation', {})
                component = record.get('component') or operation.get('component', '?')
                target = record.get('target') or operation.get('target', '?')
                self.io.say('고도화 기록: {} / 목표 {} / 단계 {}. 위 현재 상태는 최초 반입본의 기준입니다.'.format(
                    component, target, record.get('phase', '?')))
        product_containers = {'deploy': ['worksystem-nexus'], 'gitlab': ['worksystem-gitlab', 'worksystem-gitlab-runner']}
        for name in product_containers.get(self.state['role'], []):
            if not shutil.which('docker'):
                continue
            mounts = subprocess.run(['docker', 'inspect', '--format', '{{range .Mounts}}{{println .Source "→" .Destination}}{{end}}', name],
                                    env=clean_environment(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            if mounts.returncode == 0:
                self.io.say(name + ' 현재 마운트:\n' + mounts.stdout.decode().strip())

    def check_health(self):
        role = self.state['role']
        commands = []
        if role == 'deploy':
            commands = [['systemctl', 'is-active', 'docker', 'mariadb', 'deploy-system'],
                        [sys.executable, self.package / 'installer/deploy-system/install-tls-keystore.py', '--jar', '/opt/deploy-system/app/deploy-ops.jar', '--mode', 'health-check', '--port', str(self.state['values']['deploy_port'])]]
        elif role == 'gitlab':
            commands = [['docker', 'exec', 'worksystem-gitlab', 'gitlab-ctl', 'status']]
            if self.state['values'].get('runner_token'):
                commands.append(['docker', 'exec', 'worksystem-gitlab-runner', 'gitlab-runner', 'verify'])
        else:
            commands = [['systemctl', 'is-active', 'docker' if role == 'was' else 'nginx', 'deploy-pull-agent.service']]
        agent_role = role in ('was', 'web')
        if agent_role:
            self.state['stages']['health'] = 'RUNNING'
            self.save()
        result = self.run_commands(commands, 'health-services' if agent_role else 'health')
        if agent_role and result:
            self.state['stages']['health'] = 'FAILED'
            self.save()
        if result == 0 and agent_role:
            try:
                probe = subprocess.run(['/usr/local/deploy/bin/deploy-agent-probe'], env=clean_environment(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            except OSError:
                self.state['stages']['health'] = 'FAILED'
                self.state['last_return_code'] = 127
                self.save()
                self.io.say('에이전트 준비 검사 프로그램을 실행하지 못했습니다. 설치 결과를 확인하세요.')
                return 127
            try:
                ready = probe.returncode == 0 and json.loads(probe.stdout.decode('utf-8')).get('ready') is True
            except (ValueError, UnicodeError):
                ready = False
            if not ready:
                self.state['stages']['health'] = 'FAILED'
                self.state['last_return_code'] = 1
                self.save()
                self.io.say('에이전트 준비 검사 ready=true를 확인하지 못했습니다. Deploy 웹 연결·승인 상태와 서버 로그를 확인하세요.')
                return 1
            self.state['stages']['health'] = 'SUCCEEDED'
            self.state['last_return_code'] = 0
            self.save()
        if result == 0:
            self.io.say('서비스 상태 명령이 성공했습니다. 실제 PC 웹 접속·방화벽·업무 배포 결과는 별도로 확인하세요.')
        return result

    def initial_password(self):
        role = self.state['role']
        if role not in ('deploy', 'gitlab'):
            self.io.say('이 역할에는 제품 초기 암호 파일이 없습니다.')
            return
        self.io.say('초기 암호를 화면에 표시하면 회사 접속 기록에 남을 수 있습니다. 현재 계정은 Linux root이며 제품 웹 암호와 별개입니다.')
        if not self.io.confirm('제품 최초 암호 파일을 지금 표시합니까?'):
            return
        container, path = ('worksystem-nexus', '/nexus-data/admin.password') if role == 'deploy' else ('worksystem-gitlab', '/etc/gitlab/initial_root_password')
        subprocess.run(['docker', 'exec', container, 'cat', path], env=clean_environment())
        self.io.say('파일이 없으면 이미 초기 암호 변경을 마쳤을 수 있습니다. 데이터를 초기화하지 마세요.')

    def repair_nexus(self):
        if self.state['role'] != 'deploy':
            self.io.say('Nexus를 설치한 demo-deploy에서 선택하세요.')
            return
        self.io.say('Maven 정책 변경: releases/seed=RELEASE·ALLOW_ONCE, snapshots=SNAPSHOT·ALLOW, public group=세 내부 저장소.')
        self.io.say('Nexus UI에서 실제 데이터와 위 정책 차이를 먼저 확인하세요. 기존 데이터가 있으면 영향 검토 없이 실행하지 마세요.')
        if self.io.confirm('변경 내용을 검토했으며 이번 한 번 Maven 저장소 정책 변경을 허용합니까?'):
            try:
                self.component('nexus', 'nexus-policy-repair', {'NEXUS_MAVEN_REPAIR_DRIFT': 'y'})
            finally:
                self.prepare_answers()
            self.io.say('정책 변경 허용값은 다시 n으로 복원했습니다. 성공했다면 1번으로 설치를 재개하세요.')

    def ci_menu(self):
        role = self.state['role']
        if role not in ('deploy', 'gitlab'):
            self.io.say('CI 재료는 demo-deploy 또는 demo-gitlab에서 준비합니다.')
            return
        required = 'infrastructure' if role == 'deploy' else 'runner'
        if self.state['stages'].get(required) != 'SUCCEEDED':
            self.io.say('먼저 해당 서버 프로그램 설치 단계를 성공시켜야 합니다.')
            return
        if not self.io.confirm('Nexus 웹 약관 처리가 완료됐습니까?'):
            return
        operations = {'1': 'prepare-images', '2': 'seed-dry-run', '3': 'seed-apply'} if role == 'deploy' else {'1': 'prepare-images', '2': 'publish-images'}
        for key, operation in operations.items():
            self.io.say('{}: {}'.format(key, operation))
        self.io.say('0: 돌아가기 · 준비 이미지와 seed는 패키지에 동봉된 Java CI 재료이며 신규 프로젝트의 모든 의존성을 보장하지 않습니다.')
        choice = self.io.ask('CI 작업 선택', '0')
        if choice not in operations:
            return
        operation = operations[choice]
        if operation != 'prepare-images' and self.state['stages'].get('ci-prepare-images') != 'SUCCEEDED':
            self.io.say('먼저 이 서버에서 prepare-images를 실행하세요.')
            return
        v = self.state['values']
        ci = self.directory / 'ci'
        private_directory(ci)
        state_dir = ci / (operation + '-' + uuid.uuid4().hex)
        command = ['bash', self.package / 'installer/ci/bootstrap-offline-ci.sh', '--operation', operation, '--state-dir', state_dir, '--post-eula-confirmed', '--allow-http']
        auth = ci / 'auth.json'
        if operation != 'prepare-images':
            nexus_ip = v['server_ip'] if role == 'deploy' else v['nexus_ip']
            command += ['--nexus-url', 'http://{}:{}'.format(nexus_ip, v['nexus_port'])]
            self.io.say('최초 반입용 Nexus 계정은 정책 조회·Maven 또는 Docker 쓰기 권한이 필요합니다. 상시 CI 계정과 구분하세요.')
            username = self.io.ask('Nexus 최초 반입 계정', 'admin')
            password = self.io.secret('Nexus 반입 계정 암호')
            write_private(auth, (json.dumps({'username': username, 'password': password}) + '\n').encode('utf-8'))
        try:
            if operation.startswith('seed-'):
                if not self.io.confirm('이번 기본 반입은 SNAPSHOT을 제외합니다. 필요한 SNAPSHOT은 별도 반입할 것을 확인했습니까?'):
                    return
                if operation == 'seed-apply' and self.state['stages'].get('ci-seed-dry-run') != 'SUCCEEDED':
                    self.io.say('먼저 seed-dry-run 결과를 확인하세요.')
                    return
                command += ['--maven-auth-file', auth, '--omit-snapshots']
            elif operation == 'publish-images':
                repo_pattern = r'[a-z0-9]+(?:[._/-][a-z0-9]+)*'
                builder = self.io.ask('빌드 이미지 저장 경로', 'platform/java-builder', lambda x: x if re.fullmatch(repo_pattern, x) else invalid('소문자 저장소 경로를 입력하세요.'))
                runtime = self.io.ask('실행 이미지 저장 경로', 'platform/java-runtime', lambda x: x if re.fullmatch(repo_pattern, x) else invalid('소문자 저장소 경로를 입력하세요.'))
                tag = self.io.ask('고정 태그 (latest 금지)', 'offline-r6', lambda x: x if re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', x) and x != 'latest' else invalid('고정 태그를 입력하세요.'))
                if builder == runtime:
                    raise Refused('빌드·실행 이미지 저장 경로를 다르게 입력하세요.')
                command += ['--registry', '{}:{}'.format(v['nexus_ip'], v['registry_port']), '--registry-auth-file', auth, '--policy-auth-file', auth,
                            '--docker-repository', 'docker-hosted', '--builder-repository', builder, '--runtime-repository', runtime, '--tag', tag]
            if self.io.confirm('선택한 CI 재료 작업을 실행합니까?'):
                result = self.run_commands([command], 'ci-' + operation)
                self.io.say('결과 폴더: ' + str(state_dir))
                if result == 0:
                    self.io.say('재료 준비가 끝났습니다. 프로젝트 등록·MR·실제 CI 성공은 Deploy/GitLab 웹에서 확인하세요.')
        finally:
            if auth.exists():
                auth.unlink()

    def summary(self):
        v = self.state['values']
        self.io.say('\n서버: {} / {} / 역할: {} / 실행 권한: root'.format(v['server_name'], v['server_ip'], self.state['role']))
        for key in ('deploy_port', 'nexus_ip', 'nexus_port', 'registry_port', 'gitlab_port', 'control_ip', 'control_port', 'server_id', 'was_ip', 'was_port', 'web_port', 'proxy_port', 'health_port', 'health_path'):
            if key in v:
                self.io.say('  {}: {}'.format(key, v[key]))
        self.io.say('암호·토큰·키: 표시하지 않음 / 저장 위치: ' + str(self.directory))
        self.io.say('이 설치기는 현재 서버만 변경합니다. 다른 서버로 SSH/SFTP 접속하지 않습니다.')
        self.io.say('설치 상태: ' + json.dumps(self.state['stages'], ensure_ascii=False))
        for marker in sorted(self.directory.glob('component-*.status')):
            trusted_path(marker, private=True)
            self.io.say('{}: {}'.format(marker.name[10:-7], marker.read_text().strip()))

    def start(self):
        require_host()
        trusted_path(self.package)
        for required in ('installer/install.sh', 'installer/bundles/RELEASE-BOM.env'):
            trusted_path(self.package / required)
        self.identity = machine_identity()
        self.io.say('반입 계정: demo-deploy=deploy / demo-gitlab=gitlab / demo-was=docker / demo-web=nginx')
        self.io.say('설치 계정: 모든 서버에서 root. 이미 root 세션이면 계정 전환하지 마세요.')
        keys = ['deploy', 'gitlab', 'was', 'web']
        for index, role in enumerate(keys, 1):
            self.io.say('{}. {} ({})'.format(index, ROLES[role][0], ROLES[role][3]))
        saved_roles = [str(i) for i, role in enumerate(keys, 1) if (self.base / role / 'state.json').is_file()]
        selection = self.io.ask('이 서버의 역할 (0: 종료)', saved_roles[0] if len(saved_roles) == 1 else '0')
        if selection == '0':
            return 0
        if selection not in ('1', '2', '3', '4'):
            raise Refused('1~4 중 하나를 선택하세요.')
        role = keys[int(selection) - 1]
        private_directory(self.base)
        import fcntl
        lock_path = self.base / 'installer.lock'
        if lock_path.exists() or lock_path.is_symlink():
            trusted_path(lock_path, private=True)
        self._lock = os.open(str(lock_path), os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        try:
            fcntl.flock(self._lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(self._lock)
            self._lock = None
            raise Refused('이 서버에서 다른 설치기가 실행 중입니다. 중복 실행하지 않습니다.')
        existing = self.load(role)
        if existing:
            self.io.say('이 서버의 저장된 설정을 찾았습니다. 비밀정보는 재생성하지 않습니다.')
        else:
            if role == 'deploy' and Path('/opt/deploy-system/app/deploy-ops.jar').exists():
                raise Refused('기존 Deploy가 있으나 마법사 원본 설정이 없습니다. 신규 설정을 덮어쓰지 말고 기존 설정 복구/패치 절차를 사용하세요.')
            self.state = {'version': VERSION, 'role': role, 'identity': self.identity, 'package': str(self.package), 'values': {}, 'stages': {}}
            self.state['values'] = self.collect(role)
            self.save()
        while True:
            self.summary()
            self.io.say('1. 설정 검사 후 설치/재개\n2. 입력 내용 수정\n3. 저장하고 종료\n4. 폐쇄망 CI 재료 준비\n5. 서비스 상태 확인\n6. 제품 최초 암호 확인\n7. Nexus Maven 정책 변경 검토\n8. 인프라 에이전트 등록\n9. 초기화·삭제·서비스 관리\n10. 제품 버전 고도화\n11. 백업·복구 준비 및 Deploy 백업 생성')
            choice = self.io.ask('선택', '3')
            try:
                if choice == '1':
                    if self.io.confirm('표시된 현재 서버에 설치/재개합니까?'):
                        self.execute()
                elif choice == '2':
                    self.replace_values(self.collect(role, self.state['values']))
                    if role == 'gitlab' and self.io.confirm('Runner 등록 정보도 입력/수정합니까?'):
                        self.runner_questions()
                    self.save()
                elif choice == '3':
                    self.save()
                    self.io.say('설정을 저장했습니다. 같은 설치기를 다시 실행하면 이어서 진행할 수 있습니다.')
                    return 0
                elif choice == '4':
                    self.ci_menu()
                elif choice == '5':
                    self.show_runtime()
                    self.check_health()
                elif choice == '6':
                    self.initial_password()
                elif choice == '7':
                    with baseline_guard('nexus', self.package):
                        self.repair_nexus()
                elif choice == '8':
                    with baseline_guard('deploy-agent'):
                        self.enroll_infrastructure()
                elif choice == '9':
                    lifecycle = self.lifecycle()
                    try:
                        with baseline_guard(''):
                            lifecycle.manage(self)
                    except lifecycle.LifecycleError as error:
                        raise Refused(str(error))
                elif choice == '10':
                    self.upgrade_menu()
                elif choice == '11':
                    self.backup_menu()
                else:
                    self.io.say('1~11 중 하나를 선택하세요.')
            except (Refused, OSError, ValueError, KeyError, TypeError) as error:
                self.io.say('중단: {}. 설정은 유지됩니다. 오류를 해결한 뒤 재개하세요.'.format(safe_error(error)))


def invalid(message):
    raise Refused(message)


def safe_error(error):
    return str(error) if isinstance(error, Refused) else type(error).__name__


def clean_environment():
    return {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'HOME': '/root', 'PYTHONDONTWRITEBYTECODE': '1'}


def run(package_root):
    wizard = Wizard(package_root)
    try:
        return wizard.start()
    except (EOFError, KeyboardInterrupt):
        print('\n입력을 종료했습니다. 저장된 설정이 있으면 같은 설치기로 재개하세요.')
        return 130
    except (Refused, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print('설치를 시작하지 못했습니다: ' + safe_error(error), file=sys.stderr)
        return 1
    finally:
        if getattr(wizard, '_lock', None) is not None:
            os.close(wizard._lock)


def main(argv=None):
    flags = list(sys.argv[1:] if argv is None else argv)
    if flags == ['--prepare-baseline-lock'] or (len(flags) == 2 and flags[0] == '--baseline-check'):
        try:
            with upgrade_lock_file(UPGRADE_STATE):
                if flags[0] == '--baseline-check':
                    for key in sorted(check_baseline(flags[1], UPGRADE_STATE, Path(__file__).absolute().parents[1])):
                        print(key)
            return 0
        except (OSError, Refused, ValueError) as error:
            print('설치 중단: ' + str(error), file=sys.stderr)
            return 1
    # Python 3.6 does not provide -X utf8 or TextIOWrapper.reconfigure.
    # Keep Korean prompts readable when an approved SSH session uses C locale.
    for name in ('stdin', 'stdout', 'stderr'):
        stream = getattr(sys, name)
        if (getattr(stream, 'encoding', '') or '').lower().replace('-', '') != 'utf8' and hasattr(stream, 'detach'):
            setattr(sys, name, io.TextIOWrapper(stream.detach(), encoding='utf-8', errors='replace', line_buffering=True))
    parser = argparse.ArgumentParser(description='Deploy System 역할별 대화형 오프라인 설치기 (root / Rocky 8.10)')
    parser.add_argument('--package-root', required=True)
    args = parser.parse_args(argv)
    return run(args.package_root)


if __name__ == '__main__':
    # Maintenance helpers import these same permission/state routines under Python isolated mode.
    sys.modules.setdefault('interactive_install', sys.modules[__name__])
    sys.exit(main())
