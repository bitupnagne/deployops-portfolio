"""Focused Linux tests. Only temporary fixtures; no Docker or real Nexus keys."""
import contextlib
import importlib.util
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


def module(name, relative):
    path = Path(__file__).resolve().parents[1] / 'nexus-backup' / relative
    spec = importlib.util.spec_from_file_location(name, str(path))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


promotion = module('nexus_promotion_test_library', 'nexus-promotion-worker.py')
broker = module('nexus_broker_test_library', 'nexus-backup-broker.py')


class Store:
    def __init__(self, base):
        self.base = base
        self.app_gid = 1000
        for name in ('inbox', 'state', 'results'):
            (base / name).mkdir()

    def write(self, path, value, exclusive=False):
        with path.open('x' if exclusive else 'w') as out:
            json.dump(value, out)

    def ids(self, directory):
        return []

    def read_inbox(self, path):
        return json.loads(path.read_text())


class PromotionTests(unittest.TestCase):
    def test_request_accepts_only_ids_not_client_commands_or_paths(self):
        value = dict(schema=1, requestId=1, restoreRequestId=2, action='PROMOTE')
        self.assertEqual(promotion.validate_job(value, 1), value)
        for changed in (dict(value, keyFile='/private'), dict(value, requestId=True),
                        dict(value, restoreRequestId=0), dict(value, action='SHELL')):
            with self.assertRaises(ValueError):
                promotion.validate_job(changed, 1)

    def test_key_copy_is_same_bytes_with_750_440_even_under_umask_077(self):
        self.assertEqual(os.geteuid(), 0)
        with tempfile.TemporaryDirectory(prefix='nexus-promotion-unit-') as directory:
            parent = Path(directory) / 'keys'
            previous = os.umask(0o077)
            try:
                key = promotion.persistent_key(parent, 1, b'UNIT_FIXTURE_NOT_A_REAL_KEY')
            finally:
                os.umask(previous)
            self.assertEqual(key.read_bytes(), b'UNIT_FIXTURE_NOT_A_REAL_KEY')
            for folder in (parent, key.parent):
                self.assertEqual((folder.stat().st_uid, folder.stat().st_gid,
                    stat.S_IMODE(folder.stat().st_mode)), (0, 200, 0o750))
            self.assertEqual((key.stat().st_uid, key.stat().st_gid,
                stat.S_IMODE(key.stat().st_mode)), (0, 200, 0o440))
            with self.assertRaises(ValueError):
                promotion.persistent_key(parent, 1, b'DO_NOT_OVERWRITE')
            self.assertEqual(key.read_bytes(), b'UNIT_FIXTURE_NOT_A_REAL_KEY')

    def test_key_copy_rejects_link_parent(self):
        with tempfile.TemporaryDirectory(prefix='nexus-promotion-unit-') as directory:
            target = Path(directory) / 'target'
            target.mkdir()
            link = Path(directory) / 'link'
            link.symlink_to(target)
            with self.assertRaises(ValueError):
                promotion.persistent_key(link, 1, b'UNIT_FIXTURE')
            self.assertEqual(list(target.iterdir()), [])

    def test_old_always_restart_is_disabled_before_rollback_rename(self):
        state = dict(Running=False, ExitCode=143, OOMKilled=False, Error='')
        policy, calls = dict(Name='always'), []
        def command(args):
            calls.append(args)
            if args[0] == 'update':
                policy['Name'] = 'no'
        library = SimpleNamespace(inspect=lambda _: dict(State=state,
            HostConfig=dict(RestartPolicy=policy)), call=command)
        promotion.retain_rollback(library, 'a' * 64, 'rollback-1')
        self.assertEqual(calls, [['update', '--restart=no', 'a' * 64],
            ['rename', 'a' * 64, 'rollback-1']])

    def test_killed_or_running_original_is_not_renamed(self):
        for running, code in ((True, 0), (False, 137)):
            calls = []
            library = SimpleNamespace(inspect=lambda _: dict(State=dict(Running=running,
                ExitCode=code, OOMKilled=False, Error='')), call=calls.append)
            with self.assertRaises(ValueError):
                promotion.retain_rollback(library, 'a' * 64, 'rollback')
            self.assertEqual(calls, [])

    def test_restart_update_failure_blocks_rename(self):
        calls = []
        library = SimpleNamespace(inspect=lambda _: dict(State=dict(Running=False,
            ExitCode=0, OOMKilled=False, Error=''), HostConfig=dict(RestartPolicy=dict(Name='always'))),
            call=calls.append)
        with self.assertRaises(ValueError):
            promotion.retain_rollback(library, 'a' * 64, 'rollback')
        self.assertEqual(calls, [['update', '--restart=no', 'a' * 64]])

    def test_crashed_claim_is_reported_without_replaying_when_source_unready(self):
        with tempfile.TemporaryDirectory(prefix='nexus-promotion-unit-') as directory:
            store = Store(Path(directory))
            job = dict(schema=1, requestId=1, restoreRequestId=2, action='PROMOTE')
            store.write(store.base / 'inbox/promotion-1.json', job)
            store.write(store.base / 'state/promotion-1.json', job)
            store.write(store.base / 'results/promotion-1.json', promotion.result(job, 'RUNNING'))
            api = SimpleNamespace(parse_unique=lambda raw: json.loads(raw.decode()),
                read_file=lambda path, *args, **kwargs: path.read_bytes())
            library = SimpleNamespace(inspect=lambda _: self.fail('Docker must never run for an existing claim'))
            self.assertTrue(promotion.run_queue(api, store, {}, library, ready=False))
            result = json.loads((store.base / 'results/promotion-1.json').read_text())
            self.assertEqual(result['status'], 'RECOVERY_REQUIRED')
            self.assertEqual(result['errorCode'], 'RESULT_UNCERTAIN')

    def test_no_promotion_does_not_block_backup_recovery(self):
        with tempfile.TemporaryDirectory(prefix='nexus-promotion-unit-') as directory:
            store = Store(Path(directory))
            store.ids = lambda _: self.fail('Unrelated backup queue must remain available')
            self.assertFalse(promotion.run_queue(SimpleNamespace(), store, {}, SimpleNamespace(), ready=False))

    def test_backup_generation_image_must_equal_current_and_clone_image(self):
        restored = dict(schema=1, requestId=2, backupRequestId=3, status='HEALTH_READY',
            originalProjectionPreserved=True, mutationUncertain=False, cloneName='nexus-restore-2',
            cloneVolume='nexus-restore-data-2', cloneNetwork='nexus-restore-net-2',
            cloneContainerId='a' * 64, cloneImageId='old-image', manifestSha256='b' * 64)
        api = SimpleNamespace(parse_unique=lambda _: restored, read_file=lambda *a, **kw: b'{}')
        store = SimpleNamespace(base=Path('/unit'), app_gid=1000,
            get_result=lambda _: dict(status='COMPLETED', manifestSha256='b' * 64, artifactId='backup-3'))
        library = SimpleNamespace(verify_artifact=lambda _: (Path('/unit'), dict(imageId='old-image')),
            digest=lambda _: 'b' * 64, inspect=lambda _: self.fail('Mismatched image must fail before clone/original access'))
        with self.assertRaises(ValueError):
            promotion.prepare(api, store, dict(externalWritersManaged=True, allStorageInDataVolume=True,
                backupRoot='/unit', imageId='current-image'), library, dict(restoreRequestId=2))

    def test_readiness_rejects_inaccessible_key_in_real_execution_context(self):
        def inaccessible(_):
            raise PermissionError('Fixture sandbox hides the key')
        config = dict(externalWritersManaged=True, allStorageInDataVolume=True,
            containerName='nexus', imageId='image', volume='volume', keyFile='/hidden', heap='1g')
        library = SimpleNamespace(inspect=lambda _: dict(Id='a' * 64, State=dict(Running=True)),
            source_contract=inaccessible)
        self.assertEqual(broker.source_readiness(config, library),
            (False, 'SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
