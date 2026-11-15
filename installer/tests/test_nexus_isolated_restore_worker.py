"""Pure worker tests. Docker, root filesystem ownership and GUI are not exercised."""
import contextlib
import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace

SOURCE = Path(__file__).resolve().parents[1] / 'nexus-backup' / 'nexus-isolated-restore-worker.py'
SPEC = importlib.util.spec_from_file_location('restore_worker', str(SOURCE))
W = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(W)
LIBRARY_SOURCE = SOURCE.with_name('nexus-local-backup-restore.py')
LIBRARY_SPEC = importlib.util.spec_from_file_location('restore_library', str(LIBRARY_SOURCE))
REAL_LIBRARY = importlib.util.module_from_spec(LIBRARY_SPEC)
LIBRARY_SPEC.loader.exec_module(REAL_LIBRARY)
MUTATION_OBSERVER = None
SHA = 'a' * 64
CID = 'b' * 64
IMAGE = 'sha256:617de9147a594bd1fb8d942cd56f1135ebb61cc622566269907e178377b3fdbd'


class Backend:
    def __init__(self, root):
        self.base = root
        self.app_gid = 50
        for name in ('inbox', 'state', 'results'):
            (root / name).mkdir()

    def write(self, path, value, exclusive=False):
        with path.open('x' if exclusive else 'w') as stream:
            json.dump(value, stream)

    def read_inbox(self, path):
        return json.loads(path.read_text())

    def get_result(self, request_id):
        return {'status': 'COMPLETED', 'originalStateRestored': True,
                'artifactId': 'nexus-backup-2', 'manifestSha256': SHA}

    def ids(self, area):
        return iter(())


class Library:
    WORK_SECONDS = 1800

    def __init__(self, root, timeout=False, changed=False):
        self.root, self.timeout, self.changed = root, timeout, changed
        self.calls, self.applied = [], False
        self.original = {'Id': 'c' * 64, 'Name': '/worksystem-nexus', 'Image': IMAGE,
                         'Mounts': [{'Type': 'volume', 'Name': 'nexus-data', 'Destination': '/nexus-data'}],
                         'State': {'Running': True, 'StartedAt': 'original'}, 'Config': {'Labels': {}}}

    @contextlib.contextmanager
    def budget(self, seconds, independent=False):
        yield

    def inspect(self, cid):
        if cid == CID:
            return {'State': {'Running': True},'NetworkSettings':{'Ports':{str(p)+'/tcp':[{'HostIp':'127.0.0.1','HostPort':str(18003+i)}] for i,p in enumerate([8081,5000,5001])}}}
        value = json.loads(json.dumps(self.original))
        if self.applied and self.changed:
            value['State']['StartedAt'] = 'changed'
        return value

    def call(self, args):
        self.calls.append(args)
        if args[0] == 'ps' or args[:2] in (['volume','ls'],['network','ls']):
            return ''
        token = MUTATION_OBSERVER.begin(args[0])
        if args[0] == 'run' and self.timeout:
            self.applied = True
            raise ValueError('SIMULATED_DAEMON_APPLIED_CLIENT_TIMEOUT')
        MUTATION_OBSERVER.complete(token)
        return CID

    def new_path(self, path):
        value = Path(path)
        W.require(not value.exists())
        return value

    def verify_artifact(self, path):
        return self.root, {'containerPorts': [8081, 5000, 5001], 'imageId': IMAGE}

    def digest(self, path):
        return SHA

    def restore_identity(self, value, args, meta, key):
        return True  # Identity internals belong to the unchanged reviewed helper tests.

    def restore(self, args):
        for command in ('load', 'volume', 'run'):
            self.call([command])
        self.applied = True
        Path(args.result).write_text(json.dumps({
            'status': 'LOCAL_NEXUS_RESTORE_HEALTH_READY_FUNCTIONAL_VALIDATION_PENDING',
            'artifactManifestSha256': SHA, 'originalVolumeTouched': False,
            'authenticationArtifactsRegistryVerified': False, 'containerId': CID,
            'keySha256': 'PRIVATE_SENTINEL_NOT_PUBLIC'}))


class BridgeLibrary(Library):
    def __init__(self, root, mutation=None, changed_after=False):
        super().__init__(root)
        self.mutation, self.changed_after, self.clone_reads = mutation, changed_after, 0
        self.net_id, self.address = 'd' * 64, '172.19.0.2'
        self.net = {'Id': self.net_id, 'Name': 'nexus-restore-net-1', 'Driver': 'bridge',
                    'Internal': True, 'ConfigOnly': False,
                    'Labels': {'deployops.nexus.restore': 'nexus-restore-1'},
                    'Containers': {CID: {'Name': 'nexus-restore-1', 'EndpointID': 'e' * 64,
                                         'IPv4Address': self.address + '/16'}}}

    def inspect(self, cid):
        if cid != CID:
            return super().inspect(cid)
        self.clone_reads += 1
        result = {'Id': CID, 'Name': '/nexus-restore-1', 'Image': IMAGE,
            'Config': {'Labels': {'deployops.nexus.restore': 'nexus-restore-1'}},
            'State': {'Running': True, 'StartedAt': 'clone-start'},
            'Mounts': [{'Type': 'volume', 'Name': 'nexus-restore-data-1', 'Destination': '/nexus-data', 'RW': True},
                       {'Type': 'bind', 'Source': str(self.root / 'state/restore-1-work/keys/secrets.json'),
                        'Destination': '/run/nexus-secrets/secrets.json', 'RW': False}],
            'HostConfig': {'NetworkMode': self.net['Name'], 'PortBindings': {
                str(p) + '/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(18003 + i)}]
                for i, p in enumerate([8081, 5000, 5001])}},
            'NetworkSettings': {'Ports': {}, 'Networks': {self.net['Name']: {
                'NetworkID': self.net_id, 'EndpointID': 'e' * 64, 'IPAddress': self.address}}}}
        if self.mutation:
            self.mutation(result, self.net)
        if self.changed_after and self.clone_reads > 1:
            result['NetworkSettings']['Networks'][self.net['Name']]['EndpointID'] = 'f' * 64
        return result

    def call(self, args):
        if args == ['network', 'inspect', 'nexus-restore-net-1']:
            self.calls.append(args)
            return json.dumps([self.net])
        return super().call(args)

    def restore_identity(self, value, args, meta, key):
        return REAL_LIBRARY.restore_identity(value, args, meta, key)


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.owner_mock=patch.object(W.os, "chown", create=True)
        self.owner_mock.start()
        self.host_mock=patch.object(W.os,"uname",return_value=SimpleNamespace(nodename="deploy01"),create=True)
        self.host_mock.start()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.backend = Backend(self.root)
        self.broker = SimpleNamespace(parse_unique=lambda raw: json.loads(raw.decode()),
            read_file=lambda path, *a, **k: path.read_bytes(), positive_id=lambda value: type(value) is int and value > 0,
            SHUTDOWN_REQUESTED=False, shutdown_requested=lambda: False, releases_maintenance=lambda value: True,
            io=__import__('io'))
        self.store = W.RestoreStore(self.broker, self.backend)
        self.job = {'schema': 1, 'action': 'RESTORE', 'requestId': 1,
                    'backupRequestId': 2, 'manifestSha256': SHA}
        self.backend.write(self.store.path('inbox', 1), self.job)

    def failure_check_fixture(self):
        (self.root / 'state' / 'checks').mkdir()
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job)
        for kind in ('network', 'load', 'volume'):
            self.store.complete(1, self.store.begin(1, kind))
        library = Library(self.root)
        baseline = {library.original['Id']: W.projection(library.original)}
        self.backend.write(self.store.path('state', 1, '.baseline'), baseline)
        self.store.publish(W.public_result(self.job, 'RECOVERY_REQUIRED', error='RESULT_UNCERTAIN'))
        check = dict(self.job, action='CHECK_RESTORE_FAILURE', checkId=2)
        self.backend.write(self.store.check_path('inbox', 1, 2), check)
        work = self.root / 'state' / 'restore-1-work'
        (work / 'keys').mkdir(parents=True)
        (work / 'keys' / 'secrets.json').write_text('fixture')
        original_key = self.root / 'original-key.json'
        original_key.write_text('fixture')
        data = self.root / 'fixture-volume'
        data.mkdir()
        network = dict(Name='nexus-restore-net-1', Id='d' * 64, Driver='bridge', Internal=True,
                       Labels={'deployops.nexus.restore': 'nexus-restore-1'}, Containers={})
        volume = dict(Name='nexus-restore-data-1', Driver='local', Options=None,
                      Labels={'deployops.nexus.restore': 'nexus-restore-1'})
        def readonly_call(args):
            library.calls.append(args)
            if args[0] == 'ps':
                return ''
            if args[:2] == ['network', 'inspect']:
                return json.dumps([network])
            if args[:2] == ['volume', 'inspect']:
                return json.dumps([volume])
            raise AssertionError('Unexpected Docker operation')
        library.call = readonly_call
        library.volume_path = lambda name: data
        library.secure_path = lambda path, **kwargs: Path(path)
        library.validate_key = lambda path: None
        library.digest = lambda path: W.hashlib.sha256(path.read_bytes()).hexdigest()
        config = dict(containerName='worksystem-nexus', imageId=IMAGE, volume='nexus-data', keyFile=str(original_key))
        return library, config, work, data, network, volume

    def test_explicit_failure_check_archives_prior_evidence_and_releases_without_replay(self):
        lib, config, work, data, network, volume = self.failure_check_fixture()
        immutable = [self.store.path('state', 1), self.store.path('state', 1, '.baseline'),
                     self.store.path('state', 1, '.mutations')]
        before = [path.read_bytes() for path in immutable]
        previous = self.store.read('results', 1)
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('No replay')):
            self.assertTrue(W.run_queue(self.broker, self.backend, config, {'enabled': False}, lib))
            primary = self.store.read('results', 1)
            self.assertTrue(W.terminal_releases(primary, self.job))
            self.assertEqual(primary['status'], 'FAILED')
            result = json.loads(self.store.check_path('results', 1, 2).read_text())
            self.assertEqual(result['status'], 'VERIFIED_FAILED')
            evidence = json.loads(self.store.check_path('state/checks', 1, 2, '.evidence').read_text())
            self.assertEqual(evidence['previous'], previous)
            self.assertEqual([path.read_bytes() for path in immutable], before)
            result_bytes = self.store.check_path('results', 1, 2).read_bytes()
            self.assertFalse(W.run_queue(self.broker, self.backend, config, {'enabled': False}, lib))
            self.assertEqual(self.store.check_path('results', 1, 2).read_bytes(), result_bytes)
        self.assertTrue(all(REAL_LIBRARY.readonly(args) for args in lib.calls))

    def test_failure_check_drift_between_readonly_observations_keeps_hold(self):
        lib, config, work, data, network, volume = self.failure_check_fixture()
        original = lib.call
        observations = []
        def drift(args):
            if args[:2] == ['network', 'inspect']:
                observations.append(True)
                if len(observations) == 2:
                    network['Id'] = 'e' * 64
            return original(args)
        lib.call = drift
        prior = self.store.path('results', 1).read_bytes()
        self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
        result = json.loads(self.store.check_path('results', 1, 2).read_text())
        self.assertEqual(result['status'], 'UNCONFIRMED')
        self.assertEqual(self.store.path('results', 1).read_bytes(), prior)
        self.assertFalse(self.store.check_path('state/checks', 1, 2, '.evidence').exists())

    def test_failure_check_rejects_inflight_run_stop_foreign_resource_clone_and_nonempty_volume(self):
        for case in ('inflight', 'run', 'stop', 'clone', 'foreign-network', 'foreign-volume', 'not-internal',
                     'attached-network', 'nonempty', 'private-result', 'wrong-original', 'wrong-request', 'missing-baseline'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                saved = self.root, self.backend, self.store
                self.root = Path(temp); self.backend = Backend(self.root)
                self.store = W.RestoreStore(self.broker, self.backend)
                self.backend.write(self.store.path('inbox', 1), self.job)
                lib, config, work, data, network, volume = self.failure_check_fixture()
                if case in ('run', 'stop'):
                    self.store.complete(1, self.store.begin(1, case))
                elif case == 'inflight':
                    value = self.store.ledger(1); value['events'][-1]['state'] = 'inflight'
                    self.backend.write(self.store.path('state', 1, '.mutations'), value)
                elif case == 'clone':
                    original = lib.call
                    lib.call = lambda args: CID if args[0] == 'ps' else original(args)
                elif case == 'foreign-network': network['Labels'] = {}
                elif case == 'foreign-volume': volume['Labels'] = {}
                elif case == 'not-internal': network['Internal'] = False
                elif case == 'attached-network': network['Containers'] = {CID: {}}
                elif case == 'nonempty': (data / 'existing').write_text('retained')
                elif case == 'private-result': (work / 'restore.result.json').write_text('{}')
                elif case == 'wrong-original': lib.original['State']['StartedAt'] = 'changed'
                elif case == 'wrong-request':
                    check = dict(self.job, action='CHECK_RESTORE_FAILURE', checkId=2, backupRequestId=3)
                    self.backend.write(self.store.check_path('inbox', 1, 2), check)
                elif case == 'missing-baseline': self.store.path('state', 1, '.baseline').unlink()
                prior = self.store.path('results', 1).read_bytes()
                with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('No replay')):
                    self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
                result = json.loads(self.store.check_path('results', 1, 2).read_text())
                self.assertEqual(result['status'], 'UNCONFIRMED')
                self.assertEqual(self.store.path('results', 1).read_bytes(), prior)
                self.assertFalse(self.store.check_path('state/checks', 1, 2, '.evidence').exists())
                self.root, self.backend, self.store = saved

    def test_failure_check_changed_or_missing_copied_key_never_promotes(self):
        for case in ('changed', 'missing'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                saved = self.root, self.backend, self.store
                self.root = Path(temp); self.backend = Backend(self.root)
                self.store = W.RestoreStore(self.broker, self.backend)
                self.backend.write(self.store.path('inbox', 1), self.job)
                lib, config, work, *_ = self.failure_check_fixture()
                if case == 'changed':
                    (work / 'keys' / 'secrets.json').write_text('different')
                else:
                    (work / 'keys' / 'secrets.json').unlink()
                before = self.store.path('results', 1).read_bytes()
                self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
                self.assertEqual(self.store.path('results', 1).read_bytes(), before)
                self.assertEqual(json.loads(self.store.check_path('results', 1, 2).read_text())['status'], 'UNCONFIRMED')
                self.root, self.backend, self.store = saved

    def test_failure_check_archive_or_primary_write_failure_never_publishes_verified_result(self):
        for target in ('archive', 'primary'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temp:
                saved = self.root, self.backend, self.store
                self.root = Path(temp); self.backend = Backend(self.root)
                self.store = W.RestoreStore(self.broker, self.backend)
                self.backend.write(self.store.path('inbox', 1), self.job)
                lib, config, *_ = self.failure_check_fixture()
                write = self.backend.write
                def fail(path, value, exclusive=False):
                    if (target == 'archive' and '.evidence.' in path.name or
                            target == 'primary' and path == self.store.path('results', 1)):
                        raise OSError('fixture-write-failure')
                    return write(path, value, exclusive)
                self.backend.write = fail
                prior = self.store.path('results', 1).read_bytes()
                self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
                result = json.loads(self.store.check_path('results', 1, 2).read_text())
                self.assertEqual(result['status'], 'UNCONFIRMED')
                self.assertEqual(self.store.path('results', 1).read_bytes(), prior)
                self.assertFalse(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
                self.root, self.backend, self.store = saved

    def test_failure_check_projection_accepts_only_mount_order_and_rejects_changed_members(self):
        first = dict(Id='a' * 64, Name='/original', Image=IMAGE, running=True, startedAt='same', labels={},
                     Mounts=[dict(Type='volume', Name='data', Destination='/data', RW=True),
                             dict(Type='bind', Source='/key', Destination='/key', RW=False)])
        reversed_value = dict(first, Mounts=list(reversed(first['Mounts'])))
        self.assertEqual(W.canonical_projection(first), W.canonical_projection(reversed_value))
        for field, value in (('RW', True), ('Source', '/other')):
            changed = json.loads(json.dumps(reversed_value)); changed['Mounts'][0][field] = value
            self.assertNotEqual(W.canonical_projection(first), W.canonical_projection(changed))
        with self.assertRaises(ValueError):
            W.canonical_projection(dict(first, Mounts=first['Mounts'] + [first['Mounts'][0]]))

    def test_missing_check_publication_finishes_readonly_without_rewriting_primary_or_archive(self):
        lib, config, *_ = self.failure_check_fixture()
        result_path = self.store.check_path('results', 1, 2)
        write = self.backend.write
        def fail_result(path, value, exclusive=False):
            if path == result_path:
                raise OSError('fixture-publication-failure')
            return write(path, value, exclusive)
        self.backend.write = fail_result
        with self.assertRaises(OSError):
            W.process_failure_check(self.broker, self.store, config, lib, 1, 2)
        archive = self.store.check_path('state/checks', 1, 2, '.evidence')
        retained = [self.store.path('results', 1), archive, self.store.path('state', 1),
                    self.store.path('state', 1, '.mutations'), self.store.path('state', 1, '.baseline')]
        before = [path.read_bytes() for path in retained]
        writes = []
        def record(path, value, exclusive=False):
            writes.append(path)
            return write(path, value, exclusive)
        self.backend.write = record
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('No replay')):
            self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
        self.assertEqual(writes, [result_path])
        self.assertEqual([path.read_bytes() for path in retained], before)
        self.assertEqual(json.loads(result_path.read_text())['status'], 'VERIFIED_FAILED')
        self.assertFalse(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))

    def test_used_check_without_definitive_primary_cannot_repeat_validation_or_promote(self):
        lib, config, *_ = self.failure_check_fixture()
        check = dict(self.job, action='CHECK_RESTORE_FAILURE', checkId=2)
        self.backend.write(self.store.check_path('state/checks', 1, 2), check, exclusive=True)
        prior = self.store.path('results', 1).read_bytes()
        with patch.object(W, 'verify_precontainer_failure', side_effect=AssertionError('No restart')):
            self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
        self.assertEqual(self.store.path('results', 1).read_bytes(), prior)
        self.assertEqual(json.loads(self.store.check_path('results', 1, 2).read_text())['status'], 'UNCONFIRMED')
        self.assertFalse(lib.calls)

    def test_missing_check_publication_cannot_release_after_current_projection_drift(self):
        lib, config, *_ = self.failure_check_fixture()
        result_path = self.store.check_path('results', 1, 2)
        write = self.backend.write
        def fail_result(path, value, exclusive=False):
            if path == result_path:
                raise OSError('fixture-publication-failure')
            return write(path, value, exclusive)
        self.backend.write = fail_result
        with self.assertRaises(OSError):
            W.process_failure_check(self.broker, self.store, config, lib, 1, 2)
        self.backend.write = write
        lib.original['State']['StartedAt'] = 'drift-after-primary'
        prior = self.store.path('results', 1).read_bytes()
        self.assertTrue(W.process_failure_check(self.broker, self.store, config, lib, 1, 2))
        self.assertEqual(self.store.path('results', 1).read_bytes(), prior)
        result = json.loads(result_path.read_text())
        self.assertEqual(result['status'], 'UNCONFIRMED')
        self.assertTrue(result['mutationUncertain'])

    def tearDown(self):
        self.temp.cleanup()
        self.owner_mock.stop()
        self.host_mock.stop()

    def execute(self, timeout=False, changed=False):
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job, exclusive=True)
        library = Library(self.root, timeout, changed)
        executor = W.RestoreExecutor(self.broker, self.store,
            {'containerName': 'worksystem-nexus', 'imageId': IMAGE, 'volume': 'nexus-data',
             'backupRoot': '/fixed/root'}, {'portBase': 18000}, library)
        return executor.run(self.job), library

    def real_library_observer(self):
        self.store.initialize(1, self.job)
        return patch.object(REAL_LIBRARY, 'MUTATION_OBSERVER', SimpleNamespace(
            begin=lambda kind: self.store.begin(1, kind),
            complete=lambda sequence: self.store.complete(1, sequence)))

    def test_exact_docker_root_info_is_readonly_with_real_mutation_store(self):
        ledger = self.store.path('state', 1, '.mutations')
        process = SimpleNamespace(returncode=0, communicate=lambda timeout: (b'/fixture/docker\n', b''))
        with self.real_library_observer(), patch.object(REAL_LIBRARY.subprocess, 'Popen', return_value=process) as launch:
            before = ledger.read_bytes()
            self.assertEqual(REAL_LIBRARY.call(['info', '--format', '{{.DockerRootDir}}']), '/fixture/docker')
            launch.assert_called_once_with(REAL_LIBRARY.DOCKER + ['info', '--format', '{{.DockerRootDir}}'],
                                          stdout=REAL_LIBRARY.subprocess.PIPE, stderr=REAL_LIBRARY.subprocess.PIPE)
            self.assertEqual(ledger.read_bytes(), before)
            self.assertFalse(self.store.uncertain(1))

    def test_other_info_and_unknown_mutation_are_rejected_before_cli_and_ledger(self):
        ledger = self.store.path('state', 1, '.mutations')
        variants = (['info'], ['info', '--format', '{{json .}}'],
                    ['info', '--format', '{{.DockerRootDir}}', '--extra'],
                    ['info', '--format={{.DockerRootDir}}'], ['unknown-mutation'])
        with self.real_library_observer(), patch.object(REAL_LIBRARY.subprocess, 'Popen') as launch:
            before = ledger.read_bytes()
            for args in variants:
                with self.subTest(args=args), self.assertRaises(ValueError):
                    REAL_LIBRARY.command(args)
                self.assertEqual(ledger.read_bytes(), before)
            launch.assert_not_called()

    def test_real_volume_path_info_does_not_create_mutation_event(self):
        ledger = self.store.path('state', 1, '.mutations')
        responses = [json.dumps([dict(Name='nexus-restore-data-1', Driver='local', Options=None,
                     Mountpoint='/fixture/docker/volumes/nexus-restore-data-1/_data')]).encode(), b'/fixture/docker\n']
        def process(args, **kwargs):
            output = responses.pop(0)
            return SimpleNamespace(returncode=0, communicate=lambda timeout: (output, b''))
        with self.real_library_observer(), patch.object(REAL_LIBRARY.subprocess, 'Popen', side_effect=process) as launch, \
                patch.object(REAL_LIBRARY, 'secure_path', side_effect=lambda path, **kwargs: Path(path)):
            before = ledger.read_bytes()
            self.assertEqual(REAL_LIBRARY.volume_path('nexus-restore-data-1'),
                             Path('/fixture/docker/volumes/nexus-restore-data-1/_data'))
            self.assertEqual(launch.call_count, 2)
            self.assertEqual(ledger.read_bytes(), before)
            self.assertFalse(self.store.uncertain(1))

    def test_health_ready_is_not_functional_success_and_private_hash_not_exported(self):
        result, lib = self.execute()
        self.assertEqual(result['status'], 'HEALTH_READY')
        self.assertFalse(result['mutationUncertain'])
        self.assertEqual(result['expectedArtifacts'], [])
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(result))
        self.assertTrue(W.terminal_releases(result, self.job))
        self.assertEqual([c[0] for c in lib.calls], ['ps', 'ps', 'volume', 'network', 'network', 'load', 'volume', 'run'])

    def test_timeout_after_daemon_applied_keeps_durable_hold_and_no_retry(self):
        result, lib = self.execute(timeout=True)
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertTrue(self.store.uncertain(1))
        self.assertEqual(sum(c[0] == 'run' for c in lib.calls), 1)
        self.assertFalse(any(c[0] in ('stop', 'rm') for c in lib.calls))
        self.assertFalse(W.terminal_releases(result, self.job))

    def test_original_projection_change_overrides_health_ready(self):
        result, _ = self.execute(changed=True)
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(result['originalProjectionPreserved'])

    def execute_mount_order_change(self, timeout=False, mutation=None):
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job, exclusive=True)
        lib = Library(self.root, timeout=timeout)
        lib.original['Mounts'] = [dict(Type='volume', Name='nexus-data', Destination='/nexus-data', RW=True),
                                  dict(Type='bind', Source='/original-key', Destination='/key', RW=False)]
        original_inspect = lib.inspect
        def inspect(cid):
            value = original_inspect(cid)
            if cid != CID and lib.applied:
                value['Mounts'].reverse()
                if mutation is not None:
                    value['Mounts'][0][mutation[0]] = mutation[1]
            return value
        lib.inspect = inspect
        executor = W.RestoreExecutor(self.broker, self.store,
            {'containerName': 'worksystem-nexus', 'imageId': IMAGE, 'volume': 'nexus-data',
             'backupRoot': '/fixed/root'}, {'portBase': 18000}, lib)
        return executor.run(self.job), lib

    def test_restore_success_preserves_same_mounts_in_different_inspect_order(self):
        result, lib = self.execute_mount_order_change()
        self.assertEqual(result['status'], 'HEALTH_READY')
        self.assertTrue(result['originalProjectionPreserved'])
        self.assertFalse(result['mutationUncertain'])
        self.assertEqual(sum(c[0] == 'run' for c in lib.calls), 1)
        self.assertTrue(W.terminal_releases(result, self.job))

    def test_restore_exception_preserves_mount_order_change_but_keeps_inflight_hold(self):
        result, lib = self.execute_mount_order_change(timeout=True)
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertTrue(result['originalProjectionPreserved'])
        self.assertTrue(result['mutationUncertain'])
        self.assertFalse(W.terminal_releases(result, self.job))
        self.assertEqual(sum(c[0] == 'run' for c in lib.calls), 1)
        self.assertFalse(any(c[0] in ('stop', 'rm') for c in lib.calls))

    def test_restore_success_mount_source_change_still_holds(self):
        result, _ = self.execute_mount_order_change(mutation=('Source', '/foreign-key'))
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(result['originalProjectionPreserved'])
        self.assertFalse(W.terminal_releases(result, self.job))

    def test_restore_exception_mount_access_change_still_holds(self):
        result, lib = self.execute_mount_order_change(timeout=True, mutation=('RW', True))
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(result['originalProjectionPreserved'])
        self.assertTrue(result['mutationUncertain'])
        self.assertFalse(W.terminal_releases(result, self.job))
        self.assertFalse(any(c[0] in ('stop', 'rm') for c in lib.calls))

    def test_restart_missing_ledger_never_adopts_running_health(self):
        self.backend.write(self.store.path('state', 1), self.job)
        self.store.publish(W.public_result(self.job, 'HEALTH_READY', True, False,
            clone={'containerId': CID, 'imageId': IMAGE, 'volume': 'nexus-restore-data-1'}))
        lib = Library(self.root)
        self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, lib))
        self.assertEqual(self.store.read('results', 1)['status'], 'RECOVERY_REQUIRED')
        self.assertEqual(lib.calls, [])

    def test_empty_preclaim_ledger_can_be_adopted_but_history_cannot(self):
        self.store.initialize(1)
        self.store.initialize(1)
        self.store.begin(1, 'network')
        with self.assertRaises(ValueError):
            self.store.initialize(1)

    def test_inflight_blocks_further_mutations(self):
        self.store.initialize(1)
        self.store.begin(1, 'load')
        with self.assertRaises(ValueError):
            self.store.begin(1, 'volume')
        self.assertEqual(len(self.store.ledger(1)['events']), 1)

    def test_job_rejects_arbitrary_paths_and_boolean_id(self):
        for key, value in [('path', '/tmp/private'), ('requestId', True), ('manifestSha256', 'bad')]:
            with self.assertRaises(ValueError):
                W.validate_job(dict(self.job, **{key: value}), 1)

    def test_external_baseline_receipt_is_exactly_bound_and_never_inferred(self):
        receipt = {'schema': 1, 'backupRequestId': 2, 'manifestSha256': SHA,
                   'sourceImageId': IMAGE, 'artifacts': [{'ref': 'existing-maven', 'sha256': 'd' * 64}]}
        result = W.expectations(receipt, self.job, 'e' * 64)
        self.assertEqual(result[0]['baselineReceiptSha256'], 'e' * 64)
        for broken in [dict(receipt, backupRequestId=3), dict(receipt, artifacts=[{}, {}]),
                       dict(receipt, artifacts=receipt['artifacts'] * 2), dict(receipt, artifacts=None)]:
            with self.assertRaises(ValueError):
                W.expectations(broken, self.job, 'e' * 64)

    def test_terminal_other_request_or_typed_false_cannot_release(self):
        result, _ = self.execute()
        for broken in [dict(result, requestId=2), dict(result, mutationUncertain=0),
                       dict(result, originalProjectionPreserved=1), dict(result, cloneName='original')]:
            with self.assertRaises(ValueError):
                W.terminal_releases(broken, self.job)

    def execute_bridge(self, mutation=None, status=200, http_error=None, changed_after=False, request_error=None):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            backend = Backend(root)
            store = W.RestoreStore(self.broker, backend)
            backend.write(store.path('inbox', 1), self.job)
            store.initialize(1, self.job)
            backend.write(store.path('state', 1), self.job, exclusive=True)
            library = BridgeLibrary(root, mutation, changed_after)
            executor = W.RestoreExecutor(self.broker, store,
                {'containerName': 'worksystem-nexus', 'imageId': IMAGE, 'volume': 'nexus-data', 'backupRoot': '/fixed/root'},
                {'portBase': 18000}, library)
            connection = SimpleNamespace(request=unittest.mock.Mock(side_effect=request_error),
                getresponse=unittest.mock.Mock(return_value=SimpleNamespace(status=status), side_effect=http_error),
                close=unittest.mock.Mock())
            with patch.object(W.http.client, 'HTTPConnection', return_value=connection) as http:
                result = executor.run(self.job)
            return result, library, http, connection

    def test_actual_empty_publication_produces_verified_bridge_access_without_host_port(self):
        result, library, http, connection = self.execute_bridge()
        self.assertEqual(result['status'], 'HEALTH_READY')
        self.assertEqual(result['cloneAccess'], {'schema': 2, 'transport': 'EXECUTION_HOST_BRIDGE_ACCESS',
            'executionHost': 'deploy01', 'cloneContainerId': CID, 'networkId': 'd' * 64,
            'containerIpv4': '172.19.0.2', 'containerPort': 8081, 'requiresVerifiedRelay': True})
        http.assert_called_once_with('172.19.0.2', 8081, timeout=5)
        connection.request.assert_called_once_with('GET', '/', headers={'Connection': 'close'})
        connection.close.assert_called_once_with()
        self.assertTrue(W.terminal_releases(result, self.job))
        self.assertEqual(sum(c[0] == 'run' for c in library.calls), 1)
        self.assertFalse(any(c[0] in ('stop', 'rm') for c in library.calls))

    def test_bridge_access_rejects_wrong_network_label_endpoint_address_and_requested_binding(self):
        changes = [lambda c, n: n.update(Id='f' * 64),
            lambda c, n: n.update(Internal=False), lambda c, n: n.update(ConfigOnly=True),
            lambda c, n: n['Labels'].update({'deployops.nexus.restore': 'foreign'}),
            lambda c, n: n.update(Containers={}),
            lambda c, n: n['Containers'].update({'f' * 64: {'Name': 'foreign'}}),
            lambda c, n: (c['NetworkSettings']['Networks'][n['Name']].update(EndpointID=''),
                          n['Containers'][CID].update(EndpointID='')),
            lambda c, n: c['NetworkSettings']['Networks'].update({'foreign': {}}),
            lambda c, n: c['NetworkSettings']['Networks'][n['Name']].update(IPAddress='127.0.0.1'),
            lambda c, n: c['NetworkSettings']['Networks'][n['Name']].update(IPAddress='8.8.8.8'),
            lambda c, n: c['NetworkSettings']['Networks'][n['Name']].update(IPAddress='172.019.0.2'),
            lambda c, n: c['HostConfig']['PortBindings']['8081/tcp'][0].update(HostIp='0.0.0.0'),
            lambda c, n: c['HostConfig']['PortBindings'].update({'9999/tcp': []}),
            lambda c, n: c['NetworkSettings'].update(Ports={'8081/tcp': None}),
            lambda c, n: c['NetworkSettings'].update(Ports=None),
            lambda c, n: c.update(Id='f' * 64)]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                result, _, http, _ = self.execute_bridge(mutation=change)
                self.assertNotEqual(result['status'], 'HEALTH_READY')
                self.assertIsNone(result['cloneAccess'])
                http.assert_not_called()

    def test_bridge_access_requires_http_200_and_closes_on_failed_probe(self):
        for status, error, request_error in ((302, None, None), (503, None, None), (200, TimeoutError(), None),
                                             (200, None, ConnectionRefusedError())):
            with self.subTest(status=status, timeout=error is not None):
                result, _, http, connection = self.execute_bridge(status=status, http_error=error, request_error=request_error)
                self.assertNotEqual(result['status'], 'HEALTH_READY')
                self.assertIsNone(result['cloneAccess'])
                http.assert_called_once()
                connection.close.assert_called_once_with()

    def test_bridge_access_reinspection_drift_rejects_after_http_without_replay(self):
        result, library, http, connection = self.execute_bridge(changed_after=True)
        self.assertNotEqual(result['status'], 'HEALTH_READY')
        self.assertIsNone(result['cloneAccess'])
        http.assert_called_once()
        connection.close.assert_called_once_with()
        self.assertEqual(sum(c[0] == 'run' for c in library.calls), 1)
        self.assertFalse(any(c[0] in ('stop', 'rm') for c in library.calls))

    def test_schema2_terminal_validator_is_exact_and_bound_to_public_clone(self):
        result, _, _, _ = self.execute_bridge()
        changes = [dict(cloneContainerId='f' * 64), dict(networkId='bad'), dict(containerIpv4='192.0.2.1'),
            dict(containerPort=True), dict(containerPort=5000), dict(requiresVerifiedRelay=False),
            dict(transport='PUBLISHED_LOOPBACK'), dict(hostPort=18003), dict(schema=True)]
        for fields in changes:
            with self.subTest(fields=list(fields)):
                broken = dict(result, cloneAccess=dict(result['cloneAccess'], **fields))
                with self.assertRaises(ValueError):
                    W.terminal_releases(broken, self.job)

    def preflight_failure(self, kind, comparison='unchanged', inflight=False):
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job, exclusive=True)
        library = Library(self.root)
        original_inspect = library.inspect
        original_verify = library.verify_artifact
        reached_preflight = [False]

        def verify(path):
            # The actual production baseline must exist before readonly failure.
            self.assertTrue(self.store.exists('state', 1, '.baseline'))
            reached_preflight[0] = True
            if kind == 'artifact':
                raise ValueError('SAFE_ARTIFACT_INVALID')
            root, meta = original_verify(path)
            if kind == 'port':
                meta['containerPorts'] = [8081, 8081]
            if kind == 'overflow':
                meta['containerPorts'] = [8081, 5000]
            return root, meta

        def inspect(cid):
            if reached_preflight[0] and comparison == 'missing':
                raise ValueError('SAFE_MISSING_READBACK')
            value = original_inspect(cid)
            if reached_preflight[0] and comparison == 'changed':
                value['State']['StartedAt'] = 'external-change'
            return value

        library.verify_artifact, library.inspect = verify, inspect
        if kind == 'collision':
            original_call = library.call
            def call(args):
                value = original_call(args)
                return 'nexus-restore-1' if args == ['ps', '-a', '--format', '{{.Names}}'] else value
            library.call = call
        if inflight:
            self.store.begin(1, 'network')
        executor = W.RestoreExecutor(self.broker, self.store,
            {'containerName': 'worksystem-nexus', 'imageId': IMAGE, 'volume': 'nexus-data',
             'backupRoot': '/fixed/root'}, {'portBase': 65535 if kind == 'overflow' else 18000}, library)
        result = executor.run(self.job)
        self.assertFalse(any(c[0] in ('load', 'run', 'stop', 'rm') or c[:2] in
                             (['network', 'create'], ['volume', 'create']) for c in library.calls))
        return result

    def test_artifact_invalid_before_mutation_releases_with_durable_unchanged_baseline(self):
        result = self.preflight_failure('artifact')
        self.assertEqual(result['status'], 'FAILED')
        self.assertTrue(W.terminal_releases(result, self.job))
        self.assertEqual(self.store.ledger(1)['events'], [])

    def test_invalid_ports_before_mutation_release(self):
        result = self.preflight_failure('port')
        self.assertTrue(W.terminal_releases(result, self.job))

    def test_port_overflow_before_mutation_releases(self):
        result = self.preflight_failure('overflow')
        self.assertTrue(W.terminal_releases(result, self.job))

    def test_name_collision_before_mutation_releases(self):
        result = self.preflight_failure('collision')
        self.assertTrue(W.terminal_releases(result, self.job))

    def test_changed_projection_holds_even_with_empty_ledger(self):
        result = self.preflight_failure('artifact', comparison='changed')
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(W.terminal_releases(result, self.job))

    def test_missing_projection_holds_even_with_empty_ledger(self):
        result = self.preflight_failure('artifact', comparison='missing')
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(W.terminal_releases(result, self.job))

    def test_inflight_ledger_holds_even_with_unchanged_projection(self):
        result = self.preflight_failure('artifact', inflight=True)
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(W.terminal_releases(result, self.job))

    def test_repeated_root_hold_poll_preserves_result_time_and_does_not_execute(self):
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job, exclusive=True)
        self.store.begin(1, 'network')
        library = Library(self.root)
        for _ in range(3):
            self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
            current = self.store.path('results', 1).read_bytes()
            if _ == 0:
                first = current
            else:
                self.assertEqual(current, first)
        self.assertEqual(library.calls, [])
        self.assertTrue(self.store.uncertain(1))

    def test_existing_uncertain_claim_preserves_clone_checks_and_original_observation(self):
        previous, library = self.execute(changed=True)
        previous['expectedArtifacts'] = [{'ref': 'existing-maven', 'sha256': 'd' * 64,
                                         'baselineReceiptSha256': 'e' * 64}]
        self.store.publish(previous)
        original_bytes = self.store.path('results', 1).read_bytes()
        library.calls.clear()
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('MUST_NOT_REPLAY')):
            for _ in range(3):
                self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
                self.assertEqual(self.store.path('results', 1).read_bytes(), original_bytes)
        self.assertEqual(library.calls, [])
        current = self.store.read('results', 1)
        self.assertEqual(current['cloneAccess'], previous['cloneAccess'])
        self.assertEqual(current['expectedArtifacts'], previous['expectedArtifacts'])
        self.assertFalse(current['originalProjectionPreserved'])
        self.assertTrue(current['mutationUncertain'])

    def test_missing_ledger_promotes_hold_but_retains_valid_health_ready_evidence(self):
        previous, library = self.execute()
        self.store.publish(previous)
        self.store.path('state', 1, '.mutations').unlink()
        library.calls.clear()
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('MUST_NOT_REPLAY')):
            self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
        current = self.store.read('results', 1)
        self.assertEqual(current['status'], 'RECOVERY_REQUIRED')
        self.assertEqual(current['errorCode'], 'RESULT_UNCERTAIN')
        self.assertTrue(current['mutationUncertain'])
        self.assertTrue(current['originalProjectionPreserved'])
        for field in ('cloneContainerId', 'cloneImageId', 'cloneName', 'cloneVolume',
                      'cloneNetwork', 'cloneAccess', 'expectedArtifacts', 'completedAt'):
            self.assertEqual(current[field], previous[field])
        self.assertFalse(W.terminal_releases(current, self.job))
        self.assertEqual(library.calls, [])
        held_bytes = self.store.path('results', 1).read_bytes()
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('MUST_NOT_REPLAY')):
            for _ in range(2):
                self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
                self.assertEqual(self.store.path('results', 1).read_bytes(), held_bytes)
        self.assertEqual(library.calls, [])

    def test_existing_claim_invalid_prior_identity_never_retains_arbitrary_information(self):
        previous, library = self.execute(changed=True)
        for broken in (dict(previous, requestId=2), dict(previous, cloneName='original'),
                       dict(previous, cloneVolume=None),
                       dict(previous, cloneAccess={'private': 'SENTINEL'}),
                       dict(previous, expectedArtifacts=[{'private': 'SENTINEL'}])):
            self.backend.write(self.store.path('results', 1), broken)
            library.calls.clear()
            with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('MUST_NOT_REPLAY')):
                self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
            current = self.store.read('results', 1)
            self.assertEqual(current['status'], 'RECOVERY_REQUIRED')
            self.assertTrue(current['mutationUncertain'])
            self.assertIsNone(current['cloneContainerId'])
            self.assertEqual(current['expectedArtifacts'], [])
            self.assertNotIn('SENTINEL', json.dumps(current))
            self.assertEqual(library.calls, [])

    def test_existing_valid_terminal_completed_ledger_is_unchanged_and_not_replayed(self):
        previous, library = self.execute()
        self.store.publish(previous)
        before = self.store.path('results', 1).read_bytes()
        library.calls.clear()
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('MUST_NOT_REPLAY')):
            self.assertFalse(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
        self.assertEqual(self.store.path('results', 1).read_bytes(), before)
        self.assertEqual(library.calls, [])

    def test_no_snapshot_evidence_holds_without_any_mutation(self):
        self.store.initialize(1, self.job)
        library = Library(self.root)
        library.inspect = lambda cid: (_ for _ in ()).throw(ValueError('SAFE_READ_UNAVAILABLE'))
        executor = W.RestoreExecutor(self.broker, self.store,
            {'containerName': 'worksystem-nexus', 'imageId': IMAGE, 'volume': 'nexus-data',
             'backupRoot': '/fixed/root'}, {'portBase': 18000}, library)
        result = executor.run(self.job)
        self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(self.store.exists('state', 1, '.baseline'))
        self.assertEqual(library.calls, [])

    def backup_read_failure(self, broken=None, read_error=False):
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job, exclusive=True)
        normal = self.backend.get_result(2)
        def get_result(request_id):
            self.assertTrue(self.store.exists('state', 1, '.baseline'))
            if read_error:
                raise FileNotFoundError('SAFE_MISSING_BACKUP_RESULT')
            return broken if broken is not None else normal
        self.backend.get_result = get_result
        library = Library(self.root)
        executor = W.RestoreExecutor(self.broker, self.store,
            {'containerName': 'worksystem-nexus', 'imageId': IMAGE, 'volume': 'nexus-data',
             'backupRoot': '/fixed/root'}, {'portBase': 18000}, library)
        result = executor.run(self.job)
        self.assertEqual(result['status'], 'FAILED')
        self.assertTrue(W.terminal_releases(result, self.job))
        self.assertEqual(self.store.ledger(1)['events'], [])
        self.assertEqual(library.calls, [['ps', '-aq', '--no-trunc', '--filter', 'label=deployops.nexus.restore']])

    def test_missing_backup_result_releases_with_preexisting_durable_baseline(self):
        self.backup_read_failure(read_error=True)

    def test_malformed_backup_result_releases_without_any_mutation(self):
        self.backup_read_failure(broken={})

    def test_wrong_backup_state_releases_without_any_mutation(self):
        self.backup_read_failure(broken=dict(self.backend.get_result(2), status='FAILED'))

    def test_wrong_backup_manifest_releases_without_any_mutation(self):
        self.backup_read_failure(broken=dict(self.backend.get_result(2), manifestSha256='d' * 64))

    def test_wrong_backup_artifact_releases_without_any_mutation(self):
        self.backup_read_failure(broken=dict(self.backend.get_result(2), artifactId='nexus-backup-3'))

    def test_shutdown_before_claim_leaves_empty_ledger_and_sends_no_docker(self):
        calls = iter([False, True])
        self.broker.shutdown_requested = lambda: next(calls)
        library = Library(self.root)
        self.assertTrue(W.run_queue(self.broker, self.backend, {}, {'enabled': True}, library))
        self.assertFalse(self.store.exists('state', 1))
        self.assertEqual(self.store.ledger(1)['events'], [])
        self.assertEqual(library.calls, [])


class ExistingReadinessTest(unittest.TestCase):
    """Actual producer/publication functions; CLI, HTTP and fsync are modeled."""
    def setUp(self):
        WorkerTest.setUp(self)
        (self.root / 'state/checks').mkdir()
        self.store.initialize(1, self.job)
        self.backend.write(self.store.path('state', 1), self.job)
        for kind in ('network', 'load', 'volume', 'run'):
            self.store.complete(1, self.store.begin(1, kind))
        self.lib = BridgeLibrary(self.root)
        self.config = {'containerName': 'worksystem-nexus', 'imageId': IMAGE,
                       'volume': 'nexus-data', 'backupRoot': '/fixed/root'}
        self.restore_config = {'enabled': True, 'portBase': 18000}
        self.backend.write(self.store.path('state', 1, '.baseline'),
                           {'c' * 64: W.projection(self.lib.original)})
        self.work = self.root / 'state/restore-1-work'
        self.work.mkdir()
        self.private = dict(status='LOCAL_NEXUS_RESTORE_HEALTH_READY_FUNCTIONAL_VALIDATION_PENDING',
            containerId=CID, imageId=IMAGE, volume='nexus-restore-data-1',
            ports=[[8081, 18003], [5000, 18004], [5001, 18005]], keySha256='f' * 64,
            artifactManifestSha256=SHA, originalVolumeTouched=False,
            authenticationArtifactsRegistryVerified=False)
        self.backend.write(self.work / 'restore.result.json', self.private)
        self.previous = W.public_result(self.job, 'RECOVERY_REQUIRED', False, True, 'RESULT_UNCERTAIN')
        self.backend.write(self.store.path('results', 1), self.previous)
        self.previous_raw = self.store.path('results', 1).read_bytes()
        self.check = dict(self.job, action='CHECK_EXISTING_CLONE_READINESS', checkId=2)
        self.backend.write(self.store.check_path('inbox', 1, 2), self.check)
        def bounded_read(path, *args, **kwargs):
            raw = path.read_bytes()
            W.require(len(raw) <= kwargs.get('limit', 16384))
            return raw
        self.broker.read_file = bounded_read
        call = self.lib.call
        self.volume = dict(Name='nexus-restore-data-1', Driver='local', Options=None,
                           Labels={'deployops.nexus.restore': 'nexus-restore-1'})
        def readonly(args):
            if args[0] == 'exec':
                self.lib.calls.append(args)
                self.assertEqual(args, ['exec', CID, 'curl', '--noproxy', '*', '-sS', '-o', '/dev/null',
                    '--max-time', '3', '-w', '%{http_code}', 'http://127.0.0.1:8081/service/rest/v1/status'])
                return '200'
            if args[:2] == ['volume', 'inspect']:
                self.lib.calls.append(args)
                self.assertEqual(args, ['volume', 'inspect', 'nexus-restore-data-1'])
                return json.dumps([self.volume])
            self.assertEqual(args[:2], ['network', 'inspect'])
            return call(args)
        self.lib.call = readonly
        self.connection = SimpleNamespace(request=unittest.mock.Mock(),
            getresponse=unittest.mock.Mock(return_value=SimpleNamespace(status=200)), close=unittest.mock.Mock())
        self.http = patch.object(W.http.client, 'HTTPConnection', return_value=self.connection)
        self.http.start()

    def tearDown(self):
        self.http.stop()
        WorkerTest.tearDown(self)

    def process(self):
        with patch.object(W.RestoreExecutor, 'run', side_effect=AssertionError('RESTORE_REPLAY_FORBIDDEN')):
            return W.process_readiness_check(self.broker, self.store, self.config,
                self.restore_config, self.lib, 1, 2)

    def primary(self):
        return self.store.read('results', 1)

    def result(self):
        return json.loads(self.store.check_path('results', 1, 2).read_text())

    def test_existing_clone_success_archives_exact_old_bytes_then_primary_then_check(self):
        paths = []
        write = self.backend.write
        def ordered(path, value, exclusive=False):
            paths.append(path.name)
            return write(path, value, exclusive)
        self.backend.write = ordered
        self.assertTrue(self.process())
        self.assertEqual(paths, ['restore-1-2.check.json', 'restore-1-2.readiness-evidence.check.json',
                                 'restore-1.json', 'restore-1-2.check.json'])
        evidence_path = self.store.check_path('state/checks', 1, 2, '.readiness-evidence')
        evidence_raw = evidence_path.read_bytes()
        evidence = json.loads(evidence_raw)
        self.assertEqual(W.base64.b64decode(evidence['oldPrimaryBase64']), self.previous_raw)
        self.assertEqual(evidence['oldPrimarySha256'], W.hashlib.sha256(self.previous_raw).hexdigest())
        result = self.result()
        self.assertEqual(set(result), {'schema', 'requestId', 'checkId', 'backupRequestId', 'manifestSha256',
            'status', 'reasonCode', 'originalProjectionPreserved', 'mutationUncertain', 'completedAt',
            'evidenceSha256', 'primarySha256', 'primaryResult'})
        self.assertEqual((result['schema'], result['status'], result['reasonCode']),
                         (2, 'VERIFIED_HEALTH_READY', 'EXISTING_CLONE_ACCESS_CONFIRMED'))
        self.assertEqual(result['primaryResult'], self.primary())
        self.assertEqual(result['primarySha256'], W.hashlib.sha256(self.store.path('results', 1).read_bytes()).hexdigest())
        self.assertEqual(result['evidenceSha256'], W.hashlib.sha256(evidence_raw).hexdigest())
        self.assertTrue(W.terminal_releases(self.primary(), self.job))
        self.assertEqual(self.primary()['cloneAccess']['schema'], 2)
        self.assertFalse(self.store.uncertain(1))
        self.assertFalse(self.process())
        self.assertEqual(evidence_raw, evidence_path.read_bytes())
        self.assertTrue(all(call[0] in ('exec', 'network', 'volume') for call in self.lib.calls))

    def test_readiness_missing_baseline_private_inflight_or_wrong_identity_keeps_old_bytes(self):
        cases = ['baseline', 'private', 'inflight', 'stop', 'wrong-cid', 'wrong-image', 'wrong-volume',
                 'private-ports', 'manifest', 'private-status', 'original-touched', 'artifact-verified',
                 'network-label', 'foreign-member', 'http-status', 'original-changed', 'duplicate-check']
        for case in cases:
            with self.subTest(case=case):
                # Fresh real producer fixture for every independent failure.
                fixture = ExistingReadinessTest('test_existing_clone_success_archives_exact_old_bytes_then_primary_then_check')
                fixture.setUp()
                try:
                    if case == 'baseline': fixture.store.path('state', 1, '.baseline').unlink()
                    elif case == 'private': (fixture.work / 'restore.result.json').unlink()
                    elif case in ('inflight', 'stop'):
                        ledger = fixture.store.ledger(1)
                        if case == 'inflight': ledger['events'][-1]['state'] = 'inflight'
                        else: ledger['events'][-1]['kind'] = 'stop'
                        fixture.backend.write(fixture.store.path('state', 1, '.mutations'), ledger)
                    elif case == 'network-label': fixture.lib.net['Labels'] = {}
                    elif case == 'foreign-member': fixture.lib.net['Containers']['a' * 64] = {}
                    elif case == 'http-status': fixture.connection.getresponse.return_value.status = 503
                    elif case == 'original-changed': fixture.lib.original['State']['StartedAt'] = 'changed'
                    elif case == 'duplicate-check':
                        fixture.backend.write(fixture.store.check_path('inbox', 1, 3), dict(fixture.check, checkId=3))
                    else:
                        key, value = {'wrong-cid': ('containerId', 'a' * 64), 'wrong-image': ('imageId', 'sha256:' + 'a' * 64),
                            'wrong-volume': ('volume', 'other'), 'private-ports': ('ports', []),
                            'manifest': ('artifactManifestSha256', 'f' * 64), 'private-status': ('status', 'FAILED'),
                            'original-touched': ('originalVolumeTouched', True),
                            'artifact-verified': ('authenticationArtifactsRegistryVerified', True)}[case]
                        fixture.backend.write(fixture.work / 'restore.result.json', dict(fixture.private, **{key: value}))
                    self.assertTrue(fixture.process())
                    self.assertEqual(fixture.store.path('results', 1).read_bytes(), fixture.previous_raw)
                    self.assertEqual(fixture.result()['status'], 'UNCONFIRMED')
                    self.assertIsNone(fixture.result()['primaryResult'])
                finally:
                    fixture.tearDown()

    def test_crashes_after_each_publication_resume_same_check_without_restore_or_overwrite(self):
        class Crash(BaseException): pass
        for boundary in ('evidence', 'primary', 'check'):
            with self.subTest(boundary=boundary):
                fixture = ExistingReadinessTest('test_existing_clone_success_archives_exact_old_bytes_then_primary_then_check')
                fixture.setUp()
                try:
                    write = fixture.backend.write
                    def crash(path, value, exclusive=False):
                        write(path, value, exclusive)
                        if ((boundary == 'evidence' and 'readiness-evidence' in path.name)
                            or (boundary == 'primary' and path == fixture.store.path('results', 1))
                            or (boundary == 'check' and path == fixture.store.check_path('results', 1, 2))):
                            raise Crash()
                    fixture.backend.write = crash
                    with self.assertRaises(Crash): fixture.process()
                    fixture.backend.write = write
                    evidence = fixture.store.check_path('state/checks', 1, 2, '.readiness-evidence').read_bytes()
                    primary = fixture.store.path('results', 1).read_bytes()
                    if boundary == 'evidence': self.assertEqual(primary, fixture.previous_raw)
                    elif boundary == 'primary': self.assertEqual(fixture.primary()['status'], 'HEALTH_READY')
                    self.assertEqual(fixture.process(), boundary != 'check')
                    self.assertEqual(evidence, fixture.store.check_path('state/checks', 1, 2, '.readiness-evidence').read_bytes())
                    self.assertEqual(fixture.result()['status'], 'VERIFIED_HEALTH_READY')
                    if boundary == 'primary': self.assertEqual(primary, fixture.store.path('results', 1).read_bytes())
                finally:
                    fixture.tearDown()

    def test_postpromotion_transient_failure_does_not_downgrade_or_publish_unconfirmed(self):
        write = self.backend.write
        def failed_check(path, value, exclusive=False):
            if path == self.store.check_path('results', 1, 2): raise OSError('MODELED_PUBLICATION_FAILURE')
            return write(path, value, exclusive)
        self.backend.write = failed_check
        self.assertTrue(self.process())
        raw = self.store.path('results', 1).read_bytes()
        self.assertEqual(self.primary()['status'], 'HEALTH_READY')
        self.backend.write = write
        self.connection.getresponse.return_value.status = 503
        self.assertTrue(self.process())
        self.assertEqual(raw, self.store.path('results', 1).read_bytes())
        self.assertFalse(self.store.check_path('results', 1, 2).exists())
        self.connection.getresponse.return_value.status = 200
        self.assertTrue(self.process())
        self.assertEqual(self.result()['status'], 'VERIFIED_HEALTH_READY')

    def test_stale_token_or_missing_tampered_evidence_never_invents_history(self):
        for case in ('missing', 'malformed', 'token'):
            with self.subTest(case=case):
                fixture = ExistingReadinessTest('test_existing_clone_success_archives_exact_old_bytes_then_primary_then_check')
                fixture.setUp()
                try:
                    token = fixture.store.check_path('state/checks', 1, 2)
                    fixture.backend.write(token, dict(fixture.check, backupRequestId=3) if case == 'token' else fixture.check)
                    if case == 'malformed':
                        fixture.backend.write(fixture.store.check_path('state/checks', 1, 2, '.readiness-evidence'), {})
                    self.assertTrue(fixture.process())
                    self.assertEqual(fixture.store.path('results', 1).read_bytes(), fixture.previous_raw)
                    self.assertEqual(fixture.result()['status'], 'UNCONFIRMED')
                finally:
                    fixture.tearDown()

    def test_bounded_large_evidence_succeeds_but_oversize_stops_before_primary(self):
        self.lib.original['Config']['Labels']['public-label'] = 'x' * 15000
        self.backend.write(self.store.path('state', 1, '.baseline'),
                           {'c' * 64: W.projection(self.lib.original)})
        self.assertTrue(self.process())
        evidence = self.store.check_path('state/checks', 1, 2, '.readiness-evidence').read_bytes()
        self.assertGreater(len(evidence), 16384)
        self.assertLessEqual(len(evidence), 65536)
        self.assertLessEqual(len(self.store.check_path('results', 1, 2).read_bytes()), 8192)
        self.assertEqual(self.result()['status'], 'VERIFIED_HEALTH_READY')
        fixture = ExistingReadinessTest('test_existing_clone_success_archives_exact_old_bytes_then_primary_then_check')
        fixture.setUp()
        try:
            fixture.lib.verify_artifact = lambda path: (fixture.root,
                {'containerPorts': [8081, 5000, 5001], 'imageId': IMAGE, 'bounded-public-padding': 'x' * 65536})
            self.assertTrue(fixture.process())
            self.assertEqual(fixture.store.path('results', 1).read_bytes(), fixture.previous_raw)
            self.assertFalse(fixture.store.check_path('state/checks', 1, 2, '.readiness-evidence').exists())
            self.assertEqual(fixture.result()['status'], 'UNCONFIRMED')
        finally:
            fixture.tearDown()

    def test_publication_errors_preserve_old_or_terminal_authority_and_never_replay(self):
        for boundary in ('evidence', 'primary'):
            with self.subTest(boundary=boundary):
                fixture = ExistingReadinessTest('test_existing_clone_success_archives_exact_old_bytes_then_primary_then_check')
                fixture.setUp()
                try:
                    write = fixture.backend.write
                    def failure(path, value, exclusive=False):
                        if ((boundary == 'evidence' and 'readiness-evidence' in path.name)
                            or (boundary == 'primary' and path == fixture.store.path('results', 1))):
                            raise OSError('MODELED_DURABILITY_FAILURE')
                        return write(path, value, exclusive)
                    fixture.backend.write = failure
                    self.assertTrue(fixture.process())
                    self.assertEqual(fixture.store.path('results', 1).read_bytes(), fixture.previous_raw)
                    if boundary == 'evidence': self.assertEqual(fixture.result()['status'], 'UNCONFIRMED')
                    else: self.assertFalse(fixture.store.check_path('results', 1, 2).exists())
                finally:
                    fixture.tearDown()

    def test_changed_old_primary_between_evidence_and_publication_stays_held(self):
        write = self.backend.write
        def change_primary(path, value, exclusive=False):
            write(path, value, exclusive)
            if 'readiness-evidence' in path.name:
                write(self.store.path('results', 1), dict(self.previous, completedAt='2026-10-12T00:00:00+00:00'))
        self.backend.write = change_primary
        self.assertTrue(self.process())
        self.assertEqual(self.primary()['status'], 'RECOVERY_REQUIRED')
        self.assertEqual(self.result()['status'], 'UNCONFIRMED')

    def test_dispatch_distinguishes_actions_before_loading_and_ready_primary_does_not_rehold(self):
        with patch.object(W, 'process_failure_check', side_effect=AssertionError('WRONG_ACTION')):
            self.assertTrue(W.run_queue(self.broker, self.backend, self.config, self.restore_config, self.lib))
        raw = self.store.path('results', 1).read_bytes()
        self.assertFalse(W.run_queue(self.broker, self.backend, self.config, self.restore_config, self.lib))
        self.assertEqual(raw, self.store.path('results', 1).read_bytes())
        self.store.check_path('inbox', 1, 2).unlink()
        self.backend.write(self.store.check_path('inbox', 1, 3), dict(self.check, checkId=3, action='RESTORE'))
        with patch.object(W, 'process_failure_check') as failure, patch.object(W, 'process_readiness_check') as ready:
            self.assertTrue(W.run_queue(self.broker, self.backend, self.config, self.restore_config, self.lib))
            failure.assert_not_called()
            ready.assert_not_called()


if __name__ == '__main__':
    unittest.main()
