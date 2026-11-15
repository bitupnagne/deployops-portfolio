"""Role-scoped maintenance for installations made by the interactive Rocky installer."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess


class LifecycleError(ValueError):
    """A failure safe to display without command output or secret values."""


UNITS = {
    'deploy': ('deployops-backup.timer', 'deployops-nexus-backup.timer',
               'deployops-backup.service', 'deployops-nexus-backup.service', 'deploy-system.service'),
    'was': ('deploy-storage-guard.timer', 'deploy-storage-guard.service'),
    'web': (), 'gitlab': (),
}
CONTAINERS = {
    'deploy': ('worksystem-nexus',),
    'gitlab': ('worksystem-gitlab-runner', 'worksystem-gitlab'),
    'was': (), 'web': (),
}
BIN = Path('/usr/local/deploy/bin')
SYSTEMD = Path('/etc/systemd/system')
COMMON_FILES = ('deploy-pull-agent.py', 'deploy-agent-probe')
ROLE_FILES = {
    'deploy': ('deploy-infrastructure.sh', 'deploy-infrastructure.py'),
    'gitlab': ('deploy-infrastructure.sh', 'deploy-infrastructure.py'),
    'was': ('local-healthcheck.sh', 'deploy-runtime.sh', 'deploy-runtime.py',
            'deploy-storage.sh', 'deploy-storage.py', 'deploy-was-app.sh'),
    'web': ('local-healthcheck.sh', 'deploy-web-app.sh', 'deploy-nginx-policy.sh',
            'nginx-config-validator.py', 'nginx-rollout.py'),
}
UNKNOWN = {'unknown': True}
ENV = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
       'LC_ALL': 'C', 'HOME': '/root', 'DOCKER_HOST': 'unix:///var/run/docker.sock'}


def assets(role):
    items = [('unit', str(SYSTEMD / name)) for name in ('deploy-pull-agent.service',) + UNITS[role]]
    items += [('file', str(BIN / name)) for name in COMMON_FILES + ROLE_FILES[role]]
    if role in ('was', 'web'):
        items.append(('file', '/etc/sudoers.d/deploy-remote'))
    items += [('container', name) for name in CONTAINERS[role]]
    return items


def command(args):
    try:
        return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=ENV,
                              universal_newlines=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise LifecycleError('명령 실행 또는 시간 제한 오류: ' + args[0]) from exc


def require(args):
    result = command(args)
    if result.returncode:
        raise LifecycleError('도구가 작업을 완료하지 못했습니다: ' + ' '.join(args))
    return result.stdout.strip()


def file_identity(name):
    path = Path(name)
    for entry in reversed((path,) + tuple(path.parents)):
        try:
            info = entry.lstat()
        except FileNotFoundError:
            return None
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            return UNKNOWN
        if entry != path and not stat.S_ISDIR(info.st_mode):
            return UNKNOWN
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > 10 * 1024 * 1024:
        return UNKNOWN
    with path.open('rb') as stream:
        digest = hashlib.sha256(stream.read()).hexdigest()
    return {'sha256': digest, 'inode': info.st_ino, 'device': info.st_dev,
            'mode': stat.S_IMODE(info.st_mode)}


def container_identity(name):
    if not shutil.which('docker', path=ENV['PATH']):
        return UNKNOWN if Path('/var/lib/docker').exists() or Path('/var/run/docker.sock').exists() else None
    result = command(['docker', 'container', 'inspect', '-f', '{{.Id}}', name])
    if result.returncode:
        if 'No such container:' in result.stderr or 'No such object:' in result.stderr:
            return None
        return UNKNOWN
    identifier = result.stdout.strip()
    return {'id': identifier} if re.fullmatch(r'[a-f0-9]{64}', identifier) else UNKNOWN


def identity(kind, name):
    return container_identity(name) if kind == 'container' else file_identity(name)


def before_install(wizard):
    """Bracket the actual local install command while the role lock is held."""
    role = wizard.state['role']
    if wizard.state.get('lifecycle_install_pending'):
        wizard.state['lifecycle_interrupted'] = True
    # After a killed process, newly present resources become preexisting on the next
    # run. A missing finally block must never turn an unobserved file into ownership.
    wizard._before_lifecycle = {name: identity(kind, name) for kind, name in assets(role)}
    wizard.state['lifecycle_install_pending'] = True
    wizard.save()


def after_install(wizard):
    baseline = getattr(wizard, '_before_lifecycle', None)
    if baseline is None:
        return
    wizard._before_lifecycle = None
    owned = wizard.state.setdefault('lifecycle_owned', {})
    for kind, name in assets(wizard.state['role']):
        old = baseline[name]
        current = identity(kind, name)
        if not current or current == UNKNOWN:
            continue
        if old is None or (name in owned and owned[name] == old):
            owned[name] = current
    wizard.state.pop('lifecycle_install_pending', None)
    wizard.save()


def unit_matches(name):
    unit = Path(name).name
    fragment = command(['systemctl', 'show', '-p', 'FragmentPath', '--value', unit])
    overrides = command(['systemctl', 'show', '-p', 'DropInPaths', '--value', unit])
    return (fragment.returncode == 0 and fragment.stdout.strip() == name and
            overrides.returncode == 0 and not overrides.stdout.strip())


PROVENANCE = Path('/var/lib/deploy-installer/upgrades/provenance')
PRODUCTS = {'worksystem-gitlab':'gitlab', 'worksystem-gitlab-runner':'runner', 'worksystem-nexus':'nexus'}


def confirmed_edge(name, target_id):
    path = PROVENANCE / name / (target_id + '.json')
    identity = file_identity(str(path))
    if identity in (None, UNKNOWN) or identity['mode'] != 0o600:
        return None
    return json.loads(path.read_text('utf-8'))


def node_snapshot(identifier):
    fmt = ('[{{json .Id}},{{json .Name}},{{json .Image}},'
           '{{json (index .Config.Labels "com.portfolio.upgrade-source")}},'
           '{{json .Mounts}},{{json .State.Running}},{{json .HostConfig.RestartPolicy.Name}}]')
    p = command(['docker', 'container', 'inspect', '-f', fmt, identifier])
    if p.returncode:
        return None
    cid, name, image, source_label, mounts, running, restart = json.loads(p.stdout)
    bindings = sorted(tuple(str(row.get(k,'')) for k in ('Destination','Type','Name','Source','RW','Propagation'))
                      for row in mounts)
    return {'id':cid, 'name':name.lstrip('/'), 'image':image, 'source':source_label,
            'mounts':bindings, 'running':running, 'restart':restart}


def upgrade_handoff(name, original, current):
    if name not in PRODUCTS or not original or not current:
        return False
    try:
        original_id = original['id']
        current_id = current['id']
        if not all(re.fullmatch(r'[a-f0-9]{64}', i) for i in (original_id, current_id)):
            return False
        machine = Path('/etc/machine-id').read_text().strip() + '/' + socket.gethostname()
        seen = set()
        target = node_snapshot(current_id)
        if not target or target['name'] != name:
            return False
        while current_id != original_id:
            if current_id in seen:
                return False
            seen.add(current_id)
            edge = confirmed_edge(name, current_id)
            if not edge or edge.get('phase') != 'done':
                return False
            operation = edge.get('operation') or {}
            sid = edge['sourceId']
            archive = name + '-before-' + sid[:12]
            if (not re.fullmatch(r'[a-f0-9]{64}', sid) or operation.get('name') != name or
                    operation.get('component') != PRODUCTS[name] or operation.get('machine') != machine or
                    edge.get('retainedName') != archive or edge.get('targetContainerId') != current_id or
                    target['id'] != current_id or target['source'] != sid or
                    edge.get('targetImageId') != target['image'] or
                    not re.fullmatch(r'sha256:[a-f0-9]{64}', target['image'])):
                return False
            source = node_snapshot(sid)
            if (not source or source['id'] != sid or source['name'] != archive or
                    source['running'] or source['restart'] not in ('','no') or
                    source['mounts'] != target['mounts']):
                return False
            target, current_id = source, sid
        return True
    except (OSError, ValueError, TypeError, KeyError, LifecycleError):
        return False


def plan(wizard):
    result = []
    role = wizard.state['role']
    owned = wizard.state.get('lifecycle_owned', {})
    other_paths = set()
    for other in UNITS:
        if other != role and (wizard.base / other / 'state.json').exists():
            other_paths.update(name for kind, name in assets(other))
    for kind, name in assets(role):
        live = identity(kind, name)
        if live is None:
            continue
        matches = name in owned and (owned[name] == live or (kind == 'container' and upgrade_handoff(name, owned[name], live)))
        if (name in other_paths or not matches or live == UNKNOWN or
                (kind == 'unit' and not unit_matches(name))):
            result.append(('keep', name, live))
        else:
            result.append((kind, name, live))
    # An untracked, changed or shared unit may still call these helpers/containers.
    unit_paths = {name for kind, name in assets(role) if kind == 'unit'}
    if any(kind == 'keep' and name in unit_paths for kind, name, _ in result):
        return [('keep', name, record) for kind, name, record in result]
    return result


ROLE_COMPONENTS = {
    'deploy': ('docker', 'mariadb', 'nexus', 'deploy-system', 'deploy-agent'),
    'gitlab': ('docker', 'gitlab', 'gitlab-runner', 'deploy-agent'),
    'was': ('docker', 'deploy-agent'), 'web': ('nginx', 'deploy-agent'),
}
CHECKPOINT_VALUES = {'VERIFIED', 'RUNNING', 'FAILED', 'INSTALLED', 'WAITING_NEXUS_WEB'}


def checkpoints(wizard):
    records = []
    if wizard.directory != wizard.base / wizard.state['role']:
        raise LifecycleError('현재 설치 역할의 상태 경로가 일치하지 않습니다.')
    for component in ROLE_COMPONENTS[wizard.state['role']]:
        path = wizard.directory / ('component-' + component + '.status')
        stamp = file_identity(str(path))
        if stamp is None:
            continue
        value = None
        if (stamp != UNKNOWN and stamp['mode'] == 0o600 and path.stat().st_size < 40):
            raw = path.read_bytes()
            for candidate in CHECKPOINT_VALUES:
                if raw == (candidate + '\n').encode('ascii'):
                    value = candidate
                    break
        records.append((path,stamp,value))
    return records


def display_checkpoints(wizard, records):
    for path, stamp, value in records:
        wizard.io.say(('초기화 체크포인트: ' if value else '형식·권한·소유 불명으로 보존: ') + str(path))


def reset(wizard, preview=None):
    current = checkpoints(wizard)
    if preview is not None and current != preview:
        raise LifecycleError('체크포인트가 변경되었습니다. 초기화 전에 다시 확인하세요.')
    for path, stamp, value in current:
        if value:
            if file_identity(str(path)) != stamp:
                raise LifecycleError('체크포인트 파일이 변경됐습니다: ' + str(path))
            path.unlink()
    wizard.state['stages'] = {}
    wizard.state.pop('last_return_code', None)
    # Keep installation_started: it guards existing key material during reconfiguration.
    wizard.save()


def disable_unit(name):
    path = Path(name)
    unit = path.name
    fragment = require(['systemctl', 'show', '-p', 'FragmentPath', '--value', unit])
    overrides = require(['systemctl', 'show', '-p', 'DropInPaths', '--value', unit])
    if fragment != name or overrides:
        raise LifecycleError('기존 unit 또는 별도 override가 있어 유지합니다: ' + unit)
    require(['systemctl', 'stop', unit])
    require(['systemctl', 'disable', unit])


def retired_name(container_id):
    return 'deploy-retired-' + container_id


def retire(wizard, name, record):
    identifier = record['id']
    dest = retired_name(identifier)
    target = container_identity(dest)
    if target is not None and target != record:
        raise LifecycleError('보관할 컨테이너 이름이 이미 사용 중입니다: ' + dest)
    # Persist the ID before the rename; an interrupted operation remains discoverable.
    wizard.state.setdefault('lifecycle_retired', {})[identifier] = name
    wizard.save()
    require(['docker', 'update', '--restart=no', identifier])
    require(['docker', 'stop', '--time', '60', identifier])
    if target is None:
        require(['docker', 'rename', identifier, dest])


def remove_runtime(wizard, preview, checkpoint_preview=None):
    if checkpoint_preview is not None and checkpoints(wizard) != checkpoint_preview:
        raise LifecycleError('체크포인트가 변경되었습니다. 다시 확인하세요.')
    if preview != plan(wizard):
        raise LifecycleError('미리보기 후 소유권 또는 대상이 바뀌었습니다. 다시 확인하세요.')
    changed_units = False
    try:
        for kind, name, record in preview:
            if kind == 'keep':
                continue
            if identity(kind, name) != record:
                raise LifecycleError('실행 직전 대상이 바뀌었습니다: ' + name)
            if kind == 'container':
                retire(wizard, name, record)
            else:
                if kind == 'unit':
                    disable_unit(name)
                    changed_units = True
                if file_identity(name) != record:
                    raise LifecycleError('서비스 중지 후 파일 변경을 감지했습니다: ' + name)
                Path(name).unlink()
            wizard.state['lifecycle_owned'].pop(name, None)
            wizard.save()
    finally:
        if changed_units:
            require(['systemctl', 'daemon-reload'])
    reset(wizard, checkpoint_preview)


def purge_candidates(wizard):
    results = []
    for identifier, name in list(wizard.state.get('lifecycle_retired', {}).items()):
        if name not in CONTAINERS[wizard.state['role']] or not re.fullmatch(r'[a-f0-9]{64}', identifier):
            raise LifecycleError('컨테이너 보관 기록의 형식이 올바르지 않습니다.')
        expected = {'id': identifier}
        retired = retired_name(identifier)
        by_name = container_identity(retired)
        by_id = container_identity(identifier)
        if (by_name is None and by_id is None and
                wizard.state.get('lifecycle_purge_pending') == identifier):
            # The previously confirmed deletion completed before the final state save.
            del wizard.state['lifecycle_retired'][identifier]
            wizard.state.pop('lifecycle_purge_pending', None)
            wizard.save()
            continue
        if by_name != expected or by_id != expected:
            raise LifecycleError('보관 컨테이너의 소유권을 확인할 수 없습니다: ' + retired)
        running = require(['docker', 'container', 'inspect', '-f', '{{.State.Running}}', identifier])
        if running != 'false':
            raise LifecycleError('보관 컨테이너가 실행 중입니다: ' + retired)
        results.append((identifier, retired))
    return results


def purge(wizard, preview):
    if preview != purge_candidates(wizard):
        raise LifecycleError('미리보기 후 보관 컨테이너가 바뀌었습니다.')
    for identifier, name in preview:
        if container_identity(name) != {'id': identifier}:
            raise LifecycleError('컨테이너 ID 변경: ' + name)
        # No force and no --volumes. Docker refuses deletion if the container restarted.
        wizard.state['lifecycle_purge_pending'] = identifier
        wizard.save()
        require(['docker', 'container', 'rm', identifier])
        wizard.state.pop('lifecycle_purge_pending', None)
        del wizard.state['lifecycle_retired'][identifier]
        wizard.save()


def volume_maintenance(wizard):
    if wizard.state['role'] != 'deploy':
        wizard.io.say('이 역할의 DB·GitLab/Runner bind mount·사이트 파일·타 서비스 볼륨은 보존합니다.')
        return
    path = Path(__file__).resolve().parent / 'lib/nexus-volume-ownership.py'
    if file_identity(str(path)) in (None, UNKNOWN):
        raise LifecycleError('root가 관리하는 볼륨 점검 도구를 찾을 수 없습니다.')
    spec = importlib.util.spec_from_file_location('nexus_volume_ownership', str(path))
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    item, reason = helper.assessment(wizard.directory)
    if item is None:
        wizard.io.say('보존: nexus-data — ' + reason)
        return
    wizard.io.say('영구 삭제 후보: {} / 생성 시각 {} / {} / {}'.format(
        item['name'], item['created'], item['mount'], reason))
    phrase = 'DELETE EMPTY ' + wizard.state['values']['server_name'] + '/nexus-data'
    if wizard.io.ask('이 빈 볼륨만 삭제하려면 입력: ' + phrase, '') == phrase:
        try:
            helper.delete_empty(wizard.directory, item)
        except helper.VolumeError as exc:
            raise LifecycleError(str(exc))
        wizard.io.say('nexus-data 빈 볼륨을 삭제했습니다.')


def manage(wizard):
    io = wizard.io
    io.say('이 서버의 ' + wizard.state['role'] + ' 역할 유지관리')
    io.say('1. 설치 단계 초기화 / 2. 설치 관리 실행 자원 제거 / 3. 보관 컨테이너 영구 삭제 / 4. 전용 빈 볼륨 검토 / 0. 돌아가기')
    if wizard.state.get('lifecycle_install_pending') or wizard.state.get('lifecycle_interrupted'):
        io.say('중단된 설치에서 생성 시점을 증명할 수 없는 자원은 이전 설치 자원으로 보존합니다.')
    choice = io.ask('선택', '0')
    if choice == '0':
        return
    io.say('보존: 백업·비밀키·JAR·DB·bind mount·Docker 볼륨·공용 Docker/nginx·모든 nginx 사이트·타 프로젝트·설정 입력값')
    label = wizard.state['values']['server_name'] + '/' + wizard.state['role']
    if choice == '1':
        stages = wizard.state.get('stages', {})
        io.say('초기화할 설치 진행 단계: ' + (', '.join(sorted(stages)) or '없음'))
        records = checkpoints(wizard)
        display_checkpoints(wizard, records)
        if io.confirm(label + ' 설치 진행 단계만 초기화합니까?'):
            reset(wizard, records)
            io.say('진행 단계를 초기화했습니다. 설치된 구성의 실제 상태를 조회합니다.')
            wizard.show_runtime()
        return
    if choice == '2':
        preview = plan(wizard)
        for kind, name, record in preview:
            if kind == 'container':
                io.say('중지·자동 시작 해제·이름 변경: {} → {}'.format(name, retired_name(record['id'])))
            elif kind == 'keep':
                io.say('보존 (소유 기록 없음, 변조 또는 확인 불가): ' + name)
            else:
                io.say(('중지·비활성화·unit 파일 제거: ' if kind == 'unit' else '실행 파일 제거: ') + name)
        if not any(row[0] != 'keep' for row in preview):
            io.say('자동 제거 가능 대상으로 확인된 자원이 없습니다. 이전 설치의 기존 자원은 보존합니다.')
            return
        io.say('재설치용 실행 단계 초기화: ' + ', '.join(sorted(wizard.state.get('stages', {}))))
        io.say('고도화 이전 세대(-before-) 컨테이너와 자료는 보존합니다. 진행 중인 서비스·배포·백업을 먼저 종료하세요.')
        records = checkpoints(wizard)
        display_checkpoints(wizard, records)
        if io.ask('위 제거를 실행하려면 입력: ' + label, '') == label:
            remove_runtime(wizard, preview, records)
            io.say('표시한 설치 관리 자원을 제거하고 단계를 초기화했습니다.')
        return
    if choice == '3':
        preview = purge_candidates(wizard)
        for identifier, name in preview:
            io.say('영구 삭제할 컨테이너 및 그 쓰기 레이어: {} ({})'.format(name, identifier))
        if preview and io.ask('백업 확인 후 영구 삭제하려면 입력: DELETE ' + label, '') == 'DELETE ' + label:
            purge(wizard, preview)
        elif not preview:
            io.say('삭제 가능한 보관 컨테이너가 없습니다.')
        return
    if choice == '4':
        volume_maintenance(wizard)
        return
    raise LifecycleError('0~4 중 하나를 선택하세요.')
