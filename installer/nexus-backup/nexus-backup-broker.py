#!/usr/libexec/platform-python
"""Root-local BACKUP and isolated RESTORE queues. No HTTP server or secret export."""
import contextlib
import datetime
import hashlib
import io
import json
import os
import posixpath
import re
import signal
import stat
import sys
import tempfile
from pathlib import Path, PurePosixPath
from types import SimpleNamespace

BASE = Path('/var/lib/deployops/nexus-backup')
CONFIG = Path('/etc/deployops/nexus-backup.json')
LIBRARY = Path('/usr/local/lib/deployops/nexus-backup/nexus-local-backup-restore.py')
LIBRARY_SHA = 'fe018c4f77a2ff3c5ab2ef4b3ec3337b518a266358373c0f4619df4b4e77037a'
FIELDS = {'schema', 'requestId', 'status', 'stage', 'artifactId', 'manifestSha256',
          'errorCode', 'originalStateRestored', 'completedAt'}
STATUSES = {'RUNNING', 'COMPLETED', 'FAILED', 'RECOVERY_REQUIRED', 'RECONCILING'}
STAGES = {'PREFLIGHT', 'STOPPING', 'COPYING', 'VERIFYING', 'RESTORING_ORIGINAL', 'DONE'}
ERRORS = {'PREFLIGHT_REJECTED', 'BACKUP_FAILED', 'ORIGINAL_RECOVERY_FAILED', 'RESULT_UNCERTAIN'}
SHUTDOWN_REQUESTED = False


class ShutdownBeforeExecution(Exception):
    pass


def request_shutdown(signum, frame):
    # Never raise from the signal handler: interruption inside the reviewed recovery
    # finally could leave the original stopped. Finish this one job and durable result.
    global SHUTDOWN_REQUESTED
    SHUTDOWN_REQUESTED = True


def install_signal_handlers():
    signal.signal(signal.SIGTERM, request_shutdown)
    signal.signal(signal.SIGINT, request_shutdown)


def require(ok):
    if not ok:
        raise ValueError('BOUNDARY_REJECTED')


def positive_id(value):
    return type(value) is int and 0 < value <= 9223372036854775807


def parse_unique(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs)


def path_parents(path):
    require(path.is_absolute() and str(path.resolve()) == str(path))
    for node in path.parents:
        info = node.lstat()
        require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode))
        require(info.st_uid == 0 and not stat.S_IMODE(info.st_mode) & 0o022)


def read_file(path, owner, mode, limit=16384, group=None):
    path = Path(path)
    path_parents(path)
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == owner and info.st_nlink == 1)
        require(group is None or info.st_gid == group)
        require(stat.S_IMODE(info.st_mode) == mode and info.st_size <= limit)
        data = os.read(fd, limit + 1)
        require(len(data) <= limit)
        return data
    finally:
        os.close(fd)


def validate_config(c):
    require(type(c) is dict and set(c) == {'schema', 'containerName', 'imageId', 'volume',
        'keyFile', 'heap', 'backupRoot', 'appUser', 'externalWritersManaged', 'allStorageInDataVolume'})
    require(type(c['schema']) is int and c['schema'] == 1)
    for key in ('externalWritersManaged', 'allStorageInDataVolume'):
        require(type(c[key]) is bool)
    for key in ('containerName', 'volume', 'appUser'):
        require(type(c[key]) is str and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', c[key]))
    require(c['imageId'] == 'sha256:617de9147a594bd1fb8d942cd56f1135ebb61cc622566269907e178377b3fdbd')
    require(type(c['heap']) is str and re.fullmatch(r'[1-9][0-9]*[mMgG]', c['heap']))
    for key in ('keyFile', 'backupRoot'):
        require(type(c[key]) is str and re.fullmatch(r'/[A-Za-z0-9_./-]+', c[key]))
        p = PurePosixPath(c[key])
        require(posixpath.normpath(c[key]) == c[key] and p != PurePosixPath('/'))
    return c


def validate_request(job, request_id):
    require(type(job) is dict and set(job) == {'schema', 'requestId', 'action'})
    require(type(job['schema']) is int and job['schema'] == 1)
    require(positive_id(job['requestId']) and job['requestId'] == request_id and job['action'] == 'BACKUP')


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()


def validate_check(job, request_id, check_id):
    require(type(job) is dict and set(job) == {'schema', 'requestId', 'action', 'checkId'})
    require(type(job['schema']) is int and job['schema'] == 1 and job['action'] == 'CHECK_RECOVERY')
    require(positive_id(job['requestId']) and job['requestId'] == request_id)
    require(positive_id(job['checkId']) and job['checkId'] == check_id)


def result(request_id, status, stage, restored=False, artifact=None, manifest=None, error=None):
    data = {'schema': 1, 'requestId': request_id, 'status': status, 'stage': stage,
            'artifactId': artifact, 'manifestSha256': manifest, 'errorCode': error,
            'originalStateRestored': restored,
            'completedAt': None if status == 'RUNNING' else datetime.datetime.now(datetime.timezone.utc).isoformat()}
    validate_result(data, request_id)
    return data


def validate_result(data, request_id):
    require(type(data) is dict and set(data) == FIELDS)
    require(type(data['schema']) is int and data['schema'] == 1 and positive_id(data['requestId']) and data['requestId'] == request_id)
    require(data['status'] in STATUSES and data['stage'] in STAGES and type(data['originalStateRestored']) is bool)
    require(data['errorCode'] is None or data['errorCode'] in ERRORS)
    require(data['artifactId'] is None or data['artifactId'] == 'nexus-backup-' + str(request_id))
    require(data['manifestSha256'] is None or (type(data['manifestSha256']) is str and re.fullmatch(r'[0-9a-f]{64}', data['manifestSha256'])))
    if data['status'] == 'COMPLETED':
        require(data['stage'] == 'DONE' and data['originalStateRestored'] is True and data['errorCode'] is None)
        require(data['artifactId'] is not None and data['manifestSha256'] is not None and data['completedAt'] is not None)
    return data


def releases_maintenance(data):
    return data['status'] in ('COMPLETED', 'FAILED') and data['originalStateRestored'] is True


class Store:
    def __init__(self, base, app_uid, app_gid):
        self.base, self.app_uid, self.app_gid = base, app_uid, app_gid

    def check_directories(self):
        for name, owner, mode in [('', 0, 0o755), ('inbox', self.app_uid, 0o700),
                                 ('results', 0, 0o750), ('state', 0, 0o700), ('state/checks', 0, 0o700)]:
            p = self.base / name
            path_parents(p)
            s = p.lstat()
            require(stat.S_ISDIR(s.st_mode) and s.st_uid == owner and stat.S_IMODE(s.st_mode) == mode)
            if name == 'results':
                require(s.st_gid == self.app_gid)

    def write(self, path, value, exclusive=False):
        path_parents(path)
        raw = json.dumps(value, sort_keys=True).encode('utf-8') + b'\n'
        if exclusive:
            target = path
            fd = os.open(str(target), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        else:
            fd, filename = tempfile.mkstemp(prefix='.' + path.name + '-', dir=str(path.parent))
            target = Path(filename)
        try:
            with os.fdopen(fd, 'wb') as f:
                f.write(raw)
                f.flush()
                os.fsync(f.fileno())
                if path.parent.name == 'results':
                    os.fchown(f.fileno(), 0, self.app_gid)
                    os.fchmod(f.fileno(), 0o640)
                elif path.parent.name == 'state' and path.name.endswith('.mutations.json'):
                    os.fchown(f.fileno(), 0, 0)
                    os.fchmod(f.fileno(), 0o600)
            if not exclusive:
                os.replace(str(target), str(path))
            dir_fd = os.open(str(path.parent), os.O_DIRECTORY | os.O_RDONLY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
        except BaseException:
            # Partial durable records remain conservative; never silently run again.
            raise

    def ids(self, directory):
        for p in sorted((self.base / directory).iterdir()):
            if re.fullmatch(r'[1-9][0-9]{0,18}\.json', p.name) and positive_id(int(p.stem)):
                yield int(p.stem)

    def claim_exists(self, request_id):
        return os.path.lexists(str(self.base / 'state' / (str(request_id) + '.json')))

    def claim(self, request_id):
        self.write(self.base / 'state' / (str(request_id) + '.json'), {'schema': 1, 'requestId': request_id}, exclusive=True)

    def request(self, request_id):
        path = self.base / 'inbox' / (str(request_id) + '.json')
        return self.read_inbox(path)

    def read_inbox(self, path):
        # Inbox parent is intentionally app-owned; root ancestors remain protected.
        # Capture the leaf once without following symlinks or waiting on special files.
        fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            s = os.fstat(fd)
            require(stat.S_ISREG(s.st_mode) and s.st_uid == self.app_uid and s.st_gid == self.app_gid)
            require(s.st_nlink == 1 and stat.S_IMODE(s.st_mode) == 0o600 and s.st_size <= 1024)
            raw = os.read(fd, 1025)
            require(len(raw) <= 1024)
            return parse_unique(raw)
        finally:
            os.close(fd)

    def get_result(self, request_id):
        p = self.base / 'results' / (str(request_id) + '.json')
        return validate_result(parse_unique(read_file(p, 0, 0o640)), request_id)

    def publish(self, value):
        self.write(self.base / 'results' / (str(value['requestId']) + '.json'), value)

    def save_baseline(self, request_id, value):
        self.write(self.base / 'state' / (str(request_id) + '.baseline.json'), value, exclusive=True)

    def baseline(self, request_id):
        return parse_unique(read_file(self.base / 'state' / (str(request_id) + '.baseline.json'), 0, 0o600))

    def mutation_path(self, request_id):
        require(positive_id(request_id))
        return self.base / 'state' / (str(request_id) + '.mutations.json')

    def mutation_ledger(self, request_id):
        path = self.mutation_path(request_id)
        require(os.path.lexists(str(path)))
        value = parse_unique(read_file(path, 0, 0o600, group=0))
        require(type(value) is dict and set(value) == {'schema', 'requestId', 'events'})
        require(type(value['schema']) is int and value['schema'] == 1 and value['requestId'] == request_id and positive_id(value['requestId']))
        require(type(value['events']) is list and 0 <= len(value['events']) <= 32)
        for index, event in enumerate(value['events'], 1):
            require(type(event) is dict and set(event) == {'sequence', 'kind', 'state'})
            require(type(event['sequence']) is int and event['sequence'] == index)
            require(event['kind'] in ('stop', 'start', 'save'))
            require(event['state'] in ('inflight', 'completed'))
        return value

    def initialize_mutation_ledger(self, request_id):
        # New eligible request only. A crash after fsynced empty ledger but before
        # claim has sent no mutation; adopt only that exact protected empty proof.
        require(not self.claim_exists(request_id))
        validate_request(self.request(request_id), request_id)
        require(not os.path.lexists(str(self.base / 'state' / (str(request_id) + '.baseline.json'))))
        require(not os.path.lexists(str(self.base / 'results' / (str(request_id) + '.json'))))
        path = self.mutation_path(request_id)
        if os.path.lexists(str(path)):
            require(self.mutation_ledger(request_id)['events'] == [])
            return
        self.write(path,
                   {'schema': 1, 'requestId': request_id, 'events': []}, exclusive=True)

    def mutation_uncertain(self, request_id):
        try:
            return any(event['state'] != 'completed' for event in self.mutation_ledger(request_id)['events'])
        except Exception:
            return True  # Missing proof/malformed protected marker is never clearance.

    def begin_mutation(self, request_id, kind):
        require(kind in ('stop', 'start', 'save'))
        value = self.mutation_ledger(request_id)
        require(len(value['events']) < 32)
        sequence = len(value['events']) + 1
        value['events'].append({'sequence': sequence, 'kind': kind, 'state': 'inflight'})
        self.write(self.mutation_path(request_id), value)
        return sequence

    def complete_mutation(self, request_id, sequence):
        value = self.mutation_ledger(request_id)
        require(type(sequence) is int and 1 <= sequence <= len(value['events']))
        event = value['events'][sequence - 1]
        require(event['state'] == 'inflight')
        event['state'] = 'completed'
        self.write(self.mutation_path(request_id), value)

    def mutation_observer(self, request_id):
        # Worker lock serializes these callbacks; no clearing/resolution API exists.
        return SimpleNamespace(begin=lambda kind: self.begin_mutation(request_id, kind),
                               complete=lambda token: self.complete_mutation(request_id, token))

    def checks(self):
        for p in sorted((self.base / 'inbox').iterdir()):
            m = re.fullmatch(r'([1-9][0-9]{0,18})-([1-9][0-9]{0,18})\.check\.json', p.name)
            if m and positive_id(int(m[1])) and positive_id(int(m[2])):
                yield int(m[1]), int(m[2])

    def claim_check(self, request_id, check_id):
        p = self.base / 'state' / 'checks' / ('%s-%s.json' % (request_id, check_id))
        if os.path.lexists(str(p)):
            return False
        self.write(p, {'schema': 1, 'requestId': request_id, 'checkId': check_id}, exclusive=True)
        return True

    def publish_check(self, value, check_id):
        validate_result(value, value['requestId'])
        checked = dict(value, checkId=check_id)
        self.write(self.base / 'results' / ('%s-%s.check.json' % (value['requestId'], check_id)), checked)


class Executor:
    def __init__(self, config, library):
        self.config, self.lib = config, library
        self.started = False
        self.original = None
        self.cid = None

    def prepare(self, request_id):
        c = self.config
        require(c['externalWritersManaged'] is True and c['allStorageInDataVolume'] is True)
        i = self.lib.inspect(c['containerName'])
        require(re.fullmatch(r'[0-9a-f]{64}', i['Id']) and i['State']['Running'] is True)
        self.cid = i['Id']
        self.args = SimpleNamespace(container_id=self.cid, container_name=c['containerName'],
            image_id=c['imageId'], volume=c['volume'], key_file=c['keyFile'], heap=c['heap'],
            output=str(Path(c['backupRoot']) / ('nexus-backup-' + str(request_id))), all_storage_in_data_volume=True)
        original, _, _, _ = self.lib.source_contract(self.args)
        require(original['Id'] == self.cid and original['Image'] == c['imageId'] and original['State']['Running'] is True)
        self.original = self.lib.projection(original)
        self.lib.new_path(self.args.output)

    def baseline(self, request_id):
        return {'schema': 1, 'requestId': request_id, 'containerId': self.cid,
                'imageId': self.config['imageId'], 'projectionSha256': fingerprint(self.original),
                'configSha256': fingerprint(self.config), 'originalRunning': True}

    def check_recovery(self, baseline, request_id):
        require(type(baseline) is dict and set(baseline) == {'schema', 'requestId', 'containerId',
            'imageId', 'projectionSha256', 'configSha256', 'originalRunning'})
        require(type(baseline['schema']) is int and baseline['schema'] == 1)
        require(positive_id(baseline['requestId']) and baseline['requestId'] == request_id)
        require(baseline['originalRunning'] is True and baseline['imageId'] == self.config['imageId'])
        require(baseline['configSha256'] == fingerprint(self.config))
        for field in ('containerId', 'projectionSha256', 'configSha256'):
            require(type(baseline[field]) is str and re.fullmatch(r'[0-9a-f]{64}', baseline[field]))
        self.cid = baseline['containerId']
        self.args = SimpleNamespace(container_id=self.cid, container_name=self.config['containerName'],
            image_id=self.config['imageId'], volume=self.config['volume'], key_file=self.config['keyFile'],
            heap=self.config['heap'], all_storage_in_data_volume=True)
        current, _, _, _ = self.lib.source_contract(self.args)
        require(current['Id'] == self.cid and current['Image'] == baseline['imageId'])
        require(current['State']['Running'] is True)
        require(fingerprint(self.lib.projection(current)) == baseline['projectionSha256'])
        self.lib.wait_health(self.cid)
        return True

    def backup(self):
        if SHUTDOWN_REQUESTED:
            raise ShutdownBeforeExecution()
        self.started = True
        # The reviewed library owns stop/restore-finally; no raw log/error reaches systemd.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.lib.backup(self.args)

    def verify(self):
        root, meta = self.lib.verify_artifact(self.args.output)
        require(meta['sourceContainerId'] == self.cid and meta['sourceName'] == self.config['containerName'])
        require(meta['imageId'] == self.config['imageId'] and meta['sourceVolume'] == self.config['volume'])
        require(self.recovered())
        return self.lib.digest(root / 'manifest.json')

    def recovered(self):
        if not self.started:
            return True
        try:
            i = self.lib.inspect(self.cid)
            require(self.lib.projection(i) == self.original and i['State']['Running'] is True)
            self.lib.wait_health(self.cid)
            return True
        except Exception:
            return False


def process_job(store, request_id, executor):
    # Cover shutdown after run_queue's check/factory evaluation, before any claim.
    if SHUTDOWN_REQUESTED:
        return None
    if store.claim_exists(request_id):
        try:
            previous = store.get_result(request_id)
            if previous['status'] == 'COMPLETED' or (not store.mutation_uncertain(request_id) and (releases_maintenance(previous) or previous['status'] == 'RECOVERY_REQUIRED')):
                return previous
        except Exception:
            pass
        uncertain = result(request_id, 'RECOVERY_REQUIRED' if store.mutation_uncertain(request_id) else 'RECONCILING',
                           'RESTORING_ORIGINAL', error='RESULT_UNCERTAIN')
        store.publish(uncertain)
        return uncertain
    store.initialize_mutation_ledger(request_id)
    store.claim(request_id)  # Durable BEFORE any stop; a crash never automatically replays.
    prepared = False
    try:
        validate_request(store.request(request_id), request_id)
        store.publish(result(request_id, 'RUNNING', 'PREFLIGHT'))
        if SHUTDOWN_REQUESTED:
            raise ShutdownBeforeExecution()
        executor.prepare(request_id)
        prepared = True
        store.save_baseline(request_id, executor.baseline(request_id))
        if SHUTDOWN_REQUESTED:
            raise ShutdownBeforeExecution()
        store.publish(result(request_id, 'RUNNING', 'STOPPING'))
        executor.args.mutation_observer = store.mutation_observer(request_id)
        executor.backup()
        require(not store.mutation_uncertain(request_id))
        store.publish(result(request_id, 'RUNNING', 'VERIFYING'))
        manifest = executor.verify()
        final = result(request_id, 'COMPLETED', 'DONE', True,
                       'nexus-backup-' + str(request_id), manifest)
    except ShutdownBeforeExecution:
        # Durable claim exists, but stop has not started. Preserve it and hold the
        # handoff for reconciliation; do not blame config or automatically replay.
        final = result(request_id, 'RECONCILING', 'PREFLIGHT', True, error='RESULT_UNCERTAIN')
    except Exception as error:
        # Uncertainty wins over a momentary running/healthy observation.
        uncertain = (store.mutation_uncertain(request_id) or
                     isinstance(error, (executor.lib.MutationUncertain, executor.lib.RecoveryRequired)))
        restored = False if uncertain else executor.recovered() if prepared else True
        final = result(request_id, 'FAILED' if restored else 'RECOVERY_REQUIRED',
                       'DONE' if restored else 'RESTORING_ORIGINAL', restored,
                       error=('PREFLIGHT_REJECTED' if not prepared else
                              'BACKUP_FAILED' if restored else 'ORIGINAL_RECOVERY_FAILED'))
    store.publish(final)
    return final


def run_queue(store, factory):
    if SHUTDOWN_REQUESTED:
        return
    # The same exclusive worker lock proves no backup worker runs during a check.
    for request_id, check_id in store.checks():
        if process_check(store, request_id, check_id, factory()):
            return
    # Any durable uncertain/running claim holds the service-wide maintenance handoff.
    for request_id in store.ids('state'):
        try:
            old = store.get_result(request_id)
            if old['status'] == 'COMPLETED' or (releases_maintenance(old) and not store.mutation_uncertain(request_id)):
                continue
        except Exception:
            pass
        process_job(store, request_id, factory())
        return
    for request_id in store.ids('inbox'):
        if not store.claim_exists(request_id):
            process_job(store, request_id, factory())
            return


def process_check(store, request_id, check_id, executor):
    if SHUTDOWN_REQUESTED:
        return False
    if not store.claim_check(request_id, check_id):
        return False  # Permanent check token: never replay or rewrite a stale result.
    final = result(request_id, 'RECONCILING', 'RESTORING_ORIGINAL', error='RESULT_UNCERTAIN')
    eligible_primary = False
    try:
        validate_check(store.read_inbox(store.base / 'inbox' / ('%s-%s.check.json' % (request_id, check_id))), request_id, check_id)
        require(store.claim_exists(request_id))
        previous = store.get_result(request_id)
        # A prior check can restore the primary result before Java accepts its
        # checkId. An explicitly superseding check must revalidate the same baseline.
        # Never promote or re-check a successful backup as a recovery operation.
        known_recovered = (previous['status'] == 'FAILED' and previous['stage'] == 'DONE'
                           and previous['originalStateRestored'] is True
                           and previous['errorCode'] == 'BACKUP_FAILED'
                           and previous['artifactId'] is None and previous['manifestSha256'] is None)
        require(previous['status'] in ('RECOVERY_REQUIRED', 'RECONCILING', 'RUNNING') or known_recovered)
        eligible_primary = True
        baseline = store.baseline(request_id)
        if SHUTDOWN_REQUESTED:
            raise ShutdownBeforeExecution()
        try:
            # Restart cannot forget a pending daemon operation. A healthy running
            # observation is insufficient while any durable event is unresolved.
            require(not store.mutation_uncertain(request_id))
            executor.check_recovery(baseline, request_id)
            final = result(request_id, 'FAILED', 'DONE', True, error='BACKUP_FAILED')
        except Exception:
            final = result(request_id, 'RECOVERY_REQUIRED', 'RESTORING_ORIGINAL', error='ORIGINAL_RECOVERY_FAILED')
    except Exception:
        pass  # Missing/malformed proof remains unknown; no state-changing recovery.
    # Fresh failed/unknown proof must invalidate an old recovered primary BEFORE
    # publishing its check result. If this write fails, no check result is published.
    # Malformed/stale/unrelated checks cannot alter a primary result.
    if eligible_primary:
        store.publish(final)
    store.publish_check(final, check_id)
    return True


def load_library():
    raw = read_file(LIBRARY, 0, 0o600, 100000)
    require(hashlib.sha256(raw).hexdigest() == LIBRARY_SHA)
    namespace = {'__name__': 'reviewed_nexus_backup_library'}
    exec(compile(raw, str(LIBRARY), 'exec'), namespace)
    return SimpleNamespace(**namespace)


def load_restore_worker():
    source = LIBRARY.parent / 'nexus-isolated-restore-worker.py'
    raw = read_file(source, 0, 0o600, 100000, group=0)
    require(hashlib.sha256(raw).hexdigest() == '0cf545a418c58a6296cbfc6bb638925dcac1b394509076f8a65ab8e1503cddfd')
    namespace = {'__name__': 'reviewed_nexus_restore_worker'}
    exec(compile(raw, str(source), 'exec'), namespace)
    return SimpleNamespace(**namespace)


def load_promotion_worker():
    source = LIBRARY.parent / 'nexus-promotion-worker.py'
    raw = read_file(source, 0, 0o600, 100000, group=0)
    namespace = {'__name__': 'nexus_promotion_worker'}
    exec(compile(raw, str(source), 'exec'), namespace)
    return SimpleNamespace(**namespace)


def source_readiness(config, library):
    """Run the same read-only source contract inside the real service sandbox."""
    if not config['externalWritersManaged'] or not config['allStorageInDataVolume']:
        return False, 'OPERATOR_STORAGE_AND_WRITER_CONFIRMATION_REQUIRED'
    try:
        current = library.inspect(config['containerName'])
        require(current['State']['Running'] is True)
        args = SimpleNamespace(container_id=current['Id'], container_name=config['containerName'],
            image_id=config['imageId'], volume=config['volume'], key_file=config['keyFile'],
            heap=config['heap'], all_storage_in_data_volume=True)
        library.source_contract(args)
        require(library.call(['exec', current['Id'], 'curl', '--noproxy', '*', '-sS',
            '-o', '/dev/null', '--max-time', '3', '-w', '%{http_code}',
            'http://127.0.0.1:8081/service/rest/v1/status']) == '200')
        return True, None
    except Exception:
        # A stable category, never a raw path, key, command or exception message.
        return False, 'SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE'


def main():
    import fcntl
    import pwd
    require(os.geteuid() == 0 and sys.platform.startswith('linux'))
    install_signal_handlers()
    os.umask(0o077)
    result_dir = BASE / 'results'
    path_parents(result_dir)
    info = result_dir.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o750)
    state_dir = BASE / 'state'
    path_parents(state_dir)
    state_info = state_dir.lstat()
    require(stat.S_ISDIR(state_info.st_mode) and state_info.st_uid == 0 and stat.S_IMODE(state_info.st_mode) == 0o700)
    lock = os.open(str(BASE / 'state' / 'broker.lock'), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    locked = False
    try:
        lock_info = os.fstat(lock)
        require(stat.S_ISREG(lock_info.st_mode) and lock_info.st_uid == 0 and lock_info.st_nlink == 1 and stat.S_IMODE(lock_info.st_mode) == 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        locked = True
        # Keep the last completed probe visible while computing the next snapshot.
        # Every worker still validates its actual source contract before mutation.
        c = validate_config(parse_unique(read_file(CONFIG, 0, 0o600)))
        account = pwd.getpwnam(c['appUser'])
        require(account.pw_uid != 0)
        store = Store(BASE, account.pw_uid, account.pw_gid)
        store.check_directories()
        lib = load_library()
        restore = load_restore_worker()
        restore_config = restore.validate_config(parse_unique(read_file(restore.CONFIG, 0, 0o600, group=0)))
        ready, readiness_code = source_readiness(c, lib)
        promotion = load_promotion_worker()
        store.write(BASE / 'results' / 'ready.json', {'schema': 1, 'ready': ready, 'restoreReady': ready and restore_config['enabled'],
                                                   'externalWritersManaged': c['externalWritersManaged'],
                                                   'promotionReady': ready and restore_config['enabled'],
                                                   'readinessCode': readiness_code})
        api = SimpleNamespace(**dict(globals(), shutdown_requested=lambda: SHUTDOWN_REQUESTED))
        restore_active = restore.run_queue(api, store, c, restore_config, lib)
        promotion_active = False
        if not restore_active and not SHUTDOWN_REQUESTED:
            promotion_active = promotion.run_queue(api, store, c, lib, ready=ready)
        if ready and not restore_active and not promotion_active:
            run_queue(store, lambda: Executor(c, lib))
    except BaseException:
        if locked:
            # Configuration/import/probe failures must invalidate a prior success,
            # including failures before the app account or Store can be resolved.
            Store(BASE, -1, info.st_gid).write(result_dir / 'ready.json',
                {'schema': 1, 'ready': False, 'restoreReady': False,
                 'promotionReady': False, 'externalWritersManaged': False,
                 'readinessCode': 'SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE'})
        raise
    finally:
        os.close(lock)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # No raw exception, inspect, environment, password, key hash, or archive path.
        print('NEXUS_BACKUP_BROKER_BOUNDARY_REJECTED', file=sys.stderr)
        sys.exit(1)
