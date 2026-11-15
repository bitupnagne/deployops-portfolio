#!/usr/bin/env python3
"""Narrow root recovery for one completed Nexus backup blocked by one file query.
No SQL writes, no generic force mode, and no change to backup/agent identity state.
"""
import base64
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import zipfile


def require(ok, message):
    if not ok:
        raise ValueError(message)


def trusted(path, private=False):
    path = Path(path)
    require(path.is_absolute(), '절대 경로가 필요합니다.')
    for part in (path,) + tuple(path.parents):
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'root 소유이며 다른 사용자가 변경할 수 없는 경로가 필요합니다.')
    require(path.is_file(), '일반 파일이 필요합니다.')
    if private:
        require(stat.S_IMODE(path.stat().st_mode) == 0o600, 'root 전용 0600 파일이 필요합니다.')
    return path


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, str(trusted(path)))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def file_hash(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''): digest.update(block)
    return digest.hexdigest()


def strict(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, '중복 JSON 필드입니다.')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def query_backup_id(raw):
    value = strict(raw)
    require(isinstance(value, dict) and set(value) == {'argv', 'stdinBase64'}, '고정 결과 조회가 아닙니다.')
    require(value['argv'] == ['/usr/local/deploy/bin/deploy-infrastructure.sh', 'nexus-broker'], '고정 broker 명령이 아닙니다.')
    encoded = value['stdinBase64']
    require(isinstance(encoded, str) and len(encoded) <= 12000, '조회 입력이 잘못되었습니다.')
    request = strict(base64.b64decode(encoded, validate=True).decode('utf-8'))
    require(isinstance(request, dict) and set(request) == {'operation', 'path'} and request['operation'] in ('read', 'exists'), '읽기 전용 조회가 아닙니다.')
    match = re.fullmatch(r'results/([1-9][0-9]*)\.json', request['path'])
    require(match is not None, '단일 Nexus 백업 결과 조회만 복구할 수 있습니다.')
    return int(match[1])


def same_migrations(current, candidate):
    def inventory(path):
        with zipfile.ZipFile(str(path)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), '중복 JAR 항목입니다.')
            return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names
                    if name.startswith('BOOT-INF/classes/db/migration/') and not name.endswith('/')}
    old, new = inventory(current), inventory(candidate)
    require(bool(old) and old == new, 'DB migration이 달라 복구 전용 패치를 적용할 수 없습니다.')


def decrypt_payload(ciphertext, secret):
    # JCE is already installed with Deploy. Secrets enter only over stdin.
    java = trusted(Path('/usr/lib/jvm/temurin-21/bin/java'))
    source = """import java.io.*;import java.util.*;import java.security.*;import javax.crypto.*;import javax.crypto.spec.*;
class RecoveryDecode {public static void main(String[] a)throws Exception {var r=new BufferedReader(new InputStreamReader(System.in));byte[] k=MessageDigest.getInstance("SHA-256").digest(r.readLine().getBytes("UTF-8"));String s=r.readLine();byte[] b=Base64.getDecoder().decode(s.substring(s.lastIndexOf(':')+1));var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(k,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(b,0,12)));System.out.write(c.doFinal(Arrays.copyOfRange(b,12,b.length)));}}"""
    require(isinstance(secret, str) and '\n' not in secret and '\r' not in secret, '암호화 설정 형식을 확인하세요.')
    require(ciphertext.startswith('enc:v2:'), '현재 암호화 형식의 조회만 복구합니다.')
    with tempfile.TemporaryDirectory(prefix='deploy-query-decode-', dir='/root') as directory:
        path = Path(directory) / 'RecoveryDecode.java'
        path.write_text(source, encoding='utf-8')
        result = subprocess.run([str(java), str(path)], input=(secret+'\n'+ciphertext+'\n').encode('utf-8'),
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=True)
        require(len(result.stdout) <= 16384, '조회 입력이 너무 큽니다.')
        return result.stdout.decode('utf-8')


def recovery_operations(core):
    class RecoveryOperations(core.Operations):
        def __init__(self, config, helper, home, task_id):
            super().__init__(config, helper, home)
            self.config, self.task_id, self.proof = config, task_id, None
            self.schema_before = None

        def idle(self):
            # Exactly one current-generation UNKNOWN local DEPLOY query. No arbitrary
            # executable, transferred artifact, second task, or user workload allowed.
            rows = self.sql("SELECT t.server_id,t.generation,t.kind,t.state,t.is_sensitive,IFNULL(t.artifact_path,''),IFNULL(t.artifact_sha256,''),IFNULL(t.artifact_size,''),t.payload_encrypted,a.state,a.generation,a.machine_id FROM server_agent_task t JOIN server_agent a ON a.server_id=t.server_id WHERE t.id='"+self.task_id+"'").splitlines()
            require(len(rows)==1, '복구할 조회가 정확히 한 건이어야 합니다.')
            row = rows[0].split('\t')
            require(len(row)==12 and row[2:5]==['EXEC','UNKNOWN','0'] and row[5:8]==['','',''] and row[9]=='ACTIVE' and row[1]==row[10], '현재 승인된 읽기 조회 상태가 아닙니다.')
            require(row[11] == Path('/etc/machine-id').read_text().strip(), '현재 호스트의 조회만 복구합니다.')
            require(self.sql("SELECT COUNT(*) FROM server_agent_task WHERE state IN ('QUEUED','CLAIMED','RUNNING','UNKNOWN') AND id<>'"+self.task_id+"'")=='0', '다른 에이전트 작업이 남아 있습니다.')
            binding = self.sql("SELECT COUNT(*) FROM nexus_agent_broker_binding b JOIN server s ON s.id=b.server_id WHERE b.id=1 AND b.server_id="+row[0]+" AND s.server_type='DEPLOY' AND s.control_mode='AGENT' AND b.agent_binding_id=s.agent_binding_id")
            require(binding=='1', '현재 호스트 Nexus binding이 일치하지 않습니다.')
            ident = query_backup_id(decrypt_payload(row[8], self.config_helper.get(self.config, 'app.crypto.secret-key')))
            require(self.sql("SELECT COUNT(*) FROM nexus_backup_request WHERE id="+str(ident)+" AND status='RECONCILING' AND submitted=1 AND active_check_id IS NULL")=='1', '완료 결과 조회만 남은 백업이어야 합니다.')
            columns = [line.split('\t')[0] for line in self.sql('SHOW COLUMNS FROM deployment_execution_guard').splitlines() if line.split('\t')[0].endswith('_request_id')]
            require('nexus_maintenance_request_id' in columns, '유지보수 잠금을 확인할 수 없습니다.')
            conditions = ['`'+c+'` IS NULL' for c in columns if c!='nexus_maintenance_request_id']
            conditions.append('nexus_maintenance_request_id='+str(ident))
            require(self.sql('SELECT COUNT(*) FROM deployment_execution_guard WHERE id=1 AND '+' AND '.join(conditions))=='1', '다른 유지보수 또는 배포 잠금이 있습니다.')
            checks = {'deploy_request':"'TEST_REQUESTED','TEST_BUILDING','TEST_DEPLOYING','PROD_DEPLOYING'", 'nexus_backup_request':"'QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED'", 'nexus_restore_request':"'QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED'", 'nexus_promotion_request':"'QUEUED','RUNNING','RECONCILING','RECOVERY_REQUIRED'", 'nexus_backup_recovery_check':"'QUEUED','SUBMITTED'", 'nexus_restore_recovery_check':"'QUEUED','SUBMITTED'", 'nexus_cleanup_request':"'DELETING','RECONCILING'", 'nginx_change_request':"'APPLYING'", 'office_notification_outbox':"'SENDING'"}
            tables=set(self.sql('SHOW TABLES').splitlines())
            for table, states in checks.items():
                if table in tables:
                    exclude=' AND id<>'+str(ident) if table=='nexus_backup_request' else ''
                    require(self.sql('SELECT COUNT(*) FROM `'+table+'` WHERE status IN ('+states+')'+exclude)=='0', '다른 미완료 작업이 있습니다: '+table)
            core.queue_idle(Path('/var/lib/deployops/nexus-backup'))
            broker=module('recovery_broker','/usr/local/lib/deployops/nexus-backup/nexus-backup-broker.py')
            result=broker.parse_unique(broker.read_file(broker.BASE/'results'/(str(ident)+'.json'),0,0o640))
            broker.validate_result(result, ident)
            require(result.get('schema')==1 and result.get('requestId')==ident and result.get('status')=='COMPLETED' and result.get('stage')=='DONE' and result.get('originalStateRestored') is True and result.get('errorCode') is None and result.get('artifactId')=='nexus-backup-'+str(ident), '원본 복구와 백업 완료가 증명되지 않았습니다.')
            config=broker.validate_config(broker.parse_unique(broker.read_file(broker.CONFIG,0,0o600,group=0)))
            library=broker.load_library()
            require(broker.source_readiness(config,library)[0] is True, 'Nexus 원본 상태가 정상이어야 합니다.')
            artifact, metadata=library.verify_artifact(str(Path(config['backupRoot'])/result['artifactId']))
            require(library.digest(artifact/'manifest.json')==result.get('manifestSha256'), '백업 manifest가 결과와 다릅니다.')
            proof={'taskId':self.task_id,'serverId':int(row[0]),'generation':int(row[1]),'backupId':ident,'manifestSha256':result['manifestSha256']}
            require(self.proof is None or self.proof==proof, '복구 근거가 변경되었습니다.')
            self.proof=proof

        def backup(self, destination):
            require(self.system('is-active','--quiet',core.SERVICE,check=False)!=0, '앱 정지 후에만 보호 백업합니다.')
            self.idle()
            pair=destination/'db-pair';pair.mkdir(mode=0o700)
            self.schema_before=self.schema_generation()
            dump=shutil.which('mariadb-dump') or shutil.which('mysqldump')
            require(bool(dump),'DB dump 도구가 없습니다.')
            with (pair/'db.sql.gz').open('xb') as raw, (pair/'db.err').open('xb') as errors:
                proc=subprocess.Popen([dump,'--single-transaction','--routines','--events','--default-character-set=utf8mb4']+self.db_args, env=self.db_env,stdout=subprocess.PIPE,stderr=errors)
                with gzip.GzipFile(fileobj=raw,mode='wb',compresslevel=1) as out: shutil.copyfileobj(proc.stdout,out)
                require(proc.wait()==0 and raw.tell()>0,'보호 DB 백업이 실패했습니다.')
            subprocess.run(['tar','-czf',str(pair/'config.tar.gz'),'-C',str(self.home),'config','app/deploy-ops.jar'],check=True,stderr=subprocess.DEVNULL)
            with (pair/'runtime-state.json').open('xb') as out:
                subprocess.run([sys.executable,'-I','-B','/usr/local/deploy/bin/deployops-state.py','capture','--archive',str(pair/'runtime-state.tar.gz'),'--app-home',str(self.home)],stdout=out,stderr=subprocess.DEVNULL,check=True)
            hashes={p.name:file_hash(p) for p in pair.iterdir() if p.name!='db.err'}
            (pair/'SHA256SUMS').write_text(''.join(value+'  '+name+'\n' for name,value in sorted(hashes.items())))
            (pair/'RECOVERY.txt').write_text('Protected pre-patch DB+JAR/config+runtime-state. Preserve all files together. Request states and credentials were not changed.\n')
            require(self.schema_generation()==self.schema_before,'백업 중 DB schema가 변경되었습니다.')

        def healthy(self):
            healthy=super().healthy()
            return healthy and (self.schema_before is None or self.schema_generation()==self.schema_before)
    return RecoveryOperations


def apply_snapshot(argv, candidate):
    check_only = len(argv)==4 and argv[-1]=='--check-only'
    if check_only: argv=argv[:-1]
    require(os.geteuid()==0 and len(argv)==3 and argv[2]=='--acknowledge-read-only-recovery', '복구 조회 UUID, 회사설정 JAR, 명시 확인 인자가 필요합니다.')
    task=argv[0]
    require(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',task) is not None,'조회 UUID가 잘못되었습니다.')
    core=module('recovery_core',Path(__file__).with_name('maintenance.py'))
    helper=module('recovery_embedded','/usr/local/deploy/bin/deployops-embedded-config.py')
    home=Path('/opt/deploy-system');current=home/'app/deploy-ops.jar'
    config=helper.read(current);helper.same(config,helper.read(candidate));require(config==helper.read(candidate),'설정 전체가 기존 JAR와 같아야 합니다.');same_migrations(current,candidate)
    require(helper.get(config,'server.ssl.enabled',False)==helper.get(helper.read(candidate),'server.ssl.enabled',False),'TLS 방식을 변경할 수 없습니다.')
    entries=core.payload(candidate)
    ops=recovery_operations(core)(config,helper,home,task)
    state=Path('/var/lib/deployops/nexus-backup/state')
    with core.secure_lock(state/'deploy-patch.lock'),core.secure_lock(state/'broker.lock',wait_seconds=30):
        require(ops.system('is-active','--quiet',core.SERVICE,check=False)==0,'기존 앱이 정상 실행 중이어야 합니다.')
        require(ops.healthy(),'기존 앱/DB가 정상이어야 합니다.')
        ops.idle()
        if check_only:
            print('복구 전용 패치 조건 검사 통과. 서비스와 DB 상태를 변경하지 않았습니다.')
            return
        root=Path('/var/backups/deploy-system');root.mkdir(mode=0o700,exist_ok=True)
        require(root.stat().st_uid==0 and stat.S_IMODE(root.stat().st_mode)==0o700 and not root.is_symlink(),'보호 백업 디렉터리가 안전하지 않습니다.')
        audit=root/('readonly-recovery-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid())+'.json')
        evidence=dict(ops.proof,phase='VERIFIED',candidateSha256=file_hash(candidate),operatorUid=os.geteuid(),acknowledged=True)
        audit.write_text(json.dumps(evidence,sort_keys=True)+'\n');os.chmod(str(audit),0o600)
        backup=root/('readonly-recovery-patch-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
        try:
            core.replace_generation(current,candidate,entries,backup,ops)
            evidence.update(phase='PATCHED_PENDING_UI_RECONCILIATION',backup=str(backup))
        except BaseException:
            evidence.update(phase='PATCH_FAILED_REVIEW_PROTECTED_BACKUP',backup=str(backup));raise
        finally:
            audit.write_text(json.dumps(evidence,sort_keys=True)+'\n')
        print('복구 전용 패치 완료. 백업/에이전트 미확정 상태는 변경하지 않았습니다. UI에서 읽기 전용 조회를 재개하세요.')

def main(argv):
    require(os.geteuid()==0 and len(argv) in (3,4), 'root와 정확한 복구 인자가 필요합니다.')
    source=trusted(Path(argv[1]))
    require(source.resolve()!=Path('/opt/deploy-system/app/deploy-ops.jar').resolve(), '실행 중인 JAR와 다른 반입 경로가 필요합니다.')
    # Verify and deploy this one immutable root-private copy, never reread media.
    with tempfile.TemporaryDirectory(prefix='deploy-query-patch-',dir='/root') as directory:
        snapshot=Path(directory)/'candidate.jar'
        fd=os.open(str(snapshot),os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'wb') as output, source.open('rb') as incoming:
            shutil.copyfileobj(incoming,output);output.flush();os.fsync(output.fileno())
        return apply_snapshot(argv,snapshot)

if __name__=='__main__':
    os.umask(0o077)
    try: main(sys.argv[1:])
    except Exception as error:
        print('복구 전용 패치 중단: '+(str(error) if isinstance(error,ValueError) else type(error).__name__),file=sys.stderr)
        sys.exit(1)
