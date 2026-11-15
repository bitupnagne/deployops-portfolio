import ast
import io
import json
import runpy
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1] / 'nexus-backup'
lib = SimpleNamespace(**runpy.run_path(str(ROOT / 'nexus-local-backup-restore.py')))
g = lib.call.__globals__
broker = runpy.run_path(str(ROOT / 'nexus-backup-broker.py'))
cid = 'a' * 64
image = 'sha256:' + 'b' * 64


class Tests(unittest.TestCase):
    def setUp(self):
        g['DEADLINE'] = None
        g['UNCERTAIN_STOP'] = False
        g['MUTATION_OBSERVER'] = None

    def timeout_client(self):
        p = Mock(stdout=io.BytesIO(), stderr=io.BytesIO(), returncode=None)
        p.communicate.side_effect = subprocess.TimeoutExpired('docker', 1)
        p.wait.side_effect = subprocess.TimeoutExpired('docker', 2)
        return p

    def test_daemon_applied_stop_timeout_is_uncertain_not_retry(self):
        p = self.timeout_client()
        applied = []
        def launch(*args, **kwargs):
            applied.append('stop applied by daemon')
            return p
        with patch.object(lib.subprocess, 'Popen', side_effect=launch) as start:
            with self.assertRaises(lib.MutationUncertain):
                lib.call(['stop', '--time', '120', cid])
        self.assertEqual(applied, ['stop applied by daemon'])
        self.assertEqual(start.call_count, 1)
        p.kill.assert_called_once_with()
        p.wait.assert_called_once_with(timeout=2)
        self.assertTrue(g['UNCERTAIN_STOP'])

    def test_read_timeout_does_not_mark_mutation(self):
        with patch.object(lib.subprocess, 'Popen', return_value=self.timeout_client()):
            with self.assertRaisesRegex(ValueError, 'READ_CLI_TIMEOUT'):
                lib.inspect(cid)
        self.assertFalse(g['UNCERTAIN_STOP'])

    def test_deadline_before_launch_has_no_mutation(self):
        g['DEADLINE'] = 0
        with patch.object(lib.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(ValueError, 'OPERATION_DEADLINE'):
                lib.call(['load', '--input', 'partial'])
        launch.assert_not_called()

    def test_partial_save_and_load_timeout_not_retried(self):
        for operation in ('save', 'load', 'run', 'volume'):
            p = self.timeout_client()
            with patch.object(lib.subprocess, 'Popen', return_value=p) as launch:
                with self.assertRaises(lib.MutationUncertain):
                    lib.call([operation, 'create' if operation == 'volume' else '--input', 'partial'])
                self.assertEqual(launch.call_count, 1)
                self.assertEqual(p.communicate.call_args.kwargs['timeout'], 600 if operation in ('save', 'load') else 120)

    def test_health_has_total_deadline_and_no_probe_after_expiry(self):
        clock = [0]
        def advance(seconds): clock[0] += seconds
        def probe(_):
            clock[0] += 15
            return {'State': {'Running': False}}
        with patch.object(lib.time, 'monotonic', side_effect=lambda: clock[0]), patch.object(lib.time, 'sleep', side_effect=advance), patch.dict(g, inspect=Mock(side_effect=probe)):
            with self.assertRaisesRegex(ValueError, 'OPERATION_DEADLINE'):
                lib.wait_health(cid)
            self.assertEqual(clock[0], 300)
            self.assertEqual(g['inspect'].call_count, 15)

    def test_pending_stop_running_observation_never_claims_restore_or_starts(self):
        g['UNCERTAIN_STOP'] = True
        with patch.dict(g, inspect=Mock(return_value={'State': {'Running': True}}), projection=lambda _: {'id': cid}, call=Mock()):
            with self.assertRaises(lib.RecoveryRequired):
                lib.restore_original(cid, {'id': cid}, True)
            g['call'].assert_not_called()

    def test_recovery_budget_is_independent_of_expired_work(self):
        g['DEADLINE'] = 0
        with lib.budget(360, independent=True):
            self.assertGreater(lib.remaining(120), 0)
        self.assertEqual(g['DEADLINE'], 0)

    def backup_fixture(self, folder, failure):
        root = Path(folder)
        mp = root / 'data'; mp.mkdir()
        keydir = root / 'source-keys'; keydir.mkdir()
        key = keydir / 'existing-key'; key.write_bytes(b'private fixture')
        out = root / 'partial'
        original = {'id': cid}
        running = {'State': {'Running': True}, 'Image': image}
        stopped = {'State': {'Running': False, 'ExitCode': 0}, 'Image': image}
        a = SimpleNamespace(container_id=cid, image_id=image, output=str(out), volume='data')
        restore = Mock()
        def call(args):
            if args[0] == 'save':
                (out / 'image.tar').write_bytes(b'partial image')
                raise failure
            return ''
        mocks = dict(source_contract=lambda _: (running, mp, key, []), projection=lambda _: original,
                     new_path=lambda _: out, inspect=Mock(return_value=stopped),
                     command=Mock(return_value=SimpleNamespace(returncode=0)), call=call,
                     safe_members=lambda _: [], validate_binding=lambda *a: None,
                     archive_binding=lambda *a: None, restore_original=restore,
                     write_auxiliary_diagnostic=Mock())
        return a, out, restore, mocks

    def test_partial_save_runs_finally_once_and_preserves_without_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            a, out, restore, mocks = self.backup_fixture(folder, lib.MutationUncertain('MUTATION_RESULT_UNCERTAIN'))
            with patch.dict(g, **mocks):
                with self.assertRaises(lib.MutationUncertain): lib.backup(a)
            restore.assert_called_once()
            self.assertTrue((out / 'image.tar').exists())
            self.assertFalse((out / 'manifest.json').exists())
            self.assertFalse((out / 'backup.result.json').exists())

    def test_recovery_timeout_overrides_success_claim_and_keeps_partial(self):
        with tempfile.TemporaryDirectory() as folder:
            a, out, restore, mocks = self.backup_fixture(folder, ValueError('COPY_FAILED'))
            restore.side_effect = lib.MutationUncertain('MUTATION_RESULT_UNCERTAIN')
            with patch.dict(g, **mocks):
                with self.assertRaises(lib.RecoveryRequired): lib.backup(a)
            restore.assert_called_once()
            self.assertTrue(out.exists())
            self.assertFalse((out / 'backup.result.json').exists())

    def test_backup_pending_stop_preserves_hold_even_when_inspect_running(self):
        with tempfile.TemporaryDirectory() as folder:
            a, out, restore, mocks = self.backup_fixture(folder, ValueError('UNREACHED'))
            del mocks['restore_original']
            mocks['inspect'] = Mock(return_value={'State': {'Running': True}, 'Image': image})
            def pending_stop(args):
                g['UNCERTAIN_STOP'] = True
                raise lib.MutationUncertain('MUTATION_RESULT_UNCERTAIN')
            mocks['command'] = Mock(side_effect=pending_stop)
            mocks['call'] = Mock()
            with patch.dict(g, **mocks):
                with self.assertRaises(lib.RecoveryRequired): lib.backup(a)
                mocks['command'].assert_called_once()
                mocks['call'].assert_not_called()
                self.assertGreaterEqual(mocks['inspect'].call_count, 1)
            self.assertFalse((out / 'manifest.json').exists())
            self.assertFalse((out / 'backup.result.json').exists())

    def test_log_reader_implicit_wait_is_bounded(self):
        p = Mock(stdout=io.BytesIO(), stderr=io.BytesIO())
        lib.BoundedLogProcess(p).wait()
        p.wait.assert_called_once_with(timeout=2)

    def restore_fixture(self, folder, failure_at, bad_label=False):
        root = Path(folder) / 'artifact'; root.mkdir()
        (root / 'keys.json').write_bytes(b'private fixture')
        mp = Path(folder) / 'volume'; mp.mkdir()
        keydir = Path(folder) / 'new-key'
        a = SimpleNamespace(artifact=str(root), new_name='clone', new_volume='clone-data',
                            network='private-net', ports='8081:18091',
                            new_key_directory=str(keydir), result=str(Path(folder) / 'result'))
        m = dict(sourceVolume='original-data', sourceName='original', containerPorts=[8081],
                 imageId=image, heap='1g', dataRootUid=200, dataRootGid=200, dataRootMode=0o750)
        calls = []
        def call(args):
            calls.append(args[0])
            if args[0] == failure_at: raise lib.MutationUncertain('MUTATION_RESULT_UNCERTAIN')
            if args[0] == 'network': return '[{"Name":"private-net","Driver":"bridge","Internal":true}]'
            if args[0] == 'image': return image
            if args[0] == 'run': return cid
            return ''
        i = {'Name': '/clone', 'Image': image, 'Config': {'Labels': {'deployops.nexus.restore': 'other' if bad_label else 'clone'}}, 'Mounts': [
            {'Type': 'volume', 'Name': 'clone-data', 'Destination': '/nexus-data'},
            {'Type': 'bind', 'Source': str(keydir / 'secrets.json'), 'Destination': '/run/nexus-secrets/secrets.json', 'RW': False}]}
        mocks = dict(verify_artifact=lambda _: (root, m), call=call, new_path=Path,
                     prepare_key_directory=lambda p: p.mkdir(), validate_key=lambda _: None,
                     volume_path=lambda _: mp, safe_members=lambda _: [],
                     validate_binding=lambda *a: None, inspect=Mock(return_value=i),
                     wait_health=Mock(side_effect=ValueError('HEALTH_FAILED')))
        return a, calls, keydir, mocks

    def test_restore_load_timeout_preserves_and_never_creates_or_retries(self):
        with tempfile.TemporaryDirectory() as folder:
            a, calls, keydir, mocks = self.restore_fixture(folder, 'load')
            with patch.dict(g, **mocks), patch.object(lib.socket, 'socket'), patch.object(lib.os, 'chown', create=True), patch.object(lib.os, 'chmod'):
                with self.assertRaises(lib.MutationUncertain): lib.restore(a)
            self.assertEqual(calls.count('load'), 1)
            self.assertEqual(calls.count('volume'), 1)  # Readonly collision list only.
            self.assertNotIn('run', calls)
            self.assertNotIn('stop', calls)
            self.assertFalse(keydir.exists())

    def test_restore_run_timeout_preserves_volume_and_key_without_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            a, calls, keydir, mocks = self.restore_fixture(folder, 'run')
            with patch.dict(g, **mocks), patch.object(lib.socket, 'socket'), patch.object(lib.os, 'chown', create=True), patch.object(lib.os, 'chmod'), patch.object(lib.tarfile, 'open'):
                with self.assertRaises(lib.MutationUncertain): lib.restore(a)
            self.assertEqual(calls.count('run'), 1)
            self.assertNotIn('stop', calls)
            self.assertTrue((keydir / 'secrets.json').exists())
            mocks['inspect'].assert_not_called()

    def test_wrong_label_failure_never_stops_an_unowned_container(self):
        with tempfile.TemporaryDirectory() as folder:
            a, calls, keydir, mocks = self.restore_fixture(folder, None, bad_label=True)
            with patch.dict(g, **mocks), patch.object(lib.socket, 'socket'), patch.object(lib.os, 'chown', create=True), patch.object(lib.os, 'chmod'), patch.object(lib.tarfile, 'open'):
                with self.assertRaisesRegex(ValueError, 'RESTORE_CLEANUP_IDENTITY_REQUIRED'): lib.restore(a)
            self.assertNotIn('stop', calls)
            self.assertTrue(keydir.exists())

    def test_confirmed_owned_container_cleanup_stop_is_once_and_bounded(self):
        with tempfile.TemporaryDirectory() as folder:
            a, calls, keydir, mocks = self.restore_fixture(folder, 'stop')
            with patch.dict(g, **mocks), patch.object(lib.socket, 'socket'), patch.object(lib.os, 'chown', create=True), patch.object(lib.os, 'chmod'), patch.object(lib.tarfile, 'open'):
                with self.assertRaises(lib.MutationUncertain): lib.restore(a)
            self.assertEqual(calls.count('stop'), 1)
            self.assertEqual(mocks['inspect'].call_count, 2)
            self.assertTrue(keydir.exists())

    def test_identity_requires_exact_label_and_mounts_before_cleanup(self):
        a = SimpleNamespace(new_name='clone', new_volume='clone-data')
        i = {'Name': '/clone', 'Image': image, 'Config': {'Labels': {'deployops.nexus.restore': 'clone'}}, 'Mounts': [
            {'Type': 'volume', 'Name': 'clone-data', 'Destination': '/nexus-data'},
            {'Type': 'bind', 'Source': str(Path('/private/key')), 'Destination': '/run/nexus-secrets/secrets.json', 'RW': False}]}
        self.assertTrue(lib.restore_identity(i, a, {'imageId': image}, Path('/private/key')))
        i['Config']['Labels']['deployops.nexus.restore'] = 'other'
        self.assertFalse(lib.restore_identity(i, a, {'imageId': image}, Path('/private/key')))
        i['Config']['Labels']['deployops.nexus.restore'] = 'clone'
        i['Mounts'][1]['RW'] = True
        self.assertFalse(lib.restore_identity(i, a, {'imageId': image}, Path('/private/key')))

    def test_broker_uncertainty_overrides_recovered_true_and_holds(self):
        for error in (lib.MutationUncertain('MUTATION_RESULT_UNCERTAIN'), lib.RecoveryRequired('ORIGINAL_RECOVERY_REQUIRED')):
            store = Mock()
            store.claim_exists.return_value = False
            store.mutation_uncertain.return_value = False
            executor = Mock(lib=lib)
            executor.backup.side_effect = error
            executor.recovered.return_value = True
            with patch.dict(broker['process_job'].__globals__, validate_request=Mock()):
                result = broker['process_job'](store, 2, executor)
            self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
            self.assertIs(result['originalStateRestored'], False)
            self.assertFalse(broker['releases_maintenance'](result))
            executor.recovered.assert_not_called()
            executor.backup.assert_called_once()
            executor.verify.assert_not_called()
            store.claim.assert_called_once_with(2)

    def test_existing_failure_can_release_only_when_recovered(self):
        store = Mock(); store.claim_exists.return_value = False
        store.mutation_uncertain.return_value = False
        executor = Mock(lib=lib); executor.backup.side_effect = ValueError('COPY_FAILED')
        executor.recovered.return_value = True
        with patch.dict(broker['process_job'].__globals__, validate_request=Mock()):
            result = broker['process_job'](store, 2, executor)
        self.assertEqual(result['status'], 'FAILED')
        self.assertTrue(broker['releases_maintenance'](result))

    def test_candidates_python36_syntax_and_current_product_pin_matches_library(self):
        for filename in ('nexus-local-backup-restore.py', 'nexus-backup-broker.py'):
            ast.parse((ROOT / filename).read_text(), feature_version=(3, 6))
        self.assertEqual(lib.hashlib.sha256((ROOT / 'nexus-local-backup-restore.py').read_bytes()).hexdigest(),
                         broker['LIBRARY_SHA'])

    def ledger_fixture(self):
        disk = {}
        store_type = broker['Store']
        def worker():
            store = store_type.__new__(store_type)
            store.base = Path('/synthetic-root')
            store.request = lambda request_id: {'schema': 1, 'requestId': request_id, 'action': 'BACKUP'}
            def write(path, value, exclusive=False):
                if exclusive and str(path) in disk:
                    raise FileExistsError('synthetic exclusive collision')
                disk[str(path)] = json.dumps(value).encode()
            store.write = write
            return store
        disk[str(worker().mutation_path(2))] = json.dumps({'schema': 1, 'requestId': 2, 'events': []}).encode()
        return disk, worker

    def ledger_io(self, disk):
        return patch.dict(broker['Store'].mutation_ledger.__globals__,
                          read_file=lambda path, *args, **kwargs: disk[str(path)])

    def test_inflight_survives_worker_restart_and_completed_other_event(self):
        disk, worker = self.ledger_fixture()
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
            before = worker()
            stop = before.begin_mutation(2, 'stop')
            start = before.begin_mutation(2, 'start')
            before.complete_mutation(2, start)
            after = worker()
            self.assertTrue(after.mutation_uncertain(2))
            self.assertEqual(after.mutation_ledger(2)['events'], [
                {'sequence': stop, 'kind': 'stop', 'state': 'inflight'},
                {'sequence': start, 'kind': 'start', 'state': 'completed'}])
            self.assertIn(str(after.mutation_path(2)), disk)

    def test_authoritative_completion_retains_auditable_events(self):
        disk, worker = self.ledger_fixture()
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
            store = worker()
            event = store.begin_mutation(2, 'stop')
            store.complete_mutation(2, event)
            self.assertFalse(worker().mutation_uncertain(2))
            self.assertEqual(worker().mutation_ledger(2)['events'][0]['state'], 'completed')
            self.assertEqual(len(disk), 1)

    def test_malformed_durable_marker_is_hold(self):
        disk, worker = self.ledger_fixture()
        store = worker()
        disk[str(store.mutation_path(2))] = b'{malformed'
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
            self.assertTrue(store.mutation_uncertain(2))
            with self.assertRaises(Exception): store.begin_mutation(2, 'start')

    def test_durable_begin_precedes_cli_and_completion_follows_response(self):
        events = []
        observer = SimpleNamespace(begin=lambda kind: events.append('inflight-' + kind) or 1,
                                   complete=lambda token: events.append('completed-' + str(token)))
        p = Mock(returncode=0); p.communicate.side_effect = lambda **kwargs: (events.append('daemon-response') or b'', b'')
        def launch(*args, **kwargs): events.append('cli-launch'); return p
        with patch.dict(g, MUTATION_OBSERVER=observer), patch.object(lib.subprocess, 'Popen', side_effect=launch):
            lib.call(['stop', '--time', '120', cid])
        self.assertEqual(events, ['inflight-stop', 'cli-launch', 'daemon-response', 'completed-1'])

    def test_timeout_never_completes_durable_event_or_retries(self):
        observer = SimpleNamespace(begin=Mock(return_value=1), complete=Mock())
        with patch.dict(g, MUTATION_OBSERVER=observer), patch.object(lib.subprocess, 'Popen', return_value=self.timeout_client()) as launch:
            with self.assertRaises(lib.MutationUncertain): lib.call(['stop', '--time', '120', cid])
        observer.begin.assert_called_once_with('stop')
        observer.complete.assert_not_called()
        launch.assert_called_once()

    def test_durable_begin_failure_prevents_mutation_launch(self):
        observer = SimpleNamespace(begin=Mock(side_effect=OSError('synthetic failure')), complete=Mock())
        with patch.dict(g, MUTATION_OBSERVER=observer), patch.object(lib.subprocess, 'Popen') as launch:
            with self.assertRaises(OSError): lib.call(['start', cid])
        launch.assert_not_called()
        observer.complete.assert_not_called()

    def test_nonzero_stop_keeps_inflight_and_blocks_recovery_start(self):
        observer = SimpleNamespace(begin=Mock(return_value=1), complete=Mock())
        p = Mock(returncode=1); p.communicate.return_value = (b'', b'')
        with patch.dict(g, MUTATION_OBSERVER=observer), patch.object(lib.subprocess, 'Popen', return_value=p):
            with self.assertRaisesRegex(ValueError, 'DOCKER_COMMAND_FAILED'):
                lib.call(['stop', '--time', '120', cid])
            self.assertTrue(g['UNCERTAIN_STOP'])
        observer.complete.assert_not_called()

    def test_restart_old_recovered_result_with_inflight_never_replays_or_releases(self):
        store = Mock(); store.claim_exists.return_value = True
        store.mutation_uncertain.return_value = True
        store.get_result.return_value = broker['result'](2, 'FAILED', 'DONE', True, error='BACKUP_FAILED')
        executor = Mock(lib=lib)
        final = broker['process_job'](store, 2, executor)
        self.assertEqual(final['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(broker['releases_maintenance'](final))
        executor.backup.assert_not_called()
        executor.recovered.assert_not_called()

    def test_restart_check_running_healthy_cannot_release_inflight(self):
        disk, worker = self.ledger_fixture()
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
            worker().begin_mutation(2, 'stop')
            store = Mock(base=Path('/synthetic-root'))
            store.claim_check.return_value = True
            store.claim_exists.return_value = True
            store.get_result.return_value = broker['result'](2, 'RECOVERY_REQUIRED', 'RESTORING_ORIGINAL', error='ORIGINAL_RECOVERY_FAILED')
            store.mutation_uncertain.side_effect = worker().mutation_uncertain
            executor = Mock(lib=lib)
            executor.check_recovery.return_value = True  # New worker would see running and healthy.
            g['UNCERTAIN_STOP'] = False  # Process-local state lost on restart.
            with patch.dict(broker['process_check'].__globals__, validate_check=Mock()):
                self.assertTrue(broker['process_check'](store, 2, 7, executor))
            executor.check_recovery.assert_not_called()
            final = store.publish.call_args.args[0]
            self.assertEqual(final['status'], 'RECOVERY_REQUIRED')
            self.assertIs(final['originalStateRestored'], False)
            self.assertFalse(broker['releases_maintenance'](final))

    def test_ledger_uncertainty_overrides_generic_exception_recovered_true(self):
        store = Mock(); store.claim_exists.return_value = False
        store.mutation_uncertain.return_value = True
        executor = Mock(lib=lib); executor.backup.side_effect = OSError('synthetic client failure')
        executor.recovered.return_value = True
        with patch.dict(broker['process_job'].__globals__, validate_request=Mock()):
            final = broker['process_job'](store, 2, executor)
        self.assertEqual(final['status'], 'RECOVERY_REQUIRED')
        self.assertFalse(broker['releases_maintenance'](final))
        executor.recovered.assert_not_called()

    def test_upgrade_restart_missing_ledger_is_unknown_not_empty(self):
        disk, worker = self.ledger_fixture()
        disk.clear()
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
            store = worker()
            self.assertTrue(store.mutation_uncertain(2))
            with self.assertRaises(ValueError): store.mutation_ledger(2)
            with self.assertRaises(ValueError): store.begin_mutation(2, 'start')
            self.assertEqual(disk, {})

    def test_fresh_empty_ledger_is_exclusive_and_before_claim_work(self):
        disk, worker = self.ledger_fixture()
        disk.clear()
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
            store = worker()
            store.initialize_mutation_ledger(2)
            self.assertEqual(store.mutation_ledger(2)['events'], [])
            self.assertFalse(store.mutation_uncertain(2))
            before = dict(disk)
            store.initialize_mutation_ledger(2)
            self.assertEqual(disk, before)
        mock = Mock(); mock.claim_exists.return_value = False
        mock.mutation_uncertain.return_value = False
        executor = Mock(lib=lib); executor.backup.side_effect = ValueError('COPY_FAILED')
        executor.recovered.return_value = True
        order = []
        mock.initialize_mutation_ledger.side_effect = lambda _: order.append('durable-empty-ledger')
        mock.claim.side_effect = lambda _: order.append('claim')
        executor.prepare.side_effect = lambda _: order.append('prepare')
        with patch.dict(broker['process_job'].__globals__, validate_request=Mock()):
            broker['process_job'](mock, 2, executor)
        self.assertEqual(order, ['durable-empty-ledger', 'claim', 'prepare'])

    def test_existing_claim_cannot_be_backfilled_with_empty_ledger(self):
        disk, worker = self.ledger_fixture()
        disk.clear()
        claim = str(worker().base / 'state' / '2.json')
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk or path == claim):
            with self.assertRaises(ValueError): worker().initialize_mutation_ledger(2)
        self.assertEqual(disk, {})

    def test_legacy_unresolved_checks_without_ledger_never_call_health_or_release(self):
        for status in ('RECOVERY_REQUIRED', 'RUNNING', 'RECONCILING'):
            disk, worker = self.ledger_fixture(); disk.clear()
            with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
                store = Mock(base=Path('/synthetic-root'))
                store.claim_check.return_value = True; store.claim_exists.return_value = True
                store.get_result.return_value = broker['result'](2, status, 'RESTORING_ORIGINAL', error='RESULT_UNCERTAIN')
                store.mutation_uncertain.side_effect = worker().mutation_uncertain
                executor = Mock(lib=lib); executor.check_recovery.return_value = True
                with patch.dict(broker['process_check'].__globals__, validate_check=Mock()):
                    broker['process_check'](store, 2, 7, executor)
                executor.check_recovery.assert_not_called()
                self.assertFalse(broker['releases_maintenance'](store.publish.call_args.args[0]))

    def test_previously_completed_backup_is_not_reclassified_without_ledger(self):
        store = Mock(); store.claim_exists.return_value = True
        complete = broker['result'](2, 'COMPLETED', 'DONE', True, 'nexus-backup-2', 'c' * 64)
        store.get_result.return_value = complete
        store.mutation_uncertain.return_value = True
        executor = Mock(lib=lib)
        self.assertEqual(broker['process_job'](store, 2, executor), complete)
        executor.backup.assert_not_called()
        store.initialize_mutation_ledger.assert_not_called()

    def test_empty_ledger_preclaim_restart_is_adopted_without_write_or_docker(self):
        disk, worker = self.ledger_fixture()
        before = dict(disk)
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk), patch.object(lib.subprocess, 'Popen') as docker:
            restarted = worker()
            restarted.write = Mock(side_effect=AssertionError('adoption must not rewrite'))
            restarted.initialize_mutation_ledger(2)
            restarted.write.assert_not_called()
            docker.assert_not_called()
        self.assertEqual(disk, before)

    def test_adoption_rejects_history_malformed_wrong_request_and_ineligible(self):
        variants = [b'{bad', json.dumps({'schema': 1, 'requestId': 3, 'events': []}).encode(),
                    json.dumps({'schema': 1, 'requestId': 2, 'events': [{'sequence': 1, 'kind': 'stop', 'state': 'completed'}]}).encode()]
        for raw in variants:
            disk, worker = self.ledger_fixture(); disk[str(worker().mutation_path(2))] = raw
            with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk), patch.object(lib.subprocess, 'Popen') as docker:
                with self.assertRaises(Exception): worker().initialize_mutation_ledger(2)
                docker.assert_not_called()
        for history in ('baseline', 'result', 'invalid-inbox'):
            disk, worker = self.ledger_fixture(); store = worker()
            if history == 'baseline': disk[str(store.base / 'state' / '2.baseline.json')] = b'old'
            if history == 'result': disk[str(store.base / 'results' / '2.json')] = b'old'
            if history == 'invalid-inbox': store.request = lambda _: {'schema': 1, 'requestId': 2, 'action': 'CHECK_RECOVERY'}
            with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk):
                with self.assertRaises(ValueError): store.initialize_mutation_ledger(2)

    def test_protected_ledger_requires_root_group_and_exact_mode(self):
        raw = b'{}'
        for group, mode, expected in [(0, 0o100600, True), (200, 0o100600, False), (0, 0o100640, False)]:
            info = SimpleNamespace(st_mode=mode, st_uid=0, st_gid=group, st_nlink=1, st_size=2)
            with patch.dict(broker['read_file'].__globals__, path_parents=Mock()), patch.object(lib.os, 'O_NOFOLLOW', 0, create=True), patch.object(lib.os, 'O_NONBLOCK', 0, create=True), patch.object(lib.os, 'open', return_value=77), patch.object(lib.os, 'fstat', return_value=info), patch.object(lib.os, 'read', return_value=raw), patch.object(lib.os, 'close'):
                if expected: self.assertEqual(broker['read_file'](Path('/synthetic'), 0, 0o600, group=0), raw)
                else:
                    with self.assertRaises(ValueError): broker['read_file'](Path('/synthetic'), 0, 0o600, group=0)

    def test_queue_continues_after_empty_ledger_adoption_without_docker(self):
        disk, worker = self.ledger_fixture(); store = worker()
        store.checks = lambda: []
        store.ids = lambda directory: [] if directory == 'state' else [2]
        store.claim = Mock()
        store.publish = Mock()
        store.save_baseline = Mock()
        executor = Mock(lib=lib)
        executor.prepare.side_effect = ValueError('PREFLIGHT_REJECTED')
        with self.ledger_io(disk), patch.object(lib.os.path, 'lexists', side_effect=lambda path: path in disk), patch.object(lib.subprocess, 'Popen') as docker:
            broker['run_queue'](store, lambda: executor)
            store.claim.assert_called_once_with(2)
            executor.prepare.assert_called_once_with(2)
            executor.backup.assert_not_called()
            docker.assert_not_called()
            final = store.publish.call_args.args[0]
            self.assertEqual(final['status'], 'FAILED')


if __name__ == '__main__':
    unittest.main(verbosity=2)
