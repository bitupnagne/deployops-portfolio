import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('recovery_patch',ROOT/'deploy-system/recover-readonly-patch.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)

def payload(operation='exists',path='results/1.json',**extra):
    data={'operation':operation,'path':path};data.update(extra)
    return json.dumps({'argv':['/usr/local/deploy/bin/deploy-infrastructure.sh','nexus-broker'],'stdinBase64':base64.b64encode(json.dumps(data).encode()).decode()})

class RecoveryTest(unittest.TestCase):
    def test_readonly_exact_backup_query(self):
        self.assertEqual(1,r.query_backup_id(payload()))
        self.assertEqual(7,r.query_backup_id(payload('read','results/7.json')))
    def test_writes_paths_and_extra_payload_rejected(self):
        for raw in [payload('publish'),payload('publish-exact'),payload(path='inbox/1.json'),payload(path='results/ready.json'),payload(path='results/../1.json'),payload(path='results/restore-1.json'),payload(payload={}),payload().replace('nexus-broker','restart'),payload()+' {}']:
            with self.subTest(raw=raw),self.assertRaises((ValueError,TypeError)):r.query_backup_id(raw)
    def test_duplicates_rejected(self):
        with self.assertRaises(ValueError):r.strict('{"operation":"read","operation":"publish"}')
    def test_migration_exact_bytes_and_nonempty_required(self):
        with tempfile.TemporaryDirectory() as folder:
            a,b=Path(folder)/'a.jar',Path(folder)/'b.jar'
            def jar(path,content):
                with zipfile.ZipFile(path,'w') as z:
                    if content is not None:z.writestr('BOOT-INF/classes/db/migration/V1__test.sql',content)
            jar(a,'CREATE TABLE t(id INT);');jar(b,'CREATE TABLE t(id INT);');r.same_migrations(a,b)
            jar(b,'ALTER TABLE t ADD x INT;')
            with self.assertRaises(ValueError):r.same_migrations(a,b)
            jar(a,None);jar(b,None)
            with self.assertRaises(ValueError):r.same_migrations(a,b)
    def test_strict_gate_rejects_other_tasks_identity_drift_and_unfinished_backup(self):
        with tempfile.TemporaryDirectory() as folder:
            artifact=Path(folder);(artifact/'manifest.json').write_text('immutable')
            digest=hashlib.sha256(b'immutable').hexdigest()
            result={'schema':1,'requestId':1,'status':'COMPLETED','stage':'DONE','originalStateRestored':True,'errorCode':None,'artifactId':'nexus-backup-1','manifestSha256':digest,'completedAt':'2026-11-05T09:43:15+00:00'}
            config={'backupRoot':folder}
            lib=SimpleNamespace(verify_artifact=lambda _: (artifact,{}),digest=lambda _:digest)
            broker=SimpleNamespace(BASE=Path('/var/lib/deployops/nexus-backup'),CONFIG=Path('/etc/deployops/nexus-backup.json'),
                read_file=lambda p,*a,**kw:json.dumps(config if p.name=='nexus-backup.json' else result),parse_unique=json.loads,validate_config=lambda x:x,
                validate_result=lambda value,ident: r.require(value.get('completedAt') is not None,'completion required'),load_library=lambda:lib,source_readiness=lambda *_:(True,''))
            class Base:
                def __init__(self,*_):self.config_helper=SimpleNamespace(get=lambda *_:'fixture-key')
            core=SimpleNamespace(Operations=Base,queue_idle=lambda _:None)
            ops=r.recovery_operations(core)({},None,None,'00000000-0000-0000-0000-000000000001')
            row=['7','2','EXEC','UNKNOWN','0','','','','enc:v2:primary:fixture','ACTIVE','2','fixture-machine']
            overrides={}
            def sql(q):
                if q.startswith('SELECT t.server_id'):return '\t'.join(row)
                if q.startswith('SHOW COLUMNS'):return 'nexus_maintenance_request_id\tbigint\ndeploy_request_id\tbigint'
                if q=='SHOW TABLES':return '\n'.join(['deploy_request','nexus_backup_request','nexus_restore_request','nexus_promotion_request','nexus_backup_recovery_check','nexus_restore_recovery_check','nexus_cleanup_request','nginx_change_request','office_notification_outbox'])
                if q in overrides:return overrides[q]
                if 'nexus_agent_broker_binding' in q or 'deployment_execution_guard WHERE' in q or "status='RECONCILING'" in q:return '1'
                if q.startswith('SELECT COUNT(*)'):return '0'
                raise AssertionError(q)
            ops.sql=sql
            with patch.object(r,'module',return_value=broker),patch.object(r,'decrypt_payload',return_value=payload()),patch.object(Path,'read_text',return_value='fixture-machine'):
                ops.idle();self.assertEqual(1,ops.proof['backupId'])
                for index,value in [(2,'COPY'),(3,'RUNNING'),(4,'1'),(5,'/some/artifact'),(9,'REVOKED'),(10,'3'),(11,'other-machine')]:
                    old=row[index];row[index]=value
                    with self.subTest(index=index),self.assertRaises(ValueError):ops.idle()
                    row[index]=old
                other="SELECT COUNT(*) FROM server_agent_task WHERE state IN ('QUEUED','CLAIMED','RUNNING','UNKNOWN') AND id<>'"+ops.task_id+"'"
                overrides[other]='1'
                with self.assertRaises(ValueError):ops.idle()
                overrides.clear()
                for field,value in [('status','RUNNING'),('originalStateRestored',False),('manifestSha256','a'*64),('completedAt',None)]:
                    old=result[field];result[field]=value
                    with self.subTest(field=field),self.assertRaises(ValueError):ops.idle()
                    result[field]=old
                overrides["SELECT COUNT(*) FROM `deploy_request` WHERE status IN ('TEST_REQUESTED','TEST_BUILDING','TEST_DEPLOYING','PROD_DEPLOYING')"]='1'
                with self.assertRaises(ValueError):ops.idle()
    def test_media_change_after_snapshot_cannot_change_applied_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'input.jar';source.write_bytes(b'original')
            actual_tmp=tempfile.TemporaryDirectory
            def temp(**kwargs):return actual_tmp(prefix='snapshot-',dir=folder)
            observed=[]
            def apply(argv,snapshot):
                source.write_bytes(b'replaced-after-copy')
                observed.append(snapshot.read_bytes())
                self.assertNotEqual(source,snapshot)
                if r.os.name=='posix': self.assertEqual(snapshot.stat().st_mode & 0o777,0o600)
            with patch.object(r.os,'geteuid',return_value=0,create=True),patch.object(r,'trusted',side_effect=lambda p:p),patch.object(r.tempfile,'TemporaryDirectory',side_effect=temp),patch.object(r,'apply_snapshot',side_effect=apply):
                r.main(['00000000-0000-0000-0000-000000000001',str(source),'--acknowledge-read-only-recovery'])
            self.assertEqual(observed,[b'original'])
    def test_no_db_update_or_general_force_mode_in_recovery_module(self):
        source=(ROOT/'deploy-system/recover-readonly-patch.py').read_text(encoding='utf-8')
        self.assertNotIn('UPDATE ',source);self.assertNotIn('DELETE FROM ',source);self.assertNotIn('--force',source)

if __name__=='__main__':unittest.main()
