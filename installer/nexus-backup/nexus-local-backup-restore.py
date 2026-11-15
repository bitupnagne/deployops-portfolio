#!/usr/bin/env python3
"""Linux root/local Docker only; stopped H2+local blob volume and existing external keys."""
import argparse
import base64
import datetime
from contextlib import contextmanager
import hashlib
import json
import os
import re
import socket
import stat
import subprocess
import sys
import tarfile
import time
from pathlib import Path

DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']
NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.-]{0,100}$')
IMAGE = re.compile(r'^sha256:[0-9a-f]{64}$')
FILES = ('data.tar.gz', 'keys.json', 'image.tar', 'metadata.json')


def require(ok, category):
    if not ok:
        raise ValueError(category)


# Serial worker scopes; these bound CLI waits, not kernel/filesystem stalls.
READ_SECONDS = 15
HEALTH_SECONDS = 300
WORK_SECONDS = 1800
RECOVERY_SECONDS = 360
DEADLINE = None
UNCERTAIN_STOP = False
MUTATION_OBSERVER = None


class MutationUncertain(ValueError):
    pass


class RecoveryRequired(ValueError):
    pass


@contextmanager
def budget(seconds, independent=False):
    global DEADLINE
    prior = DEADLINE
    end = time.monotonic() + seconds
    DEADLINE = end if independent or prior is None else min(prior, end)
    try:
        yield
    finally:
        DEADLINE = prior


def remaining(seconds):
    value = seconds if DEADLINE is None else min(seconds, DEADLINE - time.monotonic())
    require(value > 0, 'OPERATION_DEADLINE_EXCEEDED')
    return value


def readonly(args):
    return (args[0] in ('inspect', 'ps', 'logs') or
            args == ['info', '--format', '{{.DockerRootDir}}'] or
            args[:2] in (['image', 'inspect'], ['volume', 'inspect'],
                         ['volume', 'ls'], ['network', 'inspect'], ['network', 'ls']) or
            (args[0] == 'exec' and args[2:3] == ['curl']))


def command(args):
    global UNCERTAIN_STOP
    read = readonly(args)
    seconds = READ_SECONDS if read else (600 if args[0] in ('save', 'load') else
                                         135 if args[0] == 'stop' else 120)
    timeout = remaining(seconds)  # Expiry before launch has no daemon mutation.
    # Broker callback fsyncs an inflight record BEFORE a mutation can reach Docker.
    token = MUTATION_OBSERVER.begin(args[0]) if not read and MUTATION_OBSERVER is not None else None
    timeout = remaining(seconds)  # Durable recording may consume the work budget.
    p = subprocess.Popen(DOCKER + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        stdout, stderr = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        # Kill this CLI only. The daemon may already have applied its request.
        if not read and args[0] == 'stop':
            UNCERTAIN_STOP = True
        try:
            p.kill()
            p.wait(timeout=2)
        except (OSError, subprocess.TimeoutExpired):
            pass
        finally:
            for stream in (p.stdout, p.stderr):
                stream.close()
        raise ValueError('READ_CLI_TIMEOUT') if read else MutationUncertain('MUTATION_RESULT_UNCERTAIN')
    # Only an authoritative successful CLI response completes this one event.
    # Nonzero exit, client exception or failed durable completion retain the hold.
    if token is not None and p.returncode == 0:
        MUTATION_OBSERVER.complete(token)
    if not read and args[0] == 'stop' and p.returncode != 0:
        UNCERTAIN_STOP = True
    return subprocess.CompletedProcess(DOCKER + args, p.returncode, stdout, stderr)


def call(args):
    p = command(args)
    require(p.returncode == 0, 'DOCKER_COMMAND_FAILED')
    return p.stdout.decode('utf-8').strip()


class BoundedLogProcess:
    def __init__(self, process):
        self.process = process
        self.stdout = process.stdout
        self.stderr = process.stderr

    def poll(self):
        return self.process.poll()

    def kill(self):
        return self.process.kill()

    def wait(self, timeout=None):
        return self.process.wait(timeout=min(timeout, 2) if timeout is not None else 2)


def inspect(cid):
    return json.loads(call(['inspect', cid]))[0]


def digest(path):
    h = hashlib.sha256()
    with open(str(path), 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            remaining(READ_SECONDS)
            h.update(block)
    return h.hexdigest()


def secure_path(path, directory=False, mode=None, owner=0):
    p = Path(path)
    require(p.is_absolute() and str(p.resolve()) == str(p), 'NONCANONICAL_PATH')
    for node in [p] + list(p.parents):
        require(not node.is_symlink(), 'SYMLINK_PATH')
    s = p.stat()
    require(owner is None or s.st_uid == owner, 'PATH_OWNER_REQUIRED')
    require(stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode), 'PATH_TYPE')
    if mode is not None:
        require(stat.S_IMODE(s.st_mode) == mode, 'PATH_MODE')
    return p


def new_path(path):
    p = Path(path)
    require(p.is_absolute() and str(p.resolve()) == str(p) and not p.exists() and not p.is_symlink(), 'NEW_PATH_REQUIRED')
    secure_path(str(p.parent), directory=True)
    require(stat.S_IMODE(p.parent.stat().st_mode) & 0o022 == 0, 'PARENT_WRITABLE_BY_OTHERS')
    return p


def write_json(path, data):
    with open(str(path), 'x') as f:
        json.dump(data, f, sort_keys=True, indent=2)
        f.write('\n')
    os.chmod(str(path), 0o600)


def write_auxiliary_diagnostic(path, data):
    # Optional evidence must never replace a primary backup/recovery exception.
    try:
        write_json(path, data)
    except Exception:
        pass  # Missing/partial auxiliary receipt is not diagnostic success evidence.


def volume_path(name):
    require(NAME.fullmatch(name), 'VOLUME_NAME')
    v = json.loads(call(['volume', 'inspect', name]))[0]
    root = call(['info', '--format', '{{.DockerRootDir}}'])
    expected = Path(root) / 'volumes' / name / '_data'
    require(v['Name'] == name and v['Driver'] == 'local' and not v.get('Options') and Path(v['Mountpoint']) == expected, 'LOCAL_VOLUME_CONTRACT')
    secure_path(root, directory=True)
    return secure_path(str(expected), directory=True, owner=None)


def validate_key(path):
    p = secure_path(str(path), mode=0o440)
    require(p.stat().st_gid == 200, 'KEY_GID')
    parent = secure_path(str(p.parent), directory=True, mode=0o750)
    require(parent.stat().st_gid == 200, 'KEY_PARENT_GID')
    data = json.loads(p.read_text())
    require(isinstance(data, dict) and isinstance(data.get('keys'), list) and data['keys'], 'KEY_SHAPE')
    ids = []
    for key in data['keys']:
        require(isinstance(key, dict) and isinstance(key.get('id'), str) and key['id'] and isinstance(key.get('key'), str) and len(key['key']) >= 32, 'KEY_SHAPE')
        ids.append(key['id'])
    require(len(ids) == len(set(ids)) and data.get('active') in ids, 'KEY_ACTIVE')


MARKER = '.deployops-nexus-key.sha256'


def binding_bytes(value, expected):
    require(re.fullmatch(rb'[0-9a-f]{64}\n?', value) and value.rstrip(b'\n').decode('ascii') == expected, 'KEY_MARKER_DIGEST_MISMATCH')


def validate_binding(data, key):
    marker = data / MARKER
    require(marker.exists() and not marker.is_symlink(), 'KEY_MARKER_REQUIRED')
    marker = secure_path(str(marker), mode=0o400)
    require(marker.stat().st_gid == 0 and marker.stat().st_nlink == 1, 'KEY_MARKER_OWNER_LINK')
    binding_bytes(marker.read_bytes(), digest(key))


def archive_binding(t, key):
    members = safe_members(t)
    markers = [x for x in members if x.name == MARKER]
    require(len(markers) == 1 and markers[0].isfile(), 'ARCHIVE_KEY_MARKER_REQUIRED')
    marker = markers[0]
    require(marker.uid == 0 and marker.gid == 0 and marker.mode == 0o400 and marker.size in (64, 65), 'ARCHIVE_KEY_MARKER_METADATA')
    binding_bytes(t.extractfile(marker).read(), digest(key))


def prepare_key_directory(keydir):
    keydir.mkdir(mode=0o750)
    os.chown(str(keydir), 0, 200)
    # mkdir mode is filtered by process umask077; restore must set 0750 explicitly.
    os.chmod(str(keydir), 0o750)


def projection(i):
    return {'id': i['Id'], 'name': i['Name'], 'image': i['Image'],
            'mounts': sorted([{'type': m['Type'], 'source': m['Source'], 'destination': m['Destination'], 'rw': m['RW']} for m in i['Mounts']], key=lambda m: m['destination'])}


def source_contract(a):
    require(a.all_storage_in_data_volume, 'LOCAL_STORAGE_OPERATOR_CONFIRMATION_REQUIRED')
    require(re.fullmatch(r'[0-9a-f]{64}', a.container_id) and NAME.fullmatch(a.container_name) and IMAGE.fullmatch(a.image_id), 'EXACT_SOURCE_ID_REQUIRED')
    require(re.fullmatch(r'[1-9][0-9]*[mMgG]', a.heap), 'HEAP_FORMAT')
    i = inspect(a.container_id)
    require(i['Id'] == a.container_id and i['Name'] == '/' + a.container_name and i['Image'] == a.image_id, 'SOURCE_IDENTITY')
    require(call(['image', 'inspect', '--format', '{{.Id}}', a.image_id]) == a.image_id, 'CACHED_IMAGE_ID')
    mp = volume_path(a.volume)
    require(mp.stat().st_uid == 200 and not stat.S_IMODE(mp.stat().st_mode) & 0o002, 'NEXUS_DATA_ROOT_OWNER_MODE')
    key = secure_path(a.key_file, mode=0o440)
    require(mp not in key.parents, 'KEY_MUST_BE_OUTSIDE_DATA_VOLUME')
    validate_key(key)
    validate_binding(mp, key)
    mounts = i['Mounts']
    require(len(mounts) == 2 and any(m['Type'] == 'volume' and m.get('Name') == a.volume and m['Source'] == str(mp) and m['Destination'] == '/nexus-data' and m['RW'] for m in mounts), 'SOURCE_DATA_MOUNT')
    require(any(m['Type'] == 'bind' and m['Source'] == str(key) and m['Destination'] == '/run/nexus-secrets/secrets.json' and not m['RW'] for m in mounts), 'SOURCE_KEY_MOUNT')
    env = dict(x.split('=', 1) for x in i['Config'].get('Env', []) if '=' in x)
    heap = '-Xms{0} -Xmx{0} -XX:MaxDirectMemorySize={0} -Djava.util.prefs.userRoot=/nexus-data/javaprefs'.format(a.heap)
    require(env.get('INSTALL4J_ADD_VM_PARAMS') == heap and env.get('NEXUS_SECRETS_KEY_FILE') == '/run/nexus-secrets/secrets.json', 'SUPPORTED_INSTALLER_RUNTIME_ONLY')
    ports = sorted(int(x[:-4]) for x in i['HostConfig']['PortBindings'] if x.endswith('/tcp'))
    require(len(ports) == len(i['HostConfig']['PortBindings']) and 8081 in ports, 'TCP_PORT_CONTRACT')
    # Closed scope: H2, file blob stores only below /nexus-data/blobs, no external DB/blob.
    require((mp / 'db' / 'nexus.mv.db').is_file() and (mp / 'blobs').is_dir(), 'LOCAL_H2_DEFAULT_BLOBS_REQUIRED')
    require(not i['HostConfig'].get('AutoRemove'), 'SOURCE_AUTOREMOVE_UNSUPPORTED')
    image_config = json.loads(call(['image', 'inspect', a.image_id]))[0]['Config']
    require(i['Config'].get('Entrypoint') == image_config.get('Entrypoint') and i['Config'].get('Cmd') == image_config.get('Cmd'), 'CUSTOM_COMMAND_UNSUPPORTED')
    return i, mp, key, ports


def safe_members(t):
    seen = set()
    for m in t.getmembers():
        name = m.name
        require(not name.startswith('/') and '\\' not in name and all(x not in ('.', '..', '') for x in name.split('/')), 'ARCHIVE_PATH')
        require(name not in seen, 'ARCHIVE_DUPLICATE')
        seen.add(name)
        require((m.isdir() or m.isfile()) and not m.mode & 0o7000, 'ARCHIVE_LINK_SPECIAL_OR_SETID')
    return t.getmembers()


def wait_health(cid):
    with budget(HEALTH_SECONDS):
        while True:
            try:
                remaining(READ_SECONDS)
                i = inspect(cid)
                if i['State']['Running'] and call(['exec', cid, 'curl', '-sS', '-o', '/dev/null', '--max-time', '3', '-w', '%{http_code}', 'http://127.0.0.1:8081/service/rest/v1/status']) == '200':
                    remaining(READ_SECONDS)
                    return
            except ValueError:
                pass
            delay = remaining(5)
            time.sleep(delay)


def restore_original(cid, original, was_running):
    require(projection(inspect(cid)) == original, 'ORIGINAL_IDENTITY_CHANGED')
    if UNCERTAIN_STOP:
        # A running observation cannot rule out a later pending daemon stop.
        raise RecoveryRequired('PENDING_STOP_RECOVERY_REQUIRED')
    if was_running:
        if not inspect(cid)['State']['Running']:
            call(['start', cid])
        wait_health(cid)
    else:
        require(not inspect(cid)['State']['Running'], 'ORIGINAL_STOPPED_STATE_CHANGED')



STOP143_IMAGE='sha256:617de9147a594bd1fb8d942cd56f1135ebb61cc622566269907e178377b3fdbd'
STOP143_READER_SHA='E3A1CB873C99750B220AE3C0DE420323A87AC562437C761D539DE44BF3860201'
STOP143_READER_BASE64='aW1wb3J0IGRhdGV0aW1lLGpzb24scmUsc3VicHJvY2VzcyxzeXMsb3Msc2VsZWN0b3JzLHRpbWUKQ0lEPSc3ZWExOGZjNTJhODE5Y2U3OTQ5MGYwOTJmZDE2OWFmZTU4MWNjODkzMDVhNjNkODhhMDg2OGFkZDkyYzZjMDZkJwpJTUFHRT0nc2hhMjU2OjYxN2RlOTE0N2E1OTRiZDFmYjhkOTQyY2Q1NmYxMTM1ZWJiNjFjYzYyMjU2NjI2OTkwN2UxNzgzNzdiM2ZkYmQnClNJTkNFPScyMDI2LTEwLTEwVDE2OjA0OjAyWicKVU5USUw9JzIwMjYtMTAtMTBUMTY6MDQ6MjRaJwpTVEFNUD1yZS5jb21waWxlKHInXihcZHs0fS1cZHsyfS1cZHsyfVRcZHsyfTpcZHsyfTpcZHsyfSg/OlwuXGR7MSw5fSk/WilccycpCkNPTVBPTkVOVD1yZS5jb21waWxlKHInXGIoKD86b3JnXC5zb25hdHlwZVwubmV4dXN8b3JnXC5hcGFjaGVcLmthcmFmfG9yZ1wuaDJ8Y29tXC5vcmllbnRlY2hub2xvZ2llcykoPzpcLltBLVphLXpfJF1bQS1aYS16MC05XyRdKikrKVxzKy1ccycpCkgyX0NPTVBPTkVOVD0nb3JnLnNvbmF0eXBlLm5leHVzLmRhdGFzdG9yZS5teWJhdGlzLk15QmF0aXNEYXRhU3RvcmUnCk1BUktFUlM9KCgnaDJfc2h1dGRvd25fc3RhcnRlZCcsJ1NodXR0aW5nIGRvd24gSDIgZGF0YWJhc2UnKSwoJ2gyX3NodXRkb3duX3N1Y2NlZWRlZCcsJ0V4ZWN1dGVkIEgyIFNIVVRET1dOIGNvbW1hbmQnKSwoJ2gyX2FscmVhZHlfY2xvc2VkJywnSDIgZGF0YWJhc2Ugd2FzIGFscmVhZHkgY2xvc2VkIGR1cmluZyBzaHV0ZG93bicpLCgnaDJfc2h1dGRvd25fZmFpbGVkJywnRmFpbGVkIHRvIGV4ZWN1dGUgSDIgU0hVVERPV04gY29tbWFuZCcpKQoKZGVmIHN0YW1wX3RpbWUocyk6CiAgICAjIGRhdGV0aW1lMy42IGFjY2VwdHMgbWljcm9zZWNvbmRzOyB0cmltIG9ubHkgZnJhY3Rpb25hbCBwcmVjaXNpb24gZm9yIGNvbXBhcmlzb25zLgogICAgcmV0dXJuIGRhdGV0aW1lLmRhdGV0aW1lLnN0cnB0aW1lKHJlLnN1YihyJyhcLlxkezZ9KVxkK1okJyxyJ1wxWicscyksJyVZLSVtLSVkVCVIOiVNOiVTLiVmWicgaWYgJy4nIGluIHMgZWxzZSAnJVktJW0tJWRUJUg6JU06JVNaJykKZGVmIHN1bW1hcml6ZShyYXcsYnl0ZV9saW1pdD00KjEwMjQqMTAyNCk6CiAgICBpZiBsZW4ocmF3KT5ieXRlX2xpbWl0OnJhaXNlIFZhbHVlRXJyb3IoJ1dJTkRPV19CWVRFU19FWENFRURFRCcpCiAgICBjb3VudHM9e25hbWU6MCBmb3IgbmFtZSxfIGluIE1BUktFUlN9O2V2ZW50cz1bXTt1bmNsYXNzaWZpZWQ9MDtpbnZhbGlkPTA7bWF0Y2hlZF9saW5lcz0wCiAgICBmb3IgbGluZSBpbiByYXcuZGVjb2RlKCd1dGYtOCcsZXJyb3JzPSdyZXBsYWNlJykuc3BsaXRsaW5lcygpOgogICAgICAgIG1hdGNoPVNUQU1QLm1hdGNoKGxpbmUpCiAgICAgICAgaWYgbm90IG1hdGNoOmludmFsaWQrPTE7Y29udGludWUKICAgICAgICBzdGFtcD1tYXRjaC5ncm91cCgxKQogICAgICAgIHRyeTp3aGVuPXN0YW1wX3RpbWUoc3RhbXApCiAgICAgICAgZXhjZXB0IFZhbHVlRXJyb3I6aW52YWxpZCs9MTtjb250aW51ZQogICAgICAgIGlmIG5vdCBzdGFtcF90aW1lKFNJTkNFKTw9d2hlbjw9c3RhbXBfdGltZShVTlRJTCk6aW52YWxpZCs9MTtjb250aW51ZQogICAgICAgIGNvbXBvbmVudD1DT01QT05FTlQuc2VhcmNoKGxpbmUpCiAgICAgICAgbWVzc2FnZT1saW5lW2NvbXBvbmVudC5lbmQoKTpdLnN0cmlwKCkgaWYgY29tcG9uZW50IGVsc2UgJycKICAgICAgICBmb3VuZD1bbmFtZSBmb3IgbmFtZSxsaXRlcmFsIGluIE1BUktFUlMgaWYgY29tcG9uZW50IGFuZCBjb21wb25lbnQuZ3JvdXAoMSk9PUgyX0NPTVBPTkVOVCBhbmQgbWVzc2FnZT09bGl0ZXJhbF0KICAgICAgICBpZiBub3QgZm91bmQ6dW5jbGFzc2lmaWVkKz0xO2NvbnRpbnVlCiAgICAgICAgbWF0Y2hlZF9saW5lcys9MQogICAgICAgIGZvciBuYW1lIGluIGZvdW5kOmNvdW50c1tuYW1lXSs9MQogICAgICAgIGlmIGxlbihldmVudHMpPDEwMDpldmVudHMuYXBwZW5kKHsndGltZXN0YW1wJzpzdGFtcCwnY29tcG9uZW50Q2xhc3MnOmNvbXBvbmVudC5ncm91cCgxKSBpZiBjb21wb25lbnQgYW5kIGxlbihjb21wb25lbnQuZ3JvdXAoMSkpPD0xNjAgZWxzZSBOb25lLCdtYXJrZXJDYXRlZ29yaWVzJzpmb3VuZH0pCiAgICByZXR1cm4geydzb3VyY2VXaW5kb3cnOnsnc2luY2UnOlNJTkNFLCd1bnRpbCc6VU5USUx9LCdyYXdCeXRlQ291bnQnOmxlbihyYXcpLCdtYXJrZXJDb3VudHMnOmNvdW50cywnZXZlbnRzJzpldmVudHMsJ2V2ZW50c1RydW5jYXRlZCc6bWF0Y2hlZF9saW5lcz4xMDAsJ3VuY2xhc3NpZmllZExpbmVDb3VudCc6dW5jbGFzc2lmaWVkLCdpbnZhbGlkT3JPdXRzaWRlVGltZXN0YW1wTGluZUNvdW50JzppbnZhbGlkLCdjbGVhblN0b3BQcm92ZW4nOkZhbHNlLCdpbnRlcnByZXRhdGlvbic6J0V4YWN0IHJlbGVhc2UgSDIgbWVzc2FnZSBvYnNlcnZhdGlvbnMgb25seTsgYWJzZW5jZSBpcyBub3QgY2xlYW4tc3RvcCBldmlkZW5jZTsgc3RvcDE0MyByZW1haW5zIGZhaWx1cmUuJ30KCmRlZiBjYXB0dXJlX3dpbmRvdyhwcm9jZXNzLCBsaW1pdD00KjEwMjQqMTAyNCwgdGltZW91dD0zMCwgcmVhZGVyPW9zLnJlYWQsIHNlbGVjdG9yX2ZhY3Rvcnk9c2VsZWN0b3JzLkRlZmF1bHRTZWxlY3RvciwgY2xvY2s9dGltZS5tb25vdG9uaWMpOgogICAgIyBCb3VuZCBib3RoIGNoYW5uZWxzIHRvZ2V0aGVyIGR1cmluZyBjYXB0dXJlOyBraWxsIG9ubHkgdGhpcyByZWFkb25seSBEb2NrZXIgbG9ncyBjbGllbnQuCiAgICBkZWFkbGluZT1jbG9jaygpK3RpbWVvdXQ7dG90YWw9MDtidWZmZXJzPXt9O3NlbGVjdG9yPXNlbGVjdG9yX2ZhY3RvcnkoKQogICAgdHJ5OgogICAgICAgIGZvciBzdHJlYW0gaW4gKHByb2Nlc3Muc3Rkb3V0LHByb2Nlc3Muc3RkZXJyKToKICAgICAgICAgICAgYnVmZmVyc1tzdHJlYW0uZmlsZW5vKCldPWJ5dGVhcnJheSgpO3NlbGVjdG9yLnJlZ2lzdGVyKHN0cmVhbSxzZWxlY3RvcnMuRVZFTlRfUkVBRCkKICAgICAgICB3aGlsZSBzZWxlY3Rvci5nZXRfbWFwKCk6CiAgICAgICAgICAgIHJlbWFpbmluZz1kZWFkbGluZS1jbG9jaygpCiAgICAgICAgICAgIGlmIHJlbWFpbmluZzw9MDpyYWlzZSBWYWx1ZUVycm9yKCdJTkNPTVBMRVRFX1dJTkRPV19USU1FT1VUJykKICAgICAgICAgICAgZm9yIGtleSxfIGluIHNlbGVjdG9yLnNlbGVjdChtaW4ocmVtYWluaW5nLDAuMikpOgogICAgICAgICAgICAgICAgY2h1bms9cmVhZGVyKGtleS5maWxlb2JqLmZpbGVubygpLG1pbig2NTUzNixsaW1pdC10b3RhbCsxKSkKICAgICAgICAgICAgICAgIGlmIG5vdCBjaHVuazpzZWxlY3Rvci51bnJlZ2lzdGVyKGtleS5maWxlb2JqKTtjb250aW51ZQogICAgICAgICAgICAgICAgdG90YWwrPWxlbihjaHVuaykKICAgICAgICAgICAgICAgIGlmIHRvdGFsPmxpbWl0OnJhaXNlIFZhbHVlRXJyb3IoJ0lOQ09NUExFVEVfV0lORE9XX0JZVEVTX0xJTUlUJykKICAgICAgICAgICAgICAgIGJ1ZmZlcnNba2V5LmZpbGVvYmouZmlsZW5vKCldLmV4dGVuZChjaHVuaykKICAgICAgICByZW1haW5pbmc9ZGVhZGxpbmUtY2xvY2soKQogICAgICAgIGlmIHJlbWFpbmluZzw9MDpyYWlzZSBWYWx1ZUVycm9yKCdJTkNPTVBMRVRFX1dJTkRPV19USU1FT1VUJykKICAgICAgICB0cnk6Y29kZT1wcm9jZXNzLndhaXQodGltZW91dD1yZW1haW5pbmcpCiAgICAgICAgZXhjZXB0IHN1YnByb2Nlc3MuVGltZW91dEV4cGlyZWQ6cmFpc2UgVmFsdWVFcnJvcignSU5DT01QTEVURV9XSU5ET1dfVElNRU9VVCcpCiAgICAgICAgaWYgY29kZSE9MDpyYWlzZSBWYWx1ZUVycm9yKCdMT0dfV0lORE9XX0NPTU1BTkRfRkFJTEVEJykKICAgICAgICByZXR1cm4gYidcbicuam9pbihieXRlcyhiKSBmb3IgYiBpbiBidWZmZXJzLnZhbHVlcygpIGlmIGIpLHRvdGFsCiAgICBmaW5hbGx5OgogICAgICAgIHNlbGVjdG9yLmNsb3NlKCkKICAgICAgICBpZiBwcm9jZXNzLnBvbGwoKSBpcyBOb25lOgogICAgICAgICAgICBwcm9jZXNzLmtpbGwoKSAgIyBPbmx5IHRoZSBQb3BlbiBEb2NrZXIgbG9ncyBjbGllbnQsIG5ldmVyIE5leHVzL2NvbnRhaW5lci9vdXRlci4KICAgICAgICAgICAgcHJvY2Vzcy53YWl0KCkKICAgICAgICBmb3Igc3RyZWFtIGluIChwcm9jZXNzLnN0ZG91dCxwcm9jZXNzLnN0ZGVycik6c3RyZWFtLmNsb3NlKCkKCmRlZiBtYWluKCk6CiAgICBwPXN1YnByb2Nlc3MuUG9wZW4oWydkb2NrZXInLCctLWhvc3QnLCd1bml4Oi8vL3Zhci9ydW4vZG9ja2VyLnNvY2snLCdsb2dzJywnLS10aW1lc3RhbXBzJywnLS1zaW5jZScsU0lOQ0UsJy0tdW50aWwnLFVOVElMLENJRF0sc3Rkb3V0PXN1YnByb2Nlc3MuUElQRSxzdGRlcnI9c3VicHJvY2Vzcy5QSVBFKQogICAgcmF3LGJ5dGVfY291bnQ9Y2FwdHVyZV93aW5kb3cocCkKICAgICMgT25lIHN5bnRoZXRpYyBuZXdsaW5lIHNlcGFyYXRlcyB0d28gYm91bmRlZCBjaGFubmVsczsgaXQgaXMgbm90IGEgY2FwdHVyZWQgYnl0ZS4KICAgIHJlc3VsdD1zdW1tYXJpemUocmF3LGJ5dGVfbGltaXQ9NCoxMDI0KjEwMjQrMSkKICAgIHJlc3VsdFsncmF3Qnl0ZUNvdW50J109Ynl0ZV9jb3VudAogICAgcmVzdWx0LnVwZGF0ZSh7J3N0YXR1cyc6J1NBRkVfU0hVVERPV05fV0lORE9XX01BUktFUl9TVU1NQVJZJywnY29udGFpbmVySWQnOkNJRCwnaW1hZ2VJZEZyb21QaW5uZWRUZXJtaW5hbCc6SU1BR0UsJ2ltYWdlQmluZGluZ01vZGUnOidBQjUwIGFjdHVhbCByZWNlaXB0IG9ubHk7IG5vIGN1cnJlbnQgaW5zcGVjdCcsJ2xvZ3NDbGlFeGl0Q29kZSc6cC5yZXR1cm5jb2RlLCAnY29tcGxldGVXaW5kb3dSZWFkJzpUcnVlfSkKICAgIHByaW50KGpzb24uZHVtcHMocmVzdWx0KSkKaWYgX19uYW1lX189PSdfX21haW5fXyc6CiAgICB0cnk6bWFpbigpCiAgICBleGNlcHQgRXhjZXB0aW9uIGFzIGU6CiAgICAgICAgY2F0ZWdvcnk9c3RyKGUpIGlmIGlzaW5zdGFuY2UoZSxWYWx1ZUVycm9yKSBhbmQgcmUuZnVsbG1hdGNoKHInW0EtWl9dKycsc3RyKGUpKSBlbHNlICdERVRBSUxfU1VQUFJFU1NFRCcKICAgICAgICBwcmludChqc29uLmR1bXBzKHsnc3RhdHVzJzonRkFJTEVEX0xPR19XSU5ET1dfTk9fUkVQTEFZJywnZXhjZXB0aW9uVHlwZSc6dHlwZShlKS5fX25hbWVfXywnY2F0ZWdvcnknOmNhdGVnb3J5LCdjb21wbGV0ZVdpbmRvd1JlYWQnOkZhbHNlLCdjbGVhblN0b3BQcm92ZW4nOkZhbHNlfSkpO3N5cy5leGl0KDEpCg=='

def stop_stamp(value):
    require(isinstance(value,str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z',value), 'STOP143_TIMESTAMP_REQUIRED')
    value=re.sub(r'(\.\d{6})\d+Z$',r'\1Z',value)
    return datetime.datetime.strptime(value,'%Y-%m-%dT%H:%M:%S.%fZ' if '.' in value else '%Y-%m-%dT%H:%M:%SZ')

def read_current_stop_h2(cid,start,finished):
    # Reuse exact independently reviewed bounded reader bytes, override both window bounds.
    source=base64.b64decode(STOP143_READER_BASE64)
    require(hashlib.sha256(source).hexdigest().upper()==STOP143_READER_SHA,'STOP143_READER_PIN')
    ns={'__name__':'reviewed_current_stop_reader_library'}
    exec(compile(source,'reviewed-bounded-h2-reader','exec'),ns)
    ns['CID']=cid;ns['SINCE']=start;ns['UNTIL']=finished
    timeout = remaining(30)
    p=subprocess.Popen(DOCKER+['logs','--timestamps','--since',start,'--until',finished,cid],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    raw,count=ns['capture_window'](BoundedLogProcess(p), timeout=timeout)
    summary=ns['summarize'](raw,byte_limit=4*1024*1024+1)
    summary['rawByteCount']=count;summary['completeWindowRead']=True
    return summary

def eligible_stop143(stopped,original,cli_code,elapsed,start,reader=None):
    state=stopped['State']
    require(stopped['Image']==STOP143_IMAGE and original['image']==STOP143_IMAGE,'STOP143_UNSUPPORTED_IMAGE')
    require(projection(stopped)==original and cli_code==0 and state.get('Running') is False and state.get('ExitCode')==143 and state.get('OOMKilled') is False and not state.get('Error'),'STOP143_STATE_REJECTED')
    require(type(elapsed) in (float,int) and 0<=elapsed<120,'STOP143_STOP_DEADLINE_REJECTED')
    finished=state.get('FinishedAt');begin=stop_stamp(start);end=stop_stamp(finished)
    require(begin<end and (end-begin).total_seconds()<120,'STOP143_WINDOW_REJECTED')
    result=(reader or read_current_stop_h2)(stopped['Id'],start,finished)
    require(result.get('completeWindowRead') is True and result.get('eventsTruncated') is False,'STOP143_INCOMPLETE_WINDOW')
    require(result.get('sourceWindow')=={'since':start,'until':finished},'STOP143_WINDOW_BINDING')
    counts=result.get('markerCounts',{})
    require(type(counts.get('h2_shutdown_succeeded')) is int and counts['h2_shutdown_succeeded']>0 and counts.get('h2_shutdown_failed')==0,'STOP143_H2_SUCCESS_REQUIRED')
    events=result.get('events',[])
    successes=[e for e in events if e.get('componentClass')=='org.sonatype.nexus.datastore.mybatis.MyBatisDataStore' and 'h2_shutdown_succeeded' in e.get('markerCategories',[]) and begin<stop_stamp(e.get('timestamp'))<end]
    require(len(successes)==counts['h2_shutdown_succeeded'],'STOP143_SUCCESS_EVENT_WINDOW')
    # Public summary contains no raw log messages and does not claim all blob shutdown.
    return {'eligible':True,'scope':'pinned-image-stop143-H2-success-stopped-fileblob-copy-only','stopStartUtc':start,'finishedAt':finished,'stopElapsedSeconds':elapsed,'markerCounts':counts,'successEvents':successes,'rawByteCount':result.get('rawByteCount'),'completeWindowRead':True,'cleanStopExitZero':False,'operationalRestoreVerified':False}

def backup(a):
    global UNCERTAIN_STOP, MUTATION_OBSERVER
    UNCERTAIN_STOP = False
    prior_observer = MUTATION_OBSERVER
    MUTATION_OBSERVER = getattr(a, 'mutation_observer', None)
    try:
        with budget(WORK_SECONDS):
            return _backup(a)
    finally:
        MUTATION_OBSERVER = prior_observer


def _backup(a):
    i, mp, key, ports = source_contract(a)
    original = projection(i)
    running = i['State']['Running']
    out = new_path(a.output)
    require(mp not in out.parents and key.parent not in out.parents, 'OUTPUT_OUTSIDE_SOURCE_REQUIRED')
    out.mkdir(mode=0o700)
    try:
        stop_start = time.monotonic()
        stop_start_utc = datetime.datetime.utcnow().isoformat()+'Z'
        stop_cli_exit = None
        stopped = None
        stop_error_type = None
        try:
            if running:
                # Original normal stop120; capture only CLI status, never stdout/stderr.
                stop_result = command(['stop', '--time', '120', a.container_id])
                stop_cli_exit = stop_result.returncode
            stopped = inspect(a.container_id)
            stop_elapsed = time.monotonic()-stop_start
        except BaseException as e:
            stop_error_type = type(e).__name__
            raise
        finally:
            state = stopped['State'] if stopped is not None else {}
            write_auxiliary_diagnostic(out / 'stop-state.readonly.json', {
                'schema': 1, 'scope': 'safe-stopped-state-diagnostic-only',
                'containerId': a.container_id,
                'imageId': stopped['Image'] if stopped is not None else a.image_id,
                'projectionMatch': projection(stopped) == original if stopped is not None else None,
                'observationAvailable': stopped is not None,
                'running': state.get('Running'), 'exitCode': state.get('ExitCode'),
                'oomKilled': state.get('OOMKilled'), 'finishedAt': state.get('FinishedAt'),
                'errorPresent': bool(state.get('Error')),
                'stopCliExitCode': stop_cli_exit,
                'stopElapsedSeconds': round(time.monotonic() - stop_start, 6),
                'exceptionType': stop_error_type,
                'cleanStopAccepted': stopped is not None and projection(stopped) == original and not state['Running'] and state['ExitCode'] == 0 and stop_cli_exit in (None, 0)
            })
        require(stop_cli_exit in (None, 0), 'DOCKER_COMMAND_FAILED')
        clean_zero = projection(stopped) == original and not stopped['State']['Running'] and stopped['State']['ExitCode'] == 0
        stop_eligibility={'exitCode':0,'mode':'original-zero-exit','guarded143':False}
        if not clean_zero:
            require(stopped['State']['ExitCode']==143,'CLEAN_STOP_REQUIRED')
            proof=eligible_stop143(stopped,original,stop_cli_exit,stop_elapsed,stop_start_utc)
            stop_eligibility={'exitCode':143,'mode':'pinned617de-current-H2-success','guarded143':True,'proof':proof}
            write_auxiliary_diagnostic(out / 'stop143-h2-eligibility.readonly.json',proof)
        # Zero exit retains its original path. Only the exact guarded143 branch reaches copy.

        # No active other container may mount this exact data volume.
        for other in call(['ps', '-q', '--no-trunc']).splitlines():
            require(not any(m.get('Name') == a.volume for m in inspect(other)['Mounts']), 'OTHER_VOLUME_WRITER')
        with tarfile.open(str(out / 'data.tar.gz'), 'w:gz') as t:
            for path in sorted(mp.rglob('*')):
                remaining(READ_SECONDS)
                s = path.lstat()
                require(stat.S_ISDIR(s.st_mode) or (stat.S_ISREG(s.st_mode) and s.st_nlink == 1), 'SOURCE_LINK_OR_SPECIAL')
                t.add(str(path), arcname=str(path.relative_to(mp)), recursive=False)
        with tarfile.open(str(out / 'data.tar.gz'), 'r:gz') as t:
            safe_members(t)
        with open(str(out / 'keys.json'), 'xb') as f:
            f.write(key.read_bytes())
        validate_binding(mp, key)
        with tarfile.open(str(out / 'data.tar.gz'), 'r:gz') as t:
            archive_binding(t, out / 'keys.json')
        call(['save', '--output', str(out / 'image.tar'), a.image_id])
        require(projection(inspect(a.container_id)) == original and not inspect(a.container_id)['State']['Running'], 'SOURCE_CHANGED_DURING_COPY')
        require(digest(out / 'keys.json') == digest(key), 'KEY_CHANGED_DURING_COPY')
        root_stat = mp.stat()
        write_json(out / 'metadata.json', {'schema': 1, 'scope': 'stopped-local-H2-default-file-blobs', 'allStorageInDataVolumeOperatorConfirmed': True, 'dataRootUid': root_stat.st_uid, 'dataRootGid': root_stat.st_gid, 'dataRootMode': stat.S_IMODE(root_stat.st_mode), 'sourceContainerId': a.container_id, 'sourceName': a.container_name, 'sourceVolume': a.volume, 'sourceKeyPath': str(key), 'imageId': a.image_id, 'heap': a.heap, 'containerPorts': ports, 'originalRunning': running, 'originalProjection': original, 'stopEligibility': stop_eligibility})
        for p in out.iterdir():
            os.chmod(str(p), 0o600)
        write_json(out / 'manifest.json', {name: digest(out / name) for name in FILES})
    finally:
        # Applies even after stop or archive failure; original stopped state stays stopped.
        recovery_start = time.monotonic()
        recovery_succeeded = False
        recovery_error_type = None
        recovered = None
        try:
            with budget(RECOVERY_SECONDS, independent=True):
                restore_original(a.container_id, original, running)
            recovery_succeeded = True
        except BaseException as e:
            recovery_error_type = type(e).__name__
            raise RecoveryRequired('ORIGINAL_RECOVERY_REQUIRED') from e
        finally:
            try:
                with budget(READ_SECONDS, independent=True):
                    recovered = inspect(a.container_id)
            except BaseException:
                pass
            state = recovered['State'] if recovered is not None else {}
            write_auxiliary_diagnostic(out / 'recovery-state.readonly.json', {
                'schema': 1, 'scope': 'safe-original-state-recovery-diagnostic-only',
                'containerId': a.container_id,
                'imageId': recovered['Image'] if recovered is not None else a.image_id,
                'originalRunning': running, 'restorationSucceeded': recovery_succeeded,
                'projectionMatch': projection(recovered) == original if recovered is not None else None,
                'observationAvailable': recovered is not None,
                'running': state.get('Running'), 'exitCode': state.get('ExitCode'),
                'oomKilled': state.get('OOMKilled'), 'finishedAt': state.get('FinishedAt'),
                'errorPresent': bool(state.get('Error')),
                'healthConfirmedByRestoreContract': recovery_succeeded and running,
                'recoveryElapsedSeconds': round(time.monotonic() - recovery_start, 6),
                'exceptionType': recovery_error_type
            })
    write_json(out / 'backup.result.json', {'status': 'LOCAL_NEXUS_BACKUP_COMPLETE_ORIGINAL_STATE_RESTORED', 'containerId': a.container_id, 'originalRunning': running, 'manifestSha256': digest(out / 'manifest.json'), 'operationalRestoreVerified': False})
    print('LOCAL_NEXUS_BACKUP_COMPLETE; RESTORE_NOT_YET_VERIFIED')


def verify_artifact(path):
    root = secure_path(path, directory=True, mode=0o700)
    manifest = json.loads(secure_path(str(root / 'manifest.json'), mode=0o600).read_text())
    require(set(manifest) == set(FILES), 'MANIFEST_MEMBERS')
    for name in FILES:
        p = secure_path(str(root / name), mode=0o600)
        require(digest(p) == manifest[name], 'ARTIFACT_HASH_MISMATCH')
    with tarfile.open(str(root / 'data.tar.gz'), 'r:gz') as t:
        members = safe_members(t)
        archive_binding(t, root / 'keys.json')
        require(any(m.name == 'db/nexus.mv.db' and m.isfile() for m in members), 'H2_ARCHIVE_REQUIRED')
    meta = json.loads((root / 'metadata.json').read_text())
    require(meta['schema'] == 1 and meta['scope'] == 'stopped-local-H2-default-file-blobs' and meta.get('allStorageInDataVolumeOperatorConfirmed') is True and IMAGE.fullmatch(meta['imageId']) and re.fullmatch(r'[1-9][0-9]*[mMgG]', meta['heap']), 'ARTIFACT_SCOPE')
    require(meta['dataRootUid'] == 200 and isinstance(meta['dataRootGid'], int) and 0 <= meta['dataRootGid'] <= 65535 and isinstance(meta['dataRootMode'], int) and 0 <= meta['dataRootMode'] <= 0o777 and not meta['dataRootMode'] & 0o002, 'DATA_ROOT_METADATA')
    result = json.loads(secure_path(str(root / 'backup.result.json'), mode=0o600).read_text())
    require(result['status'] == 'LOCAL_NEXUS_BACKUP_COMPLETE_ORIGINAL_STATE_RESTORED' and result['manifestSha256'] == digest(root / 'manifest.json'), 'INCOMPLETE_BACKUP')
    return root, meta


def parse_ports(value, expected):
    result = []
    for pair in value.split(','):
        require(re.fullmatch(r'[0-9]+:[0-9]+', pair), 'PORT_FORMAT')
        inner, outer = map(int, pair.split(':'))
        require(1 <= inner <= 65535 and 1 <= outer <= 65535, 'PORT_RANGE')
        result.append((inner, outer))
    require(sorted(x[0] for x in result) == expected and len(set(x[1] for x in result)) == len(result), 'PORT_SET_OR_DUPLICATE')
    return result


def restore(a):
    with budget(WORK_SECONDS):
        return _restore(a)


def restore_identity(i, a, m, key):
    return (i['Name'] == '/' + a.new_name and i['Image'] == m['imageId'] and
            i.get('Config', {}).get('Labels', {}).get('deployops.nexus.restore') == a.new_name and
            len(i['Mounts']) == 2 and
            any(x.get('Type') == 'volume' and x.get('Name') == a.new_volume and x['Destination'] == '/nexus-data' for x in i['Mounts']) and
            any(x.get('Type') == 'bind' and x['Source'] == str(key) and x['Destination'] == '/run/nexus-secrets/secrets.json' and not x['RW'] for x in i['Mounts']))


def _restore(a):
    root, m = verify_artifact(a.artifact)
    require(NAME.fullmatch(a.new_name) and NAME.fullmatch(a.new_volume) and a.new_volume != m['sourceVolume'] and a.new_name != m['sourceName'], 'DISTINCT_RESTORE_TARGET_REQUIRED')
    require(a.new_name not in call(['ps', '-a', '--format', '{{.Names}}']).splitlines() and a.new_volume not in call(['volume', 'ls', '--format', '{{.Name}}']).splitlines(), 'RESTORE_TARGET_EXISTS')
    network = json.loads(call(['network', 'inspect', a.network]))[0]
    require(network['Name'] == a.network and network['Driver'] == 'bridge' and network['Internal'], 'PREEXISTING_INTERNAL_NETWORK_REQUIRED')
    ports = parse_ports(a.ports, m['containerPorts'])
    for _, port in ports:
        with socket.socket() as s:
            s.bind(('127.0.0.1', port))
    keydir = new_path(a.new_key_directory)
    require(root not in keydir.parents, 'RESTORE_KEY_OUTSIDE_ARTIFACT')
    result = new_path(a.result)
    require(result.parent != keydir, 'RESULT_OUTSIDE_KEY_DIRECTORY')
    # Validate archive and all collisions before any Docker mutation.
    call(['load', '--input', str(root / 'image.tar')])
    require(call(['image', 'inspect', '--format', '{{.Id}}', m['imageId']]) == m['imageId'], 'RESTORED_IMAGE_ID')
    prepare_key_directory(keydir)
    key = keydir / 'secrets.json'
    with open(str(key), 'xb') as f:
        f.write((root / 'keys.json').read_bytes())
    os.chown(str(key), 0, 200)
    os.chmod(str(key), 0o440)
    validate_key(key)
    call(['volume', 'create', '--driver', 'local', '--label', 'deployops.nexus.restore=' + a.new_name, a.new_volume])
    mp = volume_path(a.new_volume)
    require(not any(mp.iterdir()), 'NEW_RESTORE_VOLUME_NOT_EMPTY')
    with tarfile.open(str(root / 'data.tar.gz'), 'r:gz') as t:
        for member in safe_members(t):
            remaining(READ_SECONDS)
            t.extract(member, str(mp))
    validate_binding(mp, key)
    os.chown(str(mp), m['dataRootUid'], m['dataRootGid'])
    os.chmod(str(mp), m['dataRootMode'])
    args = ['run', '--detach', '--pull', 'never', '--name', a.new_name, '--network', a.network, '--label', 'deployops.nexus.restore=' + a.new_name]
    for inner, outer in ports:
        args += ['--publish', '127.0.0.1:{0}:{1}'.format(outer, inner)]
    heap = '-Xms{0} -Xmx{0} -XX:MaxDirectMemorySize={0} -Djava.util.prefs.userRoot=/nexus-data/javaprefs'.format(m['heap'])
    args += ['--env', 'INSTALL4J_ADD_VM_PARAMS=' + heap, '--env', 'NEXUS_SECRETS_KEY_FILE=/run/nexus-secrets/secrets.json', '--mount', 'type=volume,source=' + a.new_volume + ',target=/nexus-data', '--volume', str(key) + ':/run/nexus-secrets/secrets.json:ro,Z', m['imageId']]
    cid = None
    try:
        cid = call(args)
        require(re.fullmatch(r'[0-9a-f]{64}', cid), 'RESTORE_CID')
        i = inspect(cid)
        require(restore_identity(i, a, m, key), 'RESTORE_IDENTITY_MOUNTS')
        wait_health(cid)
        write_json(result, {'status': 'LOCAL_NEXUS_RESTORE_HEALTH_READY_FUNCTIONAL_VALIDATION_PENDING', 'containerId': cid, 'imageId': m['imageId'], 'volume': a.new_volume, 'ports': ports, 'keySha256': digest(key), 'artifactManifestSha256': digest(root / 'manifest.json'), 'originalVolumeTouched': False, 'authenticationArtifactsRegistryVerified': False})
    except BaseException as error:
        if not isinstance(error, MutationUncertain) and cid and re.fullmatch(r'[0-9a-f]{64}', cid):
            with budget(135 + READ_SECONDS, independent=True):
                require(restore_identity(inspect(cid), a, m, key), 'RESTORE_CLEANUP_IDENTITY_REQUIRED')
                call(['stop', '--time', '120', cid])
        raise
    print('LOCAL_NEXUS_RESTORE_HEALTH_READY; FUNCTIONAL_VALIDATION_PENDING')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='action')
    b = sub.add_parser('backup')
    for name in ('container-id', 'container-name', 'image-id', 'volume', 'key-file', 'heap', 'output'):
        b.add_argument('--' + name, required=True)
    b.add_argument('--all-storage-in-data-volume', action='store_true', help='After normal Nexus UI confirms H2 and all file blob paths below /nexus-data; external storage unsupported')
    r = sub.add_parser('restore')
    for name in ('artifact', 'new-name', 'new-volume', 'new-key-directory', 'network', 'ports', 'result'):
        r.add_argument('--' + name, required=True)
    a = p.parse_args()
    require(a.action in ('backup', 'restore'), 'ACTION_REQUIRED')
    require(os.geteuid() == 0 and sys.platform.startswith('linux'), 'LINUX_ROOT_REQUIRED')
    os.umask(0o077)
    (backup if a.action == 'backup' else restore)(a)


if __name__ == '__main__':
    try:
        main()
    except BaseException as e:
        if isinstance(e, SystemExit):
            raise
        # Static category only; exception text/CLI stderr can contain secrets.
        category = str(e) if isinstance(e, ValueError) and re.fullmatch(r'[A-Z_]+', str(e)) else 'DETAIL_SUPPRESSED'
        print('NEXUS_OPERATION_FAILED type=' + type(e).__name__ + ' category=' + category, file=sys.stderr)
        sys.exit(1)
