#!/usr/bin/env python3
"""Synthetic files/operations only; never invokes Docker, a real DB or systemd."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('maintenance', str(ROOT / 'installer/deploy-system/maintenance.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Operations:
    def __init__(self, fail=None, health=None, generations=None):
        self.calls = []
        self.fail = fail
        self.health = iter(health or [True])
        self.idles = 0
        self.generations = iter(generations or ['unchanged', 'unchanged'])

    def idle(self):
        self.idles += 1
        self.calls.append('idle')
        if self.fail == 'race' and self.idles == 2:
            raise ValueError('new request after initial preflight')

    def system(self, *args, **kwargs):
        self.calls.append(args)
        return 0

    def backup(self, path):
        self.calls.append('backup')
        if self.fail == 'backup':
            raise ValueError('backup failed')

    def healthy(self):
        return next(self.health)

    def schema_generation(self):
        result = next(self.generations)
        if isinstance(result, Exception):
            raise result
        return result


class MaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def jar(self, mutate=None):
        path = self.root / 'candidate.jar'
        manifest = json.loads((ROOT / 'installer/deploy-system/maintenance-payload.json').read_text())
        if mutate:
            mutate(manifest)
        with zipfile.ZipFile(str(path), 'w') as jar:
            jar.writestr(m.MANIFEST, json.dumps(manifest))
            for source in m.ALLOW:
                jar.writestr(m.PREFIX + source, (ROOT / 'installer' / source).read_bytes())
        return path

    def test_payload_exact_allowlist_and_actual_sources(self):
        entries = m.payload(self.jar())
        self.assertEqual(len(entries), 12)
        self.assertFalse(any(str(p).startswith('/etc/deployops/') for p, _, _ in entries))

    def test_unfinished_or_unknown_agent_job_blocks_patch(self):
        ops = object.__new__(m.Operations)
        def sql(query):
            if query == 'SHOW TABLES': return 'deployment_execution_guard\nserver_agent_task'
            if query.startswith('SHOW COLUMNS'): return 'deploy_request_id\tbigint'
            if query.endswith('WHERE id=1'): return '1'
            if 'FROM server_agent_task' in query: return '1'
            return '0'
        ops.sql = sql
        with patch.object(m, 'queue_idle'), self.assertRaisesRegex(ValueError, '에이전트'):
            ops.idle()

    def test_payload_cannot_overwrite_operational_keys(self):
        with self.assertRaises(ValueError):
            m.payload(self.jar(lambda v: v['files'][0].update(target='/etc/deployops/nexus-backup.json')))

    def test_payload_missing_member_rejected(self):
        path = self.root / 'bad.jar'
        with zipfile.ZipFile(str(path), 'w') as jar:
            jar.writestr(m.MANIFEST, (ROOT / 'installer/deploy-system/maintenance-payload.json').read_bytes())
        with self.assertRaises(ValueError):
            m.payload(path)

    def files(self):
        current = self.root / 'current.jar'
        current.write_bytes(b'old-jar')
        candidate = self.root / 'new.jar'
        candidate.write_bytes(b'new-jar')
        helper = self.root / 'helper.py'
        helper.write_bytes(b'old-helper')
        return current, candidate, helper

    def replace(self, ops, fail_write=False, fail_save=False):
        current, candidate, helper = self.files()
        def writer(path, data, mode, uid=0, gid=0):
            if fail_write and path == helper and data == b'new-helper':
                raise OSError('helper write failed')
            path.write_bytes(data)
        with patch.object(m, 'atomic', side_effect=writer), patch.object(m.os, 'fchmod', create=True) as chmod:
            if fail_save:
                chmod.side_effect = OSError('preimage permission failed')
            try:
                m.replace_generation(current, candidate, [(helper, b'new-helper', 0o750)], self.root / 'backup', ops)
                return current, helper
            except Exception:
                self.assertEqual(current.read_bytes(), b'old-jar')
                self.assertEqual(helper.read_bytes(), b'old-helper')
                raise

    def test_success_updates_app_and_helper_together(self):
        ops = Operations()
        jar, helper = self.replace(ops)
        self.assertEqual(jar.read_bytes(), b'new-jar')
        self.assertEqual(helper.read_bytes(), b'new-helper')
        self.assertLess(ops.calls.index(('stop', m.SERVICE)), ops.calls.index('backup'))
        self.assertEqual(ops.idles, 2)

    def test_preimage_failure_never_stops_or_writes(self):
        ops = Operations()
        with self.assertRaises(OSError):
            self.replace(ops, fail_save=True)
        self.assertEqual(ops.calls, [])

    def test_backup_failure_restarts_unchanged_app(self):
        ops = Operations(fail='backup')
        with self.assertRaises(ValueError):
            self.replace(ops)
        self.assertIn(('stop', m.SERVICE), ops.calls)
        self.assertIn(('start', m.SERVICE), ops.calls)
        self.assertEqual(ops.calls[-1], ('start', m.TIMER))

    def test_post_stop_new_request_rejects_before_writes(self):
        ops = Operations(fail='race')
        with self.assertRaises(ValueError):
            self.replace(ops)
        self.assertIn(('start', m.SERVICE), ops.calls)

    def test_helper_write_failure_restores_old_generation(self):
        ops = Operations()
        with self.assertRaises(OSError):
            self.replace(ops, fail_write=True)
        self.assertIn(('daemon-reload',), ops.calls)

    def test_failed_new_health_restores_old_generation(self):
        with self.assertRaises(ValueError):
            self.replace(Operations(health=[False, True]))

    def test_changed_or_unknown_schema_never_restarts_old_jar(self):
        for after in ('changed', RuntimeError('database unavailable')):
            with self.subTest(after=type(after).__name__), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                current, candidate = base / 'current.jar', base / 'candidate.jar'
                current.write_bytes(b'old-jar')
                candidate.write_bytes(b'new-jar')
                ops = Operations(health=[False], generations=['before', after])
                with patch.object(m, 'atomic', side_effect=lambda path, data, *args: path.write_bytes(data)), \
                     patch.object(m.os, 'fchmod', create=True), \
                     self.assertRaisesRegex(ValueError, 'DB 변경'):
                    m.replace_generation(current, candidate, [], base / 'backup', ops)
                self.assertEqual(current.read_bytes(), b'new-jar')
                self.assertEqual(ops.calls.count(('start', m.SERVICE)), 1)
                self.assertNotIn(('start', m.TIMER), ops.calls)
                self.assertTrue((base / 'backup/preimage-0').is_file())

    def test_schema_generation_includes_failed_and_repeatable_migrations(self):
        ops = object.__new__(m.Operations)
        states = iter(['1\t1\t123\t1', '1\t1\t123\t1\n2\t\t456\t0'])
        queries = []
        def sql(query):
            queries.append(query)
            return next(states) if 'FROM flyway_schema_history' in query else 'same-structure'
        ops.sql = sql
        self.assertNotEqual(ops.schema_generation(), ops.schema_generation())
        self.assertIn('ORDER BY installed_rank', queries[0])
        self.assertNotIn('WHERE success', queries[0])

    def test_committed_ddl_without_new_history_changes_generation(self):
        ops = object.__new__(m.Operations)
        columns = iter(['old-columns', 'new-columns'])
        ops.sql = lambda query: next(columns) if 'information_schema.COLUMNS' in query else 'unchanged'
        self.assertNotEqual(ops.schema_generation(), ops.schema_generation())

    def test_rollback_health_failure_keeps_broker_timer_off(self):
        ops = Operations(health=[False, False])
        with self.assertRaises(ValueError):
            self.replace(ops)
        self.assertNotIn(('start', m.TIMER), ops.calls)

    def test_rollback_write_failure_keeps_broker_timer_off(self):
        current, candidate, helper = self.files()
        ops = Operations(health=[False])
        def writer(path, data, mode, uid=0, gid=0):
            if data == b'old-helper':
                raise OSError('rollback helper failure')
            path.write_bytes(data)
        with patch.object(m, 'atomic', side_effect=writer):
            with self.assertRaises(OSError):
                m.replace_generation(current, candidate, [(helper, b'new-helper', 0o750)], self.root / 'backup', ops)
        self.assertNotIn(('start', m.TIMER), ops.calls)

    def test_queue_and_inflight_ledger_block_patch(self):
        (self.root / 'inbox').mkdir()
        (self.root / 'results').mkdir()
        (self.root / 'state').mkdir()
        (self.root / 'inbox/1.json').write_text('{}')
        with self.assertRaises(ValueError):
            m.queue_idle(self.root)
        (self.root / 'results/1.json').write_text(json.dumps({'status': 'COMPLETED', 'originalStateRestored': True}))
        m.queue_idle(self.root)
        ledger = self.root / 'state/1.mutations.json'
        ledger.write_text(json.dumps({'events': [{'state': 'inflight'}]}))
        with self.assertRaises(ValueError):
            m.queue_idle(self.root)
        ledger.write_text(json.dumps({'events': [{'state': 'completed'}]}))
        m.queue_idle(self.root)

    def test_unknown_restore_recovery_does_not_unlock(self):
        (self.root / 'inbox').mkdir()
        (self.root / 'results').mkdir()
        (self.root / 'inbox/restore-1.json').write_text('{}')
        result = self.root / 'results/restore-1.json'
        result.write_text(json.dumps({'status': 'FAILED', 'mutationUncertain': True, 'originalProjectionPreserved': True}))
        with self.assertRaises(ValueError):
            m.queue_idle(self.root)
        result.write_text(json.dumps({'status': 'HEALTH_READY', 'mutationUncertain': False, 'originalProjectionPreserved': True}))
        m.queue_idle(self.root)

    def test_absent_broker_lock_created_0600_under_umask_022(self):
        self.root.chmod(0o700)
        previous = os.umask(0o022)
        try:
            lock = self.root / 'broker.lock'
            with m.secure_lock(lock):
                self.assertEqual(lock.stat().st_mode & 0o777, 0o600)
                with self.assertRaises(BlockingIOError):
                    m.secure_lock(lock)
            wrong = self.root / 'wrong.lock'
            wrong.write_bytes(b'')
            wrong.chmod(0o644)
            with self.assertRaises(ValueError):
                m.secure_lock(wrong)
        finally:
            os.umask(previous)

    def test_broker_lock_waits_for_short_poll_without_replacing_inode(self):
        self.root.chmod(0o700)
        lock = self.root / 'broker.lock'
        owner = m.secure_lock(lock)
        inode = lock.stat().st_ino
        release = threading.Timer(0.05, owner.close)
        release.start()
        try:
            with m.secure_lock(lock, wait_seconds=2):
                self.assertEqual(lock.stat().st_ino, inode)
                with self.assertRaises(BlockingIOError):
                    m.secure_lock(lock)
        finally:
            release.join()
            owner.close()

    def test_broker_timeout_preserves_owner_and_releases_patch_lock(self):
        self.root.chmod(0o700)
        broker = self.root / 'broker.lock'
        patch_lock = self.root / 'deploy-patch.lock'
        with m.secure_lock(broker):
            started = time.monotonic()
            with self.assertRaisesRegex(ValueError, '패치를 시작하지 않았습니다'):
                with m.secure_lock(patch_lock), m.secure_lock(broker, wait_seconds=0.05):
                    self.fail('busy broker must not enter mutation scope')
            self.assertLess(time.monotonic() - started, 2)
            with m.secure_lock(patch_lock):
                pass
            with self.assertRaises(BlockingIOError):
                m.secure_lock(broker)
        with m.secure_lock(broker):
            pass

    def test_idle_allows_approval_wait_and_blocks_execution(self):
        ops = object.__new__(m.Operations)
        queries = []
        def sql(query):
            queries.append(query)
            if query == 'SHOW TABLES':
                return '\n'.join(['deployment_execution_guard', 'nexus_backup_request', 'nexus_cleanup_request', 'deploy_request', 'nginx_change_request'])
            if query.startswith('SHOW COLUMNS'):
                return 'nexus_maintenance_request_id\tbigint'
            if query.endswith('WHERE id=1'):
                return '1'
            return '0'
        ops.sql = sql
        with patch.object(m, 'queue_idle'):
            ops.idle()
            self.assertFalse(any("'PENDING'" in q or "'APPROVAL_PENDING'" in q for q in queries))
            ops.sql = lambda q: '1' if q.startswith('SELECT COUNT(*) FROM `nexus_cleanup_request`') else sql(q)
            with self.assertRaises(ValueError):
                ops.idle()


if __name__ == '__main__':
    unittest.main()
