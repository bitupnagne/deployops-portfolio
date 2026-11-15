"""Pure/mocked boundary tests; no Docker, root permissions, or backup execution."""
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock

SOURCE = Path(__file__).resolve().parents[1] / 'nexus-backup' / 'nexus-backup-broker.py'
spec = importlib.util.spec_from_file_location('nexus_backup_broker', SOURCE)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
helper_spec = importlib.util.spec_from_file_location('nexus_backup_helper', SOURCE.with_name('nexus-local-backup-restore.py'))
h = importlib.util.module_from_spec(helper_spec)
helper_spec.loader.exec_module(h)


class MemoryStore:
    def __init__(self, job=None):
        self.jobs = {1: job or {'schema': 1, 'requestId': 1, 'action': 'BACKUP'}}
        self.claims, self.results, self.history = set(), {}, []
        self.base = Path('/mock')
        self.baselines, self.check_jobs, self.check_claims, self.check_results = {}, {}, set(), {}
        self.mutations = {}

    def initialize_mutation_ledger(self, request_id):
        b.require(not self.claim_exists(request_id))
        b.validate_request(self.request(request_id), request_id)
        b.require(request_id not in self.baselines and request_id not in self.results)
        b.require(not self.mutations.get(request_id))
        self.mutations.setdefault(request_id, [])

    def mutation_uncertain(self, request_id):
        return request_id not in self.mutations or any(x['state'] != 'completed' for x in self.mutations[request_id])

    def mutation_observer(self, request_id):
        def begin(kind):
            events = self.mutations[request_id]
            events.append({'kind': kind, 'state': 'inflight'})
            return len(events)
        def complete(token):
            self.mutations[request_id][token - 1]['state'] = 'completed'
        return SimpleNamespace(begin=begin, complete=complete)

    def claim_exists(self, request_id):
        return request_id in self.claims

    def claim(self, request_id):
        if request_id in self.claims:
            raise FileExistsError()
        self.claims.add(request_id)

    def request(self, request_id):
        return self.jobs[request_id]

    def get_result(self, request_id):
        return b.validate_result(self.results[request_id], request_id)

    def publish(self, value):
        self.results[value['requestId']] = value
        self.history.append(value.copy())

    def ids(self, directory):
        return sorted(self.claims if directory == 'state' else self.jobs)

    def save_baseline(self, request_id, value):
        self.baselines[request_id] = value
        self.history.append({'baselineFsynced': request_id})

    def baseline(self, request_id):
        return self.baselines[request_id]

    def checks(self):
        return sorted(self.check_jobs)

    def read_inbox(self, path):
        a, c = path.name.split('.')[0].split('-')
        return self.check_jobs[(int(a), int(c))]

    def claim_check(self, request_id, check_id):
        key = (request_id, check_id)
        if key in self.check_claims:
            return False
        self.check_claims.add(key)
        return True

    def publish_check(self, value, check_id):
        self.check_results[(value['requestId'], check_id)] = dict(value, checkId=check_id)


class MockExecutor:
    def __init__(self, failure=None, recovered=True):
        self.failure, self.restored, self.calls = failure, recovered, []
        self.args = SimpleNamespace()
        self.lib = SimpleNamespace(MutationUncertain=h.MutationUncertain, RecoveryRequired=h.RecoveryRequired)

    def prepare(self, request_id):
        self.calls.append('prepare')
        if self.failure == 'prepare':
            raise ValueError('sensitive detail must not be emitted')

    def backup(self):
        self.calls.append('backup')
        if self.failure == 'backup':
            raise ValueError('secret cli stderr')

    def verify(self):
        self.calls.append('verify')
        if self.failure == 'verify':
            raise ValueError('bad artifact')
        return 'a' * 64

    def recovered(self):
        self.calls.append('recovery-check')
        return self.restored

    def baseline(self, request_id):
        return {'schema': 1, 'requestId': request_id}

    def check_recovery(self, baseline, request_id):
        self.calls.append('read-only-check')
        if self.failure:
            raise ValueError('Mismatch or health timeout')
        return True


class BrokerTest(unittest.TestCase):
    def setUp(self):
        b.SHUTDOWN_REQUESTED = False

    def test_real_local_signal_defers_through_recovery_and_durable_result_without_replay(self):
        # Actual OS/C-runtime signal -> installed Python handler in a separate process.
        # Temporary files model claims/results; Nexus/helper work and POSIX root ownership
        # are mocked. This is not an actual systemd/Docker stop or host-shutdown test.
        fixture = r'''
import importlib.util,json,os,pathlib,signal,sys
from types import SimpleNamespace
source,folder,signal_name,fail=sys.argv[1:]
spec=importlib.util.spec_from_file_location('broker_signal',source)
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
root=pathlib.Path(folder)
def durable(name,value):
    with open(str(root/name),'w') as f:
        json.dump(value,f);f.flush();os.fsync(f.fileno())
class Store:
    def initialize_mutation_ledger(self,i):durable('ledger'+str(i),{'events':[]})
    def mutation_uncertain(self,i):return not (root/('ledger'+str(i))).exists()
    def mutation_observer(self,i):return SimpleNamespace(begin=lambda kind:None,complete=lambda token:None)
    def claim_exists(self,i):return (root/('claim'+str(i))).exists()
    def claim(self,i):
        with open(str(root/('claim'+str(i))),'x') as f:
            f.write('claimed');f.flush();os.fsync(f.fileno())
    def request(self,i):return {'schema':1,'requestId':i,'action':'BACKUP'}
    def get_result(self,i):return b.validate_result(json.loads((root/('result'+str(i))).read_text()),i)
    def publish(self,value):durable('result'+str(value['requestId']),value)
    def ids(self,directory):return ([1] if self.claim_exists(1) else []) if directory=='state' else [1,2]
    def checks(self):return []
    def save_baseline(self,i,value):durable('baseline'+str(i),value)
def deliver():
    sig=getattr(signal,signal_name)
    if os.name=='posix':os.kill(os.getpid(),sig)
    else:signal.raise_signal(sig)
class Executor:
    def __init__(self):
        self.args=SimpleNamespace()
        self.lib=SimpleNamespace(MutationUncertain=type('MutationUncertain',(ValueError,),{}),RecoveryRequired=type('RecoveryRequired',(ValueError,),{}))
    def prepare(self,i):pass
    def baseline(self,i):return {'schema':1,'requestId':i}
    def backup(self):
        durable('stopped',True)
        try:
            deliver()
            if not b.SHUTDOWN_REQUESTED:raise RuntimeError('Signal handler not reached')
            durable('handlerReached',True)
            if fail=='yes':raise ValueError('Mock backup failure')
        finally:
            # A second signal during recovery must also be deferred, not interrupt finally.
            deliver()
            durable('restored',True)
    def verify(self):return 'a'*64
    def recovered(self):return (root/'restored').exists()
b.install_signal_handlers()
store=Store()
if fail=='before':
    def factory():
        deliver()
        return Executor()
    b.run_queue(store,factory)
    assert b.SHUTDOWN_REQUESTED and not (root/'claim1').exists() and not (root/'result1').exists()
    print(json.dumps({'signal':signal_name,'actualHandlerReached':True,'claimCreated':False,
      'backupStarted':False,'scenario':'shutdown-after-queue-check-before-process-job'}))
    sys.exit(0)
final=b.process_job(store,1,Executor())
class NoReplay:
    def prepare(self,i):raise RuntimeError('Unwanted replay')
    def backup(self):raise RuntimeError('Unwanted replay')
    def verify(self):raise RuntimeError('Unwanted replay')
b.SHUTDOWN_REQUESTED=False # A fresh later broker process has no pending termination.
again=b.process_job(store,1,NoReplay())
b.SHUTDOWN_REQUESTED=True
b.run_queue(store,lambda:NoReplay())
assert final==again and not (root/'claim2').exists()
assert final['originalStateRestored'] is True and b.releases_maintenance(final)
assert final['status']==('FAILED' if fail=='yes' else 'COMPLETED')
print(json.dumps({'signal':signal_name,'actualHandlerReached':(root/'handlerReached').exists(),
 'recoveryFinallyReached':(root/'restored').exists(),'durableClaim':(root/'claim1').exists(),
 'status':final['status'],'duplicateExecutions':0,'nextJobStarted':False,
 'platformSignalDelivery':'os.kill-self' if os.name=='posix' else 'C-runtime-raise_signal'}))
'''
        for name, fail in [('SIGTERM', 'no'), ('SIGINT', 'yes'), ('SIGTERM', 'before')]:
            with self.subTest(signal=name), tempfile.TemporaryDirectory() as folder:
                proc = subprocess.run([sys.executable, '-c', fixture, str(SOURCE), folder, name, fail],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
                self.assertEqual(0, proc.returncode, proc.stderr.decode())
                observed = json.loads(proc.stdout.decode())
                self.assertTrue(observed['actualHandlerReached'])
                if fail == 'before':
                    self.assertFalse(observed['claimCreated'])
                    self.assertFalse(observed['backupStarted'])
                    print('ACTUAL_LOCAL_SIGNAL_PROOF ' + json.dumps(observed, sort_keys=True))
                    continue
                self.assertTrue(observed['recoveryFinallyReached'])
                self.assertTrue(observed['durableClaim'])
                self.assertEqual(0, observed['duplicateExecutions'])
                self.assertFalse(observed['nextJobStarted'])
                print('ACTUAL_LOCAL_SIGNAL_PROOF ' + json.dumps(observed, sort_keys=True))

    def test_shutdown_after_durable_claim_reconciles_without_settings_error_or_replay(self):
        class ShutdownStore(MemoryStore):
            def claim(self, request_id):
                super().claim(request_id)
                b.request_shutdown(None, None)
        store, executor = ShutdownStore(), MockExecutor()
        final = b.process_job(store, 1, executor)
        self.assertEqual([], executor.calls)
        self.assertEqual({1}, store.claims)
        self.assertEqual('RECONCILING', final['status'])
        self.assertEqual('RESULT_UNCERTAIN', final['errorCode'])
        self.assertTrue(final['originalStateRestored'])
        self.assertFalse(b.releases_maintenance(final))
        b.SHUTDOWN_REQUESTED = False
        b.process_job(store, 1, executor)
        self.assertEqual([], executor.calls)

    def test_success_claim_precedes_stop_and_handoff_only_after_verified_recovery(self):
        store, executor = MemoryStore(), MockExecutor()
        final = b.process_job(store, 1, executor)
        self.assertEqual(['prepare', 'backup', 'verify'], executor.calls)
        self.assertEqual(['PREFLIGHT', 'STOPPING', 'VERIFYING', 'DONE'], [x['stage'] for x in store.history if 'stage' in x])
        self.assertEqual({'baselineFsynced': 1}, store.history[1])
        self.assertEqual({1}, store.claims)
        self.assertTrue(b.releases_maintenance(final))
        self.assertEqual('nexus-backup-1', final['artifactId'])
        self.assertEqual(b.FIELDS, set(final))

    def test_duplicate_completed_request_never_runs_executor_again(self):
        store = MemoryStore()
        first = b.process_job(store, 1, MockExecutor())
        executor = MockExecutor()
        self.assertEqual(first, b.process_job(store, 1, executor))
        self.assertEqual([], executor.calls)

    def test_malformed_extra_field_or_wrong_id_refused_before_docker(self):
        for job in [{'schema': 1, 'requestId': 1, 'action': 'RESTORE'},
                    {'schema': 1, 'requestId': 2, 'action': 'BACKUP'},
                    {'schema': True, 'requestId': 1, 'action': 'BACKUP'},
                    {'schema': 1, 'requestId': 1, 'action': 'BACKUP', 'container': 'other'}]:
            store, executor = MemoryStore(job), MockExecutor()
            with self.assertRaises(ValueError): b.process_job(store, 1, executor)
            self.assertEqual([], executor.calls)
            self.assertEqual(set(), store.claims)
            self.assertEqual({}, store.results)
            self.assertEqual({}, store.mutations)

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(ValueError):
            b.parse_unique(b'{"action":"BACKUP","action":"RESTORE"}')

    def test_missing_unknown_or_running_result_keeps_maintenance_no_replay(self):
        for old in [None, {'status': 'invented'}, b.result(1, 'RUNNING', 'COPYING')]:
            store, executor = MemoryStore(), MockExecutor()
            store.claims.add(1)
            if old is not None:
                store.results[1] = old
            final = b.process_job(store, 1, executor)
            self.assertEqual([], executor.calls)
            self.assertEqual('RECOVERY_REQUIRED', final['status'])
            self.assertFalse(b.releases_maintenance(final))

    def test_uncertain_claim_blocks_another_job(self):
        store, executor = MemoryStore(), MockExecutor()
        store.claims.add(1)
        store.jobs[2] = {'schema': 1, 'requestId': 2, 'action': 'BACKUP'}
        b.run_queue(store, lambda: executor)
        self.assertEqual({1}, store.claims)
        self.assertEqual([], executor.calls)
        self.assertEqual('RECOVERY_REQUIRED', store.results[1]['status'])

    def test_recovery_required_is_retained_across_polls_without_new_stop(self):
        store, executor = MemoryStore(), MockExecutor()
        store.claims.add(1)
        original = b.result(1, 'RECOVERY_REQUIRED', 'RESTORING_ORIGINAL', error='ORIGINAL_RECOVERY_FAILED')
        store.results[1] = original
        store.mutations[1] = [{'kind': 'stop', 'state': 'completed'}]
        b.run_queue(store, lambda: executor)
        self.assertEqual(original, store.results[1])
        self.assertEqual([], executor.calls)

    def test_readiness_confirmation_boolean_shape_is_strict(self):
        config = {'schema': 1, 'containerName': 'worksystem-nexus',
                  'imageId': 'sha256:617de9147a594bd1fb8d942cd56f1135ebb61cc622566269907e178377b3fdbd',
                  'volume': 'nexus-data', 'keyFile': '/etc/deployops/nexus-secrets/secrets.json',
                  'heap': '1g', 'backupRoot': '/var/backups/deployops-nexus', 'appUser': 'deployops',
                  'externalWritersManaged': False, 'allStorageInDataVolume': False}
        self.assertEqual(config, b.validate_config(config))
        for key in ('externalWritersManaged', 'allStorageInDataVolume'):
            for value in ['true', 1, None]:
                with self.assertRaises(ValueError):
                    b.validate_config(dict(config, **{key: value}))
        with self.assertRaises(ValueError):
            b.validate_config(dict(config, backupRoot='/var/backups/../root'))

    def test_stop_failure_after_helper_recovers_returns_failed_releases_handoff(self):
        final = b.process_job(MemoryStore(), 1, MockExecutor('backup', recovered=True))
        self.assertEqual('FAILED', final['status'])
        self.assertEqual('BACKUP_FAILED', final['errorCode'])
        self.assertTrue(b.releases_maintenance(final))
        self.assertNotIn('secret', json.dumps(final))

    def test_recovery_failure_does_not_release_handoff(self):
        final = b.process_job(MemoryStore(), 1, MockExecutor('backup', recovered=False))
        self.assertEqual('RECOVERY_REQUIRED', final['status'])
        self.assertFalse(final['originalStateRestored'])
        self.assertFalse(b.releases_maintenance(final))

    def test_verification_failure_cannot_publish_completed(self):
        final = b.process_job(MemoryStore(), 1, MockExecutor('verify'))
        self.assertEqual('FAILED', final['status'])
        self.assertIsNone(final['manifestSha256'])

    def test_completed_requires_exact_id_manifest_and_recovery(self):
        good = b.result(1, 'COMPLETED', 'DONE', True, 'nexus-backup-1', 'a' * 64)
        for key, value in [('artifactId', 'nexus-backup-2'), ('originalStateRestored', False),
                           ('manifestSha256', 'A' * 64), ('stage', 'STOPPING'), ('errorCode', 'BACKUP_FAILED')]:
            wrong = dict(good, **{key: value})
            with self.assertRaises(ValueError):
                b.validate_result(wrong, 1)

    def test_id_boolean_overflow_zero_rejected(self):
        for value in [True, False, 0, -1, '1', 9223372036854775808]:
            self.assertFalse(b.positive_id(value))

    def test_executor_never_considers_changed_identity_or_failed_health_recovered(self):
        for actual in [{'image': 'changed'}, {'image': 'same'}]:
            def bad_health(cid):
                raise ValueError('not healthy')
            lib = SimpleNamespace(inspect=lambda cid: {'State': {'Running': True}},
                                  projection=lambda obj: actual, wait_health=bad_health)
            executor = b.Executor({}, lib)
            executor.cid, executor.started, executor.original = 'a' * 64, True, {'image': 'same'}
            self.assertFalse(executor.recovered())

    def recovery_store(self):
        store = MemoryStore()
        store.claims.add(1)
        store.results[1] = b.result(1, 'RECOVERY_REQUIRED', 'RESTORING_ORIGINAL', error='ORIGINAL_RECOVERY_FAILED')
        store.baselines[1] = {'schema': 1, 'requestId': 1}
        # Known authoritative mutation completion permits the existing readonly
        # recovery-check contract. Legacy absent proof is tested separately below.
        store.mutations[1] = [{'kind': 'stop', 'state': 'completed'}]
        store.check_jobs[(1, 7)] = {'schema': 1, 'requestId': 1, 'action': 'CHECK_RECOVERY', 'checkId': 7}
        return store

    def test_new_request_initializes_ledger_before_claim_and_backup(self):
        class InitializedBeforeClaim(MemoryStore):
            def claim(self, request_id):
                self.assert_initialized = request_id in self.mutations and not self.mutations[request_id]
                if not self.assert_initialized:
                    raise AssertionError('Ledger must be initialized before claim')
                super().claim(request_id)
        store, executor = InitializedBeforeClaim(), MockExecutor()
        final = b.process_job(store, 1, executor)
        self.assertTrue(store.assert_initialized)
        self.assertEqual(['prepare', 'backup', 'verify'], executor.calls)
        self.assertEqual('COMPLETED', final['status'])
        self.assertFalse(store.mutation_uncertain(1))

    def test_legacy_missing_ledger_recovery_check_keeps_unknown_hold(self):
        store, executor = self.recovery_store(), MockExecutor()
        del store.mutations[1]
        self.assertTrue(store.mutation_uncertain(1))
        self.assertTrue(b.process_check(store, 1, 7, executor))
        self.assertEqual([], executor.calls)
        final = store.check_results[(1, 7)]
        self.assertEqual('RECOVERY_REQUIRED', final['status'])
        self.assertFalse(final['originalStateRestored'])
        self.assertFalse(b.releases_maintenance(store.results[1]))
        self.assertFalse(b.releases_maintenance(final))
        self.assertNotIn(1, store.mutations)

    def test_recovery_check_success_is_failed_not_backup_success_and_permanent_dedup(self):
        store, executor = self.recovery_store(), MockExecutor()
        self.assertTrue(b.process_check(store, 1, 7, executor))
        self.assertEqual(['read-only-check'], executor.calls)
        final = store.check_results[(1, 7)]
        self.assertEqual(b.FIELDS | {'checkId'}, set(final))
        self.assertEqual(7, final['checkId'])
        self.assertEqual('FAILED', final['status'])
        self.assertTrue(final['originalStateRestored'])
        self.assertIsNone(final['artifactId'])
        self.assertIsNone(final['manifestSha256'])
        self.assertTrue(b.releases_maintenance(store.results[1]))
        executor.calls.clear()
        self.assertFalse(b.process_check(store, 1, 7, executor))
        self.assertEqual([], executor.calls)

    def test_recovery_missing_malformed_stale_or_timed_out_check_never_releases(self):
        for mode in ('missing', 'wrong-id', 'wrong-check', 'extra', 'closed', 'health-timeout'):
            store = self.recovery_store()
            executor = MockExecutor('health' if mode == 'health-timeout' else None)
            if mode == 'missing': store.baselines.clear()
            if mode == 'wrong-id': store.check_jobs[(1, 7)]['requestId'] = 2
            if mode == 'wrong-check': store.check_jobs[(1, 7)]['checkId'] = 8
            if mode == 'extra': store.check_jobs[(1, 7)]['containerId'] = 'other'
            if mode == 'closed': store.results[1] = b.result(1, 'COMPLETED', 'DONE', True, 'nexus-backup-1', 'a' * 64)
            b.process_check(store, 1, 7, executor)
            self.assertFalse(b.releases_maintenance(store.check_results[(1, 7)]), mode)
            self.assertEqual(['read-only-check'] if mode == 'health-timeout' else [], executor.calls)

    def test_pending_signal_does_not_claim_or_execute_recovery_check(self):
        store, executor = self.recovery_store(), MockExecutor()
        b.request_shutdown(None, None)
        self.assertFalse(b.process_check(store, 1, 7, executor))
        self.assertEqual(set(), store.check_claims)
        self.assertEqual([], executor.calls)

    def test_old_root_success_then_explicit_supersede_revalidates_without_backup_replay(self):
        store, old_executor = self.recovery_store(), MockExecutor()
        b.process_check(store, 1, 7, old_executor)
        old_result = store.check_results[(1, 7)].copy()
        self.assertTrue(b.releases_maintenance(store.results[1]))
        # Java has not accepted old check 7; operator explicitly supersedes with 8.
        # Root never infers acceptance or automatically generates a new check.
        store.check_jobs[(1, 8)] = {'schema': 1, 'requestId': 1, 'action': 'CHECK_RECOVERY', 'checkId': 8}
        new_executor = MockExecutor()
        b.process_check(store, 1, 8, new_executor)
        self.assertEqual(['read-only-check'], new_executor.calls)
        self.assertEqual(old_result, store.check_results[(1, 7)])
        final = store.check_results[(1, 8)]
        self.assertEqual(8, final['checkId'])
        self.assertEqual('FAILED', final['status'])
        self.assertTrue(final['originalStateRestored'])
        self.assertIsNone(final['artifactId'])
        self.assertIsNone(final['manifestSha256'])
        self.assertNotIn('backup', new_executor.calls)

    def test_fresh_negative_superseding_check_holds_primary_before_check_result(self):
        for mode in ('unhealthy', 'mismatch', 'missing-baseline'):
            store = self.recovery_store()
            b.process_check(store, 1, 7, MockExecutor())
            old_result = store.check_results[(1, 7)].copy()
            store.check_jobs[(1, 8)] = {'schema': 1, 'requestId': 1, 'action': 'CHECK_RECOVERY', 'checkId': 8}
            if mode == 'missing-baseline': store.baselines.clear()
            events = []
            original_publish, original_check = store.publish, store.publish_check
            store.publish = lambda value: (events.append('primary'), original_publish(value))[-1]
            store.publish_check = lambda value, check: (events.append('check'), original_check(value, check))[-1]
            executor = MockExecutor(mode)
            b.process_check(store, 1, 8, executor)
            self.assertEqual(['primary', 'check'], events)
            self.assertFalse(b.releases_maintenance(store.results[1]))
            self.assertFalse(b.releases_maintenance(store.check_results[(1, 8)]))
            self.assertEqual(old_result, store.check_results[(1, 7)])
            self.assertEqual([] if mode == 'missing-baseline' else ['read-only-check'], executor.calls)

    def test_primary_hold_write_failure_does_not_publish_check_and_bad_check_cannot_mutate_primary(self):
        store = self.recovery_store()
        b.process_check(store, 1, 7, MockExecutor())
        prior = store.results[1].copy()
        store.check_jobs[(1, 8)] = {'schema': 1, 'requestId': 2, 'action': 'CHECK_RECOVERY', 'checkId': 8}
        b.process_check(store, 1, 8, MockExecutor())
        self.assertEqual(prior, store.results[1])
        store.check_jobs[(1, 9)] = {'schema': 1, 'requestId': 1, 'action': 'CHECK_RECOVERY', 'checkId': 9}
        def failed(value): raise OSError('primary fsync failed')
        store.publish = failed
        with self.assertRaises(OSError): b.process_check(store, 1, 9, MockExecutor('unhealthy'))
        self.assertNotIn((1, 9), store.check_results)
        self.assertIn((1, 9), store.check_claims)

    def test_recovery_check_precedes_uncertain_backup_queue_without_new_backup(self):
        store, executor = self.recovery_store(), MockExecutor()
        store.jobs[2] = {'schema': 1, 'requestId': 2, 'action': 'BACKUP'}
        b.run_queue(store, lambda: executor)
        self.assertEqual(['read-only-check'], executor.calls)
        self.assertEqual({1}, store.claims)

    def test_baseline_durability_failure_prevents_stop(self):
        class FailedBaseline(MemoryStore):
            def save_baseline(self, request_id, value):
                raise OSError('fsync failed')
        executor = MockExecutor()
        final = b.process_job(FailedBaseline(), 1, executor)
        self.assertEqual(['prepare', 'recovery-check'], executor.calls)
        self.assertNotEqual('COMPLETED', final['status'])

    def test_baseline_write_uses_exclusive_root0600_and_file_directory_fsync(self):
        # Real temporary file bytes with mocked POSIX root/path/directory syscalls;
        # Windows filesystem cannot prove Linux ownership/mode itself.
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            (base / 'state').mkdir()
            store = b.Store(base, 1000, 1000)
            real_open, real_close, real_fsync = b.os.open, b.os.close, b.os.fsync
            calls = []
            sentinel = 98765
            def opened(path, flags, mode=0o777):
                calls.append((str(path), flags, mode))
                if str(path) == str(base / 'state'):
                    return sentinel
                return real_open(path, flags & ~getattr(b.os, 'O_NOFOLLOW', 0), mode)
            def synced(fd):
                calls.append(('fsync', fd))
                if fd != sentinel: real_fsync(fd)
            def closed(fd):
                if fd != sentinel: real_close(fd)
            with mock.patch.object(b, 'path_parents'), mock.patch.object(b.os, 'open', side_effect=opened), \
                    mock.patch.object(b.os, 'fsync', side_effect=synced), mock.patch.object(b.os, 'close', side_effect=closed), \
                    mock.patch.object(b.os, 'O_NOFOLLOW', getattr(b.os, 'O_NOFOLLOW', 0), create=True), \
                    mock.patch.object(b.os, 'O_DIRECTORY', getattr(b.os, 'O_DIRECTORY', 0), create=True):
                store.save_baseline(1, {'schema': 1, 'requestId': 1})
                with self.assertRaises(FileExistsError): store.save_baseline(1, {'schema': 1, 'requestId': 2})
            self.assertTrue(calls[0][1] & b.os.O_EXCL)
            self.assertEqual(0o600, calls[0][2])
            self.assertEqual(2, sum(1 for c in calls if c[0] == 'fsync'))
            self.assertEqual(1, json.loads((base / 'state' / '1.baseline.json').read_text())['requestId'])

    def test_exact_baseline_identity_config_mount_running_and_health_read_only(self):
        config = {'imageId': 'sha256:' + 'a' * 64, 'containerName': 'nexus', 'volume': 'data',
                  'keyFile': '/key', 'heap': '1g'}
        original = {'Id': 'b' * 64, 'Image': config['imageId'], 'State': {'Running': True}, 'mounts': ['data:key:ro']}
        counters = {'inspect': 0, 'contract': 0, 'health': 0, 'stop': 0, 'start': 0, 'backup': 0}
        def contract(args):
            counters['contract'] += 1
            return original, None, None, None
        def health(cid):
            counters['health'] += 1
        lib = SimpleNamespace(source_contract=contract, projection=lambda i: {'id': i['Id'], 'image': i['Image'], 'mounts': i['mounts']}, wait_health=health)
        executor = b.Executor(config, lib)
        executor.cid, executor.original = original['Id'], lib.projection(original)
        baseline = executor.baseline(1)
        self.assertTrue(executor.check_recovery(baseline, 1))
        for field, wrong in [('containerId', 'c' * 64), ('imageId', 'sha256:' + 'd' * 64),
                             ('projectionSha256', 'e' * 64), ('configSha256', 'f' * 64), ('originalRunning', False), ('requestId', 2)]:
            with self.assertRaises(ValueError, msg=field):
                executor.check_recovery(dict(baseline, **{field: wrong}), 1)
        original['mounts'] = ['changed:rw']
        with self.assertRaises(ValueError): executor.check_recovery(baseline, 1)
        original['mounts'] = ['data:key:ro']
        original['State']['Running'] = False
        with self.assertRaises(ValueError): executor.check_recovery(baseline, 1)
        original['State']['Running'] = True
        lib.wait_health = lambda cid: (_ for _ in ()).throw(ValueError('timeout'))
        with self.assertRaises(ValueError): executor.check_recovery(baseline, 1)
        self.assertEqual(0, counters['stop'] + counters['start'] + counters['backup'])


if __name__ == '__main__':
    unittest.main()
