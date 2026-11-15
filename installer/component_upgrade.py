#!/usr/libexec/platform-python
"""Local, offline maintenance of an already installed component on Rocky Linux 8.10."""
import argparse
from contextlib import contextmanager
import datetime
import fcntl
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

from interactive_install import Refused, private_directory, trusted_path, write_private

HERE = Path(__file__).resolve().parent
STATE = Path('/var/lib/deploy-installer/upgrades')
LIVE_JAR = Path('/opt/deploy-system/app/deploy-ops.jar')
DB_STATE = Path('/root/deploy-installer/deploy/state.json')
PACKAGES = {
    'docker': ('docker-ce', 'docker-ce-cli', 'containerd.io', 'docker-buildx-plugin', 'docker-compose-plugin'),
    'nginx': ('nginx',),
    'mariadb': ('MariaDB-server', 'MariaDB-client', 'MariaDB-shared', 'MariaDB-common')}
IMAGES = {'gitlab': ('gitlab/gitlab-ce', 'worksystem-gitlab'),
          'nexus': ('sonatype/nexus3', 'worksystem-nexus'),
          'runner': ('gitlab/gitlab-runner', 'worksystem-gitlab-runner')}
HELPER = 'registry.gitlab.com/gitlab-org/gitlab-runner/gitlab-runner-helper'
HEX = re.compile(r'[a-f0-9]{64}')
EVR = re.compile(r'([0-9]+):([0-9]+\.[0-9]+\.[0-9]+)-([A-Za-z0-9.+_~]+)')


def require(condition, message):
    if not condition:
        raise Refused(message)


def run(args, timeout=180, pass_fds=()):
    if args[0]=='docker':
        args=['docker','--host','unix:///var/run/docker.sock']+args[1:]
    result = subprocess.run([str(x) for x in args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,pass_fds=pass_fds)
    if result.returncode:
        # Command arguments and daemon diagnostics can contain credentials.
        raise Refused('{} 실행 실패(종료값 {}). 해당 서비스의 로컬 로그를 확인하세요.'.format(args[0], result.returncode))
    return result.stdout.decode('utf-8', 'replace').strip()


@contextmanager
def database_client(binary,arguments):
    if not DB_STATE.exists():
        defaults=Path('/root/.my.cnf')
        if defaults.exists():
            trusted_path(defaults,private=True)
            yield [binary,'--defaults-file='+str(defaults),'--protocol=socket']+arguments,()
        else:
            yield [binary,'--protocol=socket']+arguments,()
        return
    saved=json.loads(trusted_path(DB_STATE,private=True).read_text())
    password=saved.get('values',{}).get('db_root_password')
    require(saved.get('role')=='deploy' and isinstance(password,str) and password and
            all(ord(c)>=32 for c in password),'보호된 Deploy 역할의 MariaDB 접속 설정을 확인하세요.')
    value=password.replace('\\','\\\\').replace('"','\\"')
    # Anonymous mode-0600 descriptor: the password never appears in argv, environment or a surviving file.
    with tempfile.TemporaryFile(dir='/run') as f:
        os.fchmod(f.fileno(),0o600)
        f.write(('[client]\nuser=root\npassword="'+value+'"\n').encode('utf-8'))
        f.flush()
        f.seek(0)
        yield [binary,'--defaults-file=/proc/self/fd/'+str(f.fileno()),'--protocol=socket']+arguments,(f.fileno(),)


def db_command(binary,arguments,timeout=180):
    with database_client(binary,arguments) as pair:
        args,fds=pair
        return run(args,timeout=timeout,pass_fds=fds)


def checked_path(value, folder=False):
    p = Path(value)
    require(p.is_absolute() and '..' not in p.parts and p != Path('/'), '절대 경로를 입력하세요.')
    p = trusted_path(p)
    require(p.is_dir() if folder else p.is_file() and p.stat().st_size > 0, '입력 파일 또는 디렉터리를 확인하세요: '+str(p))
    return p


def digest(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def version(value):
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value), '지원하는 제품 버전은 숫자 세 자리 버전입니다.')
    return tuple(int(n) for n in value.split('.'))


def patch_step(before, after):
    a, b = version(before), version(after)
    require(a[:2] == b[:2] and a <= b,
            '동일 major.minor 패치만 이 메뉴에서 지원합니다. 하향·계열 변경은 공급사 단계별 이관 절차가 필요합니다.')


def rpm_plan(component, folder, target):
    header=['rpm','-qp','--qf','%{NAME}|%{EPOCHNUM}:%{VERSION}-%{RELEASE}|%{ARCH}']
    loose=False
    if component=='nginx' and folder.is_file():
        checked_path(folder)
        summary=run(header+[folder])
        lines=['|'.join([summary,digest(folder),folder.name])]
        lock=folder
        folder=folder.parent
    else:
        checked_path(folder, True)
        lock=folder/'PACKAGES.lock'
        if lock.exists() or lock.is_symlink():
            lines=checked_path(lock).read_text().splitlines()
        else:
            # Vendor media has no Deploy lock. Bind every selected artifact's bytes into this operation.
            loose=True
            lines=[]
            for rpm_file in sorted(folder.glob('*.rpm')):
                checked_path(rpm_file)
                lines.append('|'.join([run(header+[rpm_file]),digest(rpm_file),rpm_file.name]))
    rows = {}
    local_files={}
    for line in lines:
        if not line.strip() or line.startswith('#'):
            continue
        cols = line.split('|')
        require(len(cols) == 5, 'PACKAGES.lock 행이 올바르지 않습니다.')
        name, evr, arch, sha, filename = cols
        require(name not in rows and re.fullmatch(r'[a-zA-Z0-9_.+~-]+\.rpm',filename)
                and HEX.fullmatch(sha) and arch in ('x86_64','noarch'), 'RPM 이름·아키텍처·해시 오류')
        rpm_path = checked_path(folder/filename)
        require(digest(rpm_path) == sha and run(header+[rpm_path])
                == '|'.join(cols[:3]), 'RPM 파일의 내용이나 버전이 lock과 다릅니다.')
        rows[name] = evr
        local_files[name]=str(rpm_path)
    require(all(n in rows for n in PACKAGES[component]), '필수 패키지가 누락되었습니다.')
    if loose:
        require(set(rows)==set(PACKAGES[component]),'개별 RPM 폴더에는 선택한 제품 필수 패키지만 넣으세요. 무관한 RPM 및 별도 의존성 자동 교체를 허용하지 않습니다.')
    installed = {}
    # Vendor RPM label comparison handles epoch, tilde and numeric release ordering.
    import rpm
    for name in PACKAGES[component]:
        old = run(['rpm','-q','--qf','%{EPOCHNUM}:%{VERSION}-%{RELEASE}',name])
        parts = EVR.fullmatch(rows[name])
        require(parts is not None, '지원하지 않는 RPM 버전입니다.')
        previous = re.fullmatch(r'([0-9]+):([^-]+)-(.+)',old)
        require(previous and rpm.labelCompare(parts.groups(),previous.groups()) >= 0, '하위 RPM 교체는 지원하지 않습니다.')
        if component=='docker':
            patch_step(previous.group(2),parts.group(2))
            if name in ('docker-ce','docker-ce-cli'):
                require(parts.group(2)==target,'Docker 서버와 CLI의 목표 버전은 같아야 합니다.')
        installed[name] = old
    primary = PACKAGES[component][0]
    wanted = EVR.fullmatch(rows[primary])
    require(wanted.group(2) == target, '입력 버전과 RPM 원본 버전이 다릅니다.')
    patch_step(installed[primary].split(':',1)[1].split('-',1)[0],target)
    if component == 'mariadb':
        require(all(rows[n] == rows[primary] for n in PACKAGES[component]), 'MariaDB 공급자 패키지 버전이 다릅니다.')
    media_hash=hashlib.sha256(('\n'.join(lines)+'\n').encode('utf-8')).hexdigest() if loose else digest(lock)
    return {'mediaHash':media_hash,'packages':{k:rows[k] for k in PACKAGES[component]},
            'rpmPaths':[local_files[k] for k in PACKAGES[component]],'installed':installed}


def image_tar(path, repository, target):
    checked_path(path)
    version(target)
    with tarfile.open(str(path),'r:*') as t:
        entries = t.getmembers()
        names={m.name for m in entries}
        require(len(names)==len(entries) and all(not Path(m.name).is_absolute() and '..' not in Path(m.name).parts
                and (m.isfile() or m.isdir()) for m in entries),'이미지 TAR 경로 또는 중복 항목 오류')
        manifest = [m for m in entries if m.name == 'manifest.json' and m.isfile() and m.size < 4*1024**2]
        require(len(manifest)==1,'docker save 형식의 manifest.json 파일이 필요합니다.')
        records = json.load(t.extractfile(manifest[0]))
        require(isinstance(records,list) and len(records)==1,'이미지 1개만 포함하는 docker save TAR가 필요합니다.')
        record=records[0]
        tag = {'gitlab/gitlab-ce':target+'-ce.0','gitlab/gitlab-runner':'v'+target,HELPER:'x86_64-v'+target}.get(repository,target)
        expected=repository+':'+tag
        require(record.get('RepoTags')==[expected], 'TAR의 저장소·태그가 선택값과 다릅니다: '+expected)
        cfg=[m for m in entries if m.name==record.get('Config') and m.isfile() and 0<m.size<8*1024**2]
        require(len(cfg)==1 and record.get('Layers'), '이미지 config 또는 layer 누락')
        regular={m.name for m in entries if m.isfile() and m.size>0}
        require(all(layer in regular for layer in record['Layers']),'이미지 layer가 TAR에 없습니다.')
        data=t.extractfile(cfg[0]).read()
        obj=json.loads(data.decode('utf8'))
        require(obj.get('os')=='linux' and obj.get('architecture')=='amd64','Linux amd64 이미지가 필요합니다.')
        return {'tag':expected,'imageId':'sha256:'+hashlib.sha256(data).hexdigest(),'sha':digest(path)}


def inspect(kind, name):
    return json.loads(run(['docker',kind,'inspect',name]))[0]


def image_version(component,name):
    if component=='gitlab':
        return run(['docker','exec',name,'cat','/opt/gitlab/embedded/service/gitlab-rails/VERSION']).split('-')[0]
    if component=='runner':
        output=run(['docker','exec',name,'gitlab-runner','--version'])
        match=re.search(r'(?m)^Version:\s*([0-9.]+)\s*$',output)
    else:
        output=run(['docker','exec',name,'curl','--max-time','5','-fsS','-D','-','-o','/dev/null',
                    'http://127.0.0.1:8081/service/rest/v1/status'])
        match=re.search(r'(?im)^Server:\s*Nexus/([0-9]+\.[0-9]+\.[0-9]+)(?:-\d+)?\b',output)
    require(match,'설치한 제품의 실제 버전을 확인하지 못했습니다.')
    return match.group(1)


def image_plan(component,args):
    archive=image_tar(args.media,IMAGES[component][0],args.target)
    helper=image_tar(args.helper_media,HELPER,args.target) if component=='runner' else None
    return {'image':archive,'helper':helper,'mediaHash':archive['sha']}


def jar_plan(path,target):
    checked_path(path)
    require(HEX.fullmatch(target) and digest(path)==target,'Deploy JAR의 목표값은 반입할 회사용 JAR의 전체 SHA-256입니다.')
    with zipfile.ZipFile(str(path)) as z:
        require('BOOT-INF/classes/application-install.yml' in z.namelist(),'회사 설정이 들어 있는 JAR가 필요합니다.')
    run(['/usr/libexec/platform-python','-I','/usr/local/deploy/bin/deployops-embedded-config.py','validate',path])
    return {'mediaHash':target}


def health(component, name, plan, target):
    if component in PACKAGES:
        for pkg, expected in plan['packages'].items():
            require(run(['rpm','-q','--qf','%{EPOCHNUM}:%{VERSION}-%{RELEASE}',pkg])==expected,'설치 RPM이 목표와 다릅니다: '+pkg)
        run(['systemctl','is-active',component])
        if component=='docker':
            require(run(['docker','version','--format','{{.Server.Version}}'])==target,'Docker 데몬의 실행 버전이 다릅니다.')
        if component=='nginx':
            run(['nginx','-t'])
            pid=run(['systemctl','show','-p','MainPID','--value','nginx'])
            require(pid.isdigit() and pid!='0' and digest(Path('/proc')/pid/'exe')==digest(Path('/usr/sbin/nginx')),
                    'nginx master 프로세스가 새 실행 파일로 기동되지 않았습니다.')
        if component=='mariadb':
            db_version=db_command('mariadb',['-NBe','SELECT VERSION()'])
            require(db_version.split('-')[0]==target,'MariaDB 실행 버전이 다릅니다.')
    elif component in IMAGES:
        node=inspect('container',name)
        role_contract(component,name,node)
        require(node['Image']==plan['image']['imageId'] and node['State']['Running'],'이미지 또는 컨테이너 실행 상태가 다릅니다.')
        require(image_version(component,name)==target,'실제 실행 버전이 목표와 다릅니다.')
        if component=='gitlab':
            command=['gitlab-ctl','status']
            run(['docker','exec',name,'test','!','-e','/opt/gitlab/service/sshd'])
            require(run(['docker','exec',name,'gitlab-psql','-d','gitlabhq_production','-Atc',
                         'SELECT enabled_git_access_protocol FROM application_settings LIMIT 1'])=='http','GitLab HTTP 전용 정책 불일치')
            require(not any((node['NetworkSettings'].get('Ports') or {}).get(port) for port in ('22/tcp','2222/tcp')), 'GitLab SSH 포트 노출')
            port=node['Config']['Env']
            config=next((e for e in port if e.startswith('GITLAB_OMNIBUS_CONFIG=')),'')
            url=re.search(r"""external_url\s+['"]http://[^/'":;\s]+:(\d+)""",config)
            require(url,'GitLab 관리 HTTP 포트를 확인할 수 없습니다.')
            run(['docker','exec',name,'curl','-fsS','--max-time','5','http://127.0.0.1:'+url.group(1)+'/-/readiness'])
        elif component=='nexus':
            command=['curl','-fsS','--max-time','5','http://127.0.0.1:8081/service/rest/v1/status']
        else:
            command=['gitlab-runner','verify']
            helper_config=runner_config(node).read_text()
            require(re.search(r'(?m)^\s*helper_image\s*=\s*"'+re.escape(plan['helper']['tag'])+r'"\s*$',helper_config)
                    and inspect('image',plan['helper']['tag'])['Id']==plan['helper']['imageId'],
                    'Runner 설정 및 helper 이미지가 목표와 다릅니다.')
        run(['docker','exec',name]+command)
    else:
        require(digest(LIVE_JAR)==target,'실행용 Deploy JAR가 목표 SHA와 다릅니다.')
        run(['systemctl','is-active','deploy-system'])
        pid=run(['systemctl','show','-p','MainPID','--value','deploy-system'])
        require(pid.isdigit() and pid!='0' and jvm_uses_jar(Path('/proc')/pid/'fd',LIVE_JAR),
                '실행 중인 Deploy JVM이 목표 JAR 파일을 사용하지 않습니다.')
        with zipfile.ZipFile(str(LIVE_JAR)) as jar:
            cfg=json.loads(jar.read('BOOT-INF/classes/application-install.yml').decode('utf-8-sig'))
        port=cfg.get('server.port') or cfg.get('server',{}).get('port',9090)
        run(['/usr/libexec/platform-python','-I','/usr/local/deploy/bin/deployops-tls.py','--mode','health-check',
             '--jar',LIVE_JAR,'--port',str(port)])


def jvm_uses_jar(fds,jar):
    for fd in fds.iterdir():
        try:
            if fd.samefile(jar):
                return True
        except FileNotFoundError:
            continue
    return False


def native_backup(component, path, source, source_id, original_name=None, seal_new=False):
    """Validate original role backup formats, with the same source node/image."""
    checked_path(path,True)
    if component=='nexus':
        file=HERE/'nexus-backup/nexus-local-backup-restore.py'
        spec=importlib.util.spec_from_file_location('nexus_upgrade_backup',str(file))
        module=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _, meta=module.verify_artifact(str(path))
        result=json.loads((path/'backup.result.json').read_text())
        require(meta.get('imageId')==source['Image'] and result.get('containerId')==source_id,
                '백업이 현재 Nexus 원본 컨테이너와 다릅니다.')
        return digest(path/'manifest.json')
    if component=='gitlab':
        meta=json.loads(checked_path(path/'metadata.json').read_text())
        require(meta.get('format')=='deployops-gitlab' and meta.get('schema')==2 and meta.get('image_id')==source['Image']
                and meta.get('container')==(original_name or source['Name'].lstrip('/')),'GitLab 백업 원본이 다릅니다.')
        name=meta.get('backup_tar','')
        require(re.fullmatch(r'[A-Za-z0-9._-]+_gitlab_backup\.tar',name),'GitLab 백업 이름이 올바르지 않습니다.')
        files=[name,'container-inspect.json','image-inspect.json','omnibus-config.rb','version-manifest.txt','image.tar','metadata.json']
        checked_path(path/'config/gitlab.rb')
        require(bool(json.loads(checked_path(path/'config/gitlab-secrets.json').read_text())),'GitLab secret 백업이 없습니다.')
        for entry in (path/'config').rglob('*'):
            require(not entry.is_symlink(),'GitLab 백업에 참조되지 않은 외부 설정 링크가 있습니다.')
            if entry.is_file():
                files.append(str(entry.relative_to(path)))
        require(json.loads(checked_path(path/'container-inspect.json').read_text())[0]['Id']==source_id,'GitLab 원본 ID 불일치')
        require(json.loads(checked_path(path/'image-inspect.json').read_text())[0]['Id']==source['Image'],'백업된 GitLab 이미지 ID 불일치')
        with tarfile.open(str(checked_path(path/name))) as t:
            require(any(m.isfile() and m.name.lstrip('./')=='backup_information.yml' for m in t),'GitLab 네이티브 백업 목록이 없습니다.')
    else:
        meta=json.loads(checked_path(path/'upgrade-backup.json').read_text())
        require(meta['schema']==1 and meta['component']==component and meta['machine']==machine() and
                meta['sourceId']==source_id and meta.get('contents'),'같은 노드·원본의 업그레이드 백업을 사용하세요.')
        files=list(meta['contents'])
        expected={'docker':['docker-state.json','docker-data.tar'], 'nginx':['nginx-config.tar'],
                  'mariadb':['all-databases.sql','mariadb-config.tar'], 'runner':['runner-config.tar'],
                  'deploy':['SHA256SUMS','db.sql.gz','config.tar.gz','runtime-state.tar.gz','runtime-state.json']}[component]
        require(set(files)==set(expected),'구성요소 필수 백업 파일 목록 오류')
        for f in files:
            require(digest(checked_path(path/f))==meta['contents'][f],'업그레이드 백업 내용이 변경됐습니다.')
        files.append('upgrade-backup.json')
    h=hashlib.sha256()
    for item in sorted(files):
        f=trusted_path(path/item) if component=='gitlab' and item.startswith('config/') else checked_path(path/item)
        require(f.is_file(),'백업 구성 파일 오류')
        h.update((item+' '+digest(f)+'\n').encode())
    fingerprint=h.hexdigest()
    if component=='gitlab':
        seal=path/'upgrade-backup.json'
        expected={'schema':1,'sourceId':source_id,'machine':machine(),'fingerprint':fingerprint}
        if seal_new:
            require(not seal.exists(),'기존 GitLab 백업 영수증이 이미 있습니다.')
            write_private(seal,json.dumps(expected).encode())
        else:
            require(json.loads(checked_path(seal).read_text())==expected,'최초 GitLab 백업 시점의 영수증과 다릅니다.')
    return fingerprint


def machine():
    return Path('/etc/machine-id').read_text().strip()+'/'+socket.gethostname()


def source_state(component,name):
    if component in IMAGES:
        node=inspect('container',name)
        role_contract(component,name,node)
        require(node['Config']['Image'].split(':')[0]==IMAGES[component][0] or
                any(tag.split(':')[0]==IMAGES[component][0] for tag in (inspect('image',node['Image']).get('RepoTags') or [])),
                '선택한 컨테이너에 해당 제품의 이미지가 연결되어 있지 않습니다.')
        return node,node['Id'],image_version(component,name)
    if component in PACKAGES:
        pkg=PACKAGES[component][0]
        evr=run(['rpm','-q','--qf','%{EPOCHNUM}:%{VERSION}-%{RELEASE}',pkg])
        return {},evr,evr.split(':',1)[1].split('-',1)[0]
    sha=digest(LIVE_JAR)
    return {},sha,sha


def role_contract(component,name,node):
    role='deploy' if component=='nexus' else 'gitlab'
    answers=Path('/root/deploy-installer')/role/'answers.env'
    settings={}
    if answers.exists():
        for line in trusted_path(answers,private=True).read_text().splitlines():
            key,sep,value=line.partition('=')
            if sep and key in ('GITLAB_CONTAINER','GITLAB_DATA','NEXUS_CONTAINER','NEXUS_VOLUME','NEXUS_SECRETS_KEY_FILE',
                               'RUNNER_CONTAINER','RUNNER_CONFIG'):
                settings[key]=value.strip('"').strip("'")
    key={'gitlab':'GITLAB','nexus':'NEXUS','runner':'RUNNER'}[component]+'_CONTAINER'
    require(name==settings.get(key,IMAGES[component][1]),'역할 설치 설정의 컨테이너 이름과 다릅니다.')
    mounts={m['Destination']:m for m in node['Mounts']}
    if component=='gitlab':
        root=settings.get('GITLAB_DATA','/srv/gitlab')
        required={'/etc/gitlab':root+'/config','/var/log/gitlab':root+'/logs','/var/opt/gitlab':root+'/data'}
    elif component=='runner':
        required={'/etc/gitlab-runner':settings.get('RUNNER_CONFIG','/srv/gitlab-runner/config'),
                  '/var/run/docker.sock':'/var/run/docker.sock'}
    else:
        required={'/run/nexus-secrets/secrets.json':settings.get('NEXUS_SECRETS_KEY_FILE','/etc/deployops/nexus-secrets/secrets.json')}
        volume=mounts.get('/nexus-data',{})
        require(volume.get('Type')=='volume' and volume.get('Name')==settings.get('NEXUS_VOLUME','nexus-data')
                and volume.get('RW') is True,'Nexus 데이터 볼륨이 원래 설치 정의와 다릅니다.')
    for dest,path in required.items():
        row=mounts.get(dest,{})
        require(row.get('Type')=='bind' and row.get('Source')==path and
                row.get('RW')==(dest!='/run/nexus-secrets/secrets.json'),'제품의 설정·데이터 마운트 불일치: '+dest)


def runner_config(node):
    mounts=[m for m in node['Mounts'] if m['Destination']=='/etc/gitlab-runner' and m['Type']=='bind']
    require(len(mounts)==1,'설치기의 기존 Runner 설정 bind mount가 필요합니다.')
    return checked_path(Path(mounts[0]['Source'])/'config.toml')


def cold_docker_backup(path,root):
    sources=[checked_path(root,True)]
    if Path('/var/lib/containerd').exists() and Path('/var/lib/containerd')!=root:
        sources.append(checked_path(Path('/var/lib/containerd'),True))
    require(all(path!=p and p not in path.parents for p in sources),'백업 위치는 Docker/containerd 데이터 밖이어야 합니다.')
    needed=sum(int(run(['du','-sx','--block-size=1',p]).split()[0]) for p in sources)
    require(shutil.disk_usage(str(path)).free>needed+1024**3,'Docker 콜드 백업 공간이 부족합니다.')
    check_drain('docker')
    active=[u for u in ('docker.socket','docker.service','containerd.service')
            if subprocess.call(['systemctl','is-active','--quiet',u])==0]
    require('docker.service' in active,'실행 중인 로컬 Docker 서비스를 확인하세요.')
    try:
        run(['systemctl','stop']+active,timeout=300)
        # GNU tar preserves sparse files, SELinux labels and extended attributes.
        run(['tar','--create','--file',path/'docker-data.tar','--numeric-owner','--acls','--xattrs',
             '--selinux','--sparse','--one-file-system','-C','/']+[str(p).lstrip('/') for p in sources],timeout=14400)
    finally:
        run(['systemctl','start']+list(reversed(active)),timeout=360)
        run(['docker','info','--format','{{.DockerRootDir}}'])


def create_backup(component,name,path):
    source,source_id,_=source_state(component,name)
    if component=='gitlab':
        require(not path.exists(),'새 GitLab 백업 디렉터리가 필요합니다.')
        checked_path(path.parent,True)
        run(['bash',HERE/'gitlab/backup-gitlab.sh','--container',name,'--output',path],timeout=7200)
        native_backup(component,path,source,source_id,name,seal_new=True)
        return
    if component=='nexus':
        native_backup(component,path,source,source_id)
        print('기존 Nexus 운영 화면의 완료된 로컬 백업을 검증했습니다.')
        return
    if component=='deploy':
        # Use the standard coherent Deploy backup, including its private state.
        checked_path(path,True)
        required=['db.sql.gz','config.tar.gz','runtime-state.tar.gz','runtime-state.json']
        lines=checked_path(path/'SHA256SUMS').read_text().splitlines()
        expected={}
        for line in lines:
            parts=line.split()
            require(len(parts)==2 and parts[1] in required and HEX.fullmatch(parts[0]),'Deploy SHA256SUMS 형식 오류')
            expected[parts[1]]=parts[0]
        require(set(expected)==set(required),'Deploy 백업 파일 목록 오류')
        for f in required:
            require(digest(checked_path(path/f))==expected[f],'Deploy 네이티브 백업이 손상되었습니다.')
        with tarfile.open(str(path/'config.tar.gz')) as t:
            f=t.extractfile('app/deploy-ops.jar')
            require(f is not None, 'Deploy 기존 JAR 백업이 없습니다.')
            h=hashlib.sha256()
            for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
            require(h.hexdigest()==source_id,'백업의 Deploy JAR가 현재 설치와 다릅니다.')
        files=required+['SHA256SUMS']
    else:
        require(not path.exists(),'새 백업 디렉터리를 사용하세요.')
        checked_path(path.parent,True)
        private_directory(path)
        if component=='mariadb':
            mariadb_standalone()
            files=['all-databases.sql','mariadb-config.tar']
            dump_args=['--all-databases','--routines','--events','--triggers','--single-transaction','--quick','--hex-blob']
            with (path/files[0]).open('xb') as handle,database_client('mariadb-dump',dump_args) as pair:
                cmd,fds=pair
                p=subprocess.run(cmd,stdout=handle,stderr=subprocess.DEVNULL,timeout=7200,pass_fds=fds)
            require(p.returncode==0 and (path/files[0]).stat().st_size>128,'모든 DB의 백업이 완료되지 않았습니다.')
            with tarfile.open(str(path/files[1]),'w') as t:
                for item in [Path('/etc/my.cnf'),Path('/etc/my.cnf.d')]:
                    if item.exists():
                        t.add(str(checked_path(item,item.is_dir())),arcname=str(item).lstrip('/'))
        elif component=='docker':
            files=['docker-state.json','docker-data.tar']
            ids=run(['docker','ps','-aq']).splitlines()
            config=Path('/etc/docker/daemon.json')
            contents={'config':json.loads(checked_path(config).read_text()) if config.exists() else {},
                      'containers':json.loads(run(['docker','inspect']+ids)) if ids else [],'volumes':run(['docker','volume','ls','-q']).splitlines(),
                      'root':run(['docker','info','--format','{{.DockerRootDir}}'])}
            write_private(path/files[0],json.dumps(contents).encode())
            cold_docker_backup(path,Path(contents['root']))
        else:
            files=['nginx-config.tar' if component=='nginx' else 'runner-config.tar']
            folder=Path('/etc/nginx') if component=='nginx' else runner_config(source).parent
            with tarfile.open(str(path/files[0]),'w') as t:
                t.add(str(checked_path(folder,True)),arcname=folder.name)
    record={'schema':1,'component':component,'machine':machine(),'sourceId':source_id,
            'contents':{f:digest(checked_path(path/f)) for f in files}}
    receipt=path/'upgrade-backup.json'
    require(not receipt.exists(),'해당 백업에는 이미 업그레이드 영수증이 있습니다.')
    write_private(receipt,json.dumps(record).encode())


def container_payload(node,old_config,image):
    cfg=node['Config']
    host=dict(node['HostConfig'])
    require(host.get('NetworkMode','default') in ('default','bridge') and not host.get('AutoRemove')
            and not host.get('VolumesFrom') and not host.get('Links'),'기본 브리지의 단독 설치 컨테이너만 자동 교체합니다.')
    networks=node['NetworkSettings'].get('Networks') or {}
    require(set(networks)=={'bridge'} and not networks['bridge'].get('IPAMConfig') and
            not networks['bridge'].get('Links'),'사용자 정의 네트워크나 고정 IP는 별도 이관이 필요합니다.')
    require(not host.get('Privileged') and not host.get('DeviceRequests') and
            all(host.get(k) in ('',None,'private','shareable') for k in ('PidMode','IpcMode')) and
            all(str(binding.get('HostPort','')).isdigit() and int(binding['HostPort'])>0
                for bindings in (host.get('PortBindings') or {}).values() for binding in (bindings or [])),
            '특권·공유 namespace·동적 포트 컨테이너는 별도 이관이 필요합니다.')
    mounted={m['Destination'] for m in node['Mounts']}
    represented={s.split(':')[1] for s in (host.get('Binds') or []) if ':' in s}
    represented.update(m.get('Target') for m in (host.get('Mounts') or []))
    require(mounted.issubset(represented),'명시적인 bind 또는 named volume이 없는 마운트가 있습니다.')
    payload={'Image':image,'HostConfig':host}
    for key in ('Cmd','Entrypoint','WorkingDir','User','Healthcheck','StopSignal','Shell','ExposedPorts','Volumes','Labels',
                'Domainname','Tty','OpenStdin','StdinOnce','AttachStdin','AttachStdout','AttachStderr'):
        if cfg.get(key)!=old_config.get(key) and cfg.get(key) is not None:
            payload[key]=cfg[key]
    if cfg.get('Hostname') and cfg['Hostname']!=node['Id'][:12]:
        payload['Hostname']=cfg['Hostname']
    defaults=set(old_config.get('Env') or [])
    payload['Env']=[e for e in cfg.get('Env',[]) if e not in defaults]
    return payload


class DockerUnix(http.client.HTTPConnection):
    def connect(self):
        self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        self.sock.settimeout(60)
        self.sock.connect('/var/run/docker.sock')


def create_container(name,payload):
    api=run(['docker','version','--format','{{.Server.APIVersion}}'])
    require(re.fullmatch(r'[0-9]+\.[0-9]+',api),'Docker Engine API 버전 오류')
    conn=DockerUnix('localhost')
    try:
        conn.request('POST','/v'+api+'/containers/create?name='+name,body=json.dumps(payload),
                     headers={'Content-Type':'application/json'})
        response=conn.getresponse()
        body=response.read()
        require(response.status==201,'새 로컬 컨테이너 구성 실패: HTTP '+str(response.status))
        return json.loads(body.decode())['Id']
    finally:
        conn.close()


def save_state(state,path):
    state['component']=state['operation']['component']
    state['target']=state['operation']['target']
    write_private(path,(json.dumps(state,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
    fd=os.open(str(path.parent),os.O_RDONLY|os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def verification_plan(component,plan):
    if component in PACKAGES:
        return {'packages':plan['packages']}
    if component in IMAGES:
        return {'image':plan['image'],'helper':plan.get('helper')}
    return {}


def verify_baseline_skip(record):
    """Read-only action under the installer's shared maintenance lock."""
    op=record.get('operation',{})
    component=op.get('component')
    require(record.get('phase')=='done' and component in set(PACKAGES)|set(IMAGES)|{'deploy'}
            and op.get('machine')==machine(),'이 서버의 완료된 업그레이드 기록이 필요합니다.')
    target=op.get('target')
    proof=record.get('verification')
    require(isinstance(proof,dict) and isinstance(target,str),'실물 확인 정보가 없습니다. 동일 미디어로 업그레이드 resume을 실행하세요.')
    if component in IMAGES:
        node=inspect('container',op['name'])
        require(node['Id']==record.get('targetContainerId') and node['Image']==record.get('targetImageId'),
                '완료 기록과 현재 컨테이너의 고유 ID가 다릅니다.')
    health(component,op.get('name'),proof,target)
    return True


def record_image_generation(component,state,path):
    if component not in IMAGES: return
    op=state['operation']
    node=inspect('container',op['name'])
    state.update(targetContainerId=node['Id'],targetImageId=node['Image'])
    original=state['sourceId']
    require(HEX.fullmatch(original) and HEX.fullmatch(node['Id']),'원본/대상 컨테이너 고유 ID 오류')
    if node['Id']==original: return
    retained=op['name']+'-before-'+original[:12]
    require(named_id(retained)==original and (node['Config'].get('Labels') or {}).get('com.portfolio.upgrade-source')==original,
            '보관 컨테이너 및 교체 대상의 승인 관계가 다릅니다.')
    state['retainedName']=retained
    directory=private_directory(path.parent/'provenance'/op['name'])
    edge=directory/(node['Id']+'.json')
    if edge.exists():
        original_edge=json.loads(trusted_path(edge,private=True).read_text())
        require(all(original_edge.get(k)==state.get(k) for k in ('sourceId','targetContainerId','targetImageId','retainedName','operation')),
                '이미 저장된 컨테이너 승계 증거와 다릅니다.')
        return
    save_state(dict(state),edge)


def check_drain(component):
    if component=='docker':
        require(not run(['docker','ps','-q']),'Docker 업그레이드 전 이 노드의 모든 컨테이너를 정상 중지하세요.')
        ids=run(['docker','ps','-aq']).splitlines()
        if ids:
            nodes=json.loads(run(['docker','inspect']+ids))
            require(all(n['HostConfig']['RestartPolicy']['Name']!='always' for n in nodes),
                    'Docker 재기동 시 서비스가 먼저 시작되지 않도록 always 재시작 정책을 임시 해제하고 원래 값을 기록하세요.')
    if component=='nginx':
        run(['nginx','-t'])
    if component=='mariadb':
        mariadb_standalone()


def mariadb_standalone():
    require(not db_command('mariadb',['-NBe','SHOW ALL SLAVES STATUS']),'복제 MariaDB는 DBA 전환 절차가 필요합니다.')
    require(not db_command('mariadb',['-NBe',"SHOW VARIABLES LIKE 'wsrep_on'"]).upper().endswith('ON'),'Galera 클러스터 자동 업그레이드는 지원하지 않습니다.')
    require(not db_command('mariadb',['-NBe',"SELECT PLUGIN_NAME FROM INFORMATION_SCHEMA.PLUGINS WHERE PLUGIN_STATUS='ACTIVE' AND PLUGIN_TYPE='ENCRYPTION'"]),
            '외부 데이터 암호화 키 관리 DB는 키 복원 검증 후 DBA 전환 절차를 사용하세요.')


def update_helper(config,tag,source_tag):
    original=config.read_text()
    pattern=re.compile(r'(?m)^(\s*helper_image\s*=\s*")([^"\r\n]+)("\s*)$')
    found=list(pattern.finditer(original))
    require(len(found)==1 and found[0].group(2) in (source_tag,tag),'Runner helper_image가 이전 버전의 단일 고정 참조가 아닙니다.')
    if found[0].group(2)==tag: return
    updated=pattern.sub(lambda m:m.group(1)+tag+m.group(3),original)
    # gitlab-runner may rewrite config.toml. It must be stopped first.
    info=config.stat()
    with tempfile.NamedTemporaryFile('w',dir=str(config.parent),delete=False) as tmp:
        try:
            os.fchmod(tmp.fileno(),stat.S_IMODE(info.st_mode))
            os.fchown(tmp.fileno(),info.st_uid,info.st_gid)
            tmp.write(updated);tmp.flush();os.fsync(tmp.fileno())
        except BaseException:
            os.unlink(tmp.name)
            raise
    os.replace(tmp.name,str(config))


def named_id(name):
    names=run(['docker','ps','-aq','--no-trunc','--filter','name=^/'+re.escape(name)+'$']).splitlines()
    require(len(names)<=1,'컨테이너 이름 충돌')
    return names[0] if names else None


def upgrade_image(args,plan,state,path):
    name=args.container
    source=inspect('container',state['sourceId'])
    previous=name+'-before-'+state['sourceId'][:12]
    target_id=plan['image']['imageId']
    if 'payloadReady' not in state:
        require(source['Id']==named_id(name) and not named_id(previous),'기존 컨테이너가 변경되었습니다.')
        old_config=inspect('image',source['Image'])['Config']
        payload=container_payload(source,old_config,target_id)
        payload.setdefault('Labels',{}).update({'com.portfolio.upgrade-source':source['Id']})
        if args.component=='gitlab':
            require('GITLAB_DISABLE_OPENSSH=true' in source['Config']['Env'] and
                    not any((source['HostConfig'].get('PortBindings') or {}).get(p) for p in ('22/tcp','2222/tcp')),
                    'GitLab HTTP-only 설정을 먼저 적용하세요.')
        write_private(path.with_suffix('.payload'),json.dumps(payload).encode())
        state['payloadReady']=True
        save_state(state,path)
    for f,entry in [(args.media,plan['image']),(args.helper_media,plan.get('helper'))]:
        if entry:
            run(['docker','load','-i',f],timeout=1800)
            require(inspect('image',entry['tag'])['Id']==entry['imageId'],'로드한 이미지 ID가 다릅니다.')
    if named_id(name)==state['sourceId']:
        # Never let two stateful generations start on the same volumes after a host reboot.
        run(['docker','update','--restart=no',state['sourceId']])
        run(['docker','stop','-t','120',state['sourceId']])
        run(['docker','rename',state['sourceId'],previous])
    require(named_id(previous)==state['sourceId'],'원본 복구 컨테이너의 식별자가 다릅니다.')
    if plan.get('helper'):
        update_helper(runner_config(source),plan['helper']['tag'],HELPER+':x86_64-v'+state['sourceVersion'])
    cid=named_id(name)
    if not cid:
        cid=create_container(name,json.loads(checked_path(path.with_suffix('.payload')).read_text()))
    node=inspect('container',cid)
    require(node['Config']['Labels'].get('com.portfolio.upgrade-source')==state['sourceId'] and node['Image']==target_id,
            '다른 작업이 만든 컨테이너와 이름이 충돌합니다.')
    require({(m['Destination'],m.get('Name') or m['Source'],m['RW']) for m in node['Mounts']}==
            {(m['Destination'],m.get('Name') or m['Source'],m['RW']) for m in source['Mounts']},
            '새 컨테이너의 볼륨 바인딩이 원본과 다릅니다.')
    if not node['State']['Running']:
        run(['docker','start',cid])


def apply_rpm(component,plan):
    rpm_transaction(plan,commit=True)
    run(['systemctl','restart',component],timeout=360)
    if component=='mariadb':
        db_command('mariadb-upgrade',[],timeout=1800)


def rpm_transaction(plan,commit=False):
    import dnf
    with dnf.Base() as base:
        base.conf.install_weak_deps=False
        base.conf.obsoletes=False
        base.fill_sack(load_available_repos=False)
        for package in base.add_remote_rpms(plan['rpmPaths']):
            base.package_upgrade(package)
        base.resolve(allow_erasing=False)
        incoming={p.name:'{}:{}-{}'.format(p.epoch,p.version,p.release) for p in base.transaction.install_set}
        replaced={p.name for p in base.transaction.remove_set}
        # libdnf runs this same checked transaction. Unrelated changes and removals fail closed.
        expected={name:v for name,v in plan['packages'].items() if plan['installed'][name]!=v}
        require(incoming==expected and replaced==set(incoming),'DNF 변경 목록이 선택한 구성요소와 일치하지 않습니다.')
        if commit and incoming:
            base.do_transaction()


def execute(args):
    require(os.geteuid()==0,'root로 실행하세요.')
    release=dict(row.split('=',1) for row in Path('/etc/os-release').read_text().splitlines() if '=' in row)
    require(release.get('ID','').strip('"')=='rocky' and release.get('VERSION_ID','').strip('"')=='8.10',
            'Rocky Linux 8.10 전용입니다.')
    component=args.component
    if component in IMAGES:
        args.container=args.container or IMAGES[component][1]
        require(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}',args.container),'Docker 컨테이너 이름 오류')
    else:
        require(not args.container,'이미지 구성요소에만 컨테이너 이름을 입력하세요.')
    directory=private_directory(STATE)
    name=component+(('-'+args.container) if args.container else '')
    path=directory/(name+'.json')
    lock=directory/'maintenance.lock'
    if lock.exists():
        trusted_path(lock,private=True)
        require(lock.is_file(),'작업 잠금 경로 오류')
    with os.fdopen(os.open(str(lock),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600),'r+') as handle:
        fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
        for entry in directory.glob('*.json'):
            other=json.loads(trusted_path(entry,private=True).read_text())
            if other.get('phase') in ('applying','verify') and (entry!=path or args.action=='backup'):
                raise Refused('먼저 중단된 구성요소 작업을 재개하세요: '+str(entry))
        locked_action(args,component,path)


def locked_action(args,component,path):
    if args.action=='backup':
        require(args.backup and args.drained,'작업 제외 및 백업 경로가 필요합니다.')
        require(args.backup.is_absolute() and '..' not in args.backup.parts,'절대 백업 경로가 필요합니다.')
        create_backup(component,args.container,args.backup)
        print('백업 검사 완료: '+str(args.backup))
        return
    require(args.media and args.target,'반입할 파일 경로와 목표 버전이 필요합니다.')
    if component=='runner':
        require(args.helper_media,'해당 Runner 버전의 오프라인 helper TAR를 입력하세요.')
    if component in PACKAGES:
        plan=rpm_plan(component,args.media,args.target)
        rpm_transaction(plan)
    elif component in IMAGES:
        plan=image_plan(component,args)
    else:
        plan=jar_plan(args.media,args.target)
    token={'component':component,'name':args.container,'target':args.target,'media':str(args.media),
           'mediaHash':plan['mediaHash'],'helper':(plan.get('helper') or {}).get('sha'),
           'machine':machine()}
    prior=json.loads(trusted_path(path,private=True).read_text()) if path.exists() else {}
    if prior.get('phase') in ('applying','verify'):
        require(prior['operation']==token and args.action in ('resume','plan'),
                '미완료 작업이 있습니다. 같은 미디어로 resume을 선택하세요.')
        source_id=prior['sourceId']
        source=inspect('container',source_id) if component in IMAGES else {}
        current=prior['sourceVersion']
    else:
        source,source_id,current=source_state(component,args.container)
    if component in IMAGES:
        patch_step(current,args.target)
        if prior.get('phase') not in ('applying','verify'):
            container_payload(source,inspect('image',source['Image'])['Config'],plan['image']['imageId'])
            if component=='gitlab':
                require(run(['docker','exec',args.container,'gitlab-psql','-d','gitlabhq_production','-Atc',
                             'SELECT count(*) FROM batched_background_migrations WHERE status NOT IN (3,6)'])=='0',
                        'GitLab 백그라운드 마이그레이션이 끝날 때까지 대기하세요.')
    print('{} 현재 {} → 목표 {} (로컬 미디어 {})'.format(component,current,args.target,args.media))
    if args.action=='plan':
        print('미디어·업그레이드 경로 검사 통과. 사전 작업 제외, 백업 확인 후 apply를 실행하세요.')
        return
    require(args.drained and args.backup,'작업 제외 확인 및 백업 경로가 필요합니다.')
    if prior.get('phase') == 'done' and prior['operation']==token:
        if prior.get('verification') is not None and (component not in IMAGES or prior.get('targetContainerId')):
            verify_baseline_skip(prior)
        else:
            health(component,args.container,plan,args.target)
            prior['verification']=verification_plan(component,plan)
            record_image_generation(component,prior,path)
            save_state(prior,path)
        print('이미 완료된 작업입니다. 실제 실행 상태를 다시 확인했습니다.')
        return
    backup_hash=native_backup(component,args.backup,source,source_id,args.container)
    if prior.get('phase') in ('applying','verify'):
        require(prior['backupHash']==backup_hash and prior['backup']==str(args.backup),'최초 작업의 백업과 다릅니다.')
        state=prior
    else:
        require(args.action=='apply','재개할 작업이 없습니다. apply를 선택하세요.')
        check_drain(component)
        state={'operation':token,'sourceId':source_id,'sourceVersion':current,'backup':str(args.backup),
               'backupHash':backup_hash,'phase':'applying','verification':verification_plan(component,plan)}
        same=(component in PACKAGES and plan['packages']==plan['installed'] or
              component=='deploy' and source_id==args.target or
              component in IMAGES and source.get('Image')==plan['image']['imageId'])
        if same:
            health(component,args.container,plan,args.target)
            state['phase']='done'
            state['verifiedAt']=datetime.datetime.utcnow().isoformat()+'Z'
            record_image_generation(component,state,path)
            save_state(state,path)
            print('목표 버전이 이미 정상 실행 중입니다. 변경 없이 현재 상태를 기록했습니다.')
            return
        save_state(state,path)
    if state['phase']=='applying':
        check_drain(component)
        if component in PACKAGES:
            apply_rpm(component,plan)
        elif component in IMAGES:
            upgrade_image(args,plan,state,path)
        else:
            # An interrupted previous rename may have changed the on-disk JAR before helpers or JVM.
            # The installed patch procedure reconciles the entire helper/JAR generation and restarts.
            run(['/usr/local/deploy/bin/patch-deployops.sh',args.media],timeout=1800)
        state['phase']='verify'
        save_state(state,path)
    deadline=time.monotonic()+(600 if component=='gitlab' else 180 if component=='nexus' else 15)
    while True:
        try:
            health(component,args.container,plan,args.target)
            break
        except Refused:
            if time.monotonic()>=deadline: raise
            time.sleep(3)
    state['phase']='done'
    state['verification']=verification_plan(component,plan)
    state['verifiedAt']=datetime.datetime.utcnow().isoformat()+'Z'
    record_image_generation(component,state,path)
    save_state(state,path)
    print('완료: {} 실제 목표 버전·상태를 확인했습니다. 기록 {}'.format(component,path))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['backup','plan','apply','resume'])
    p.add_argument('--component',choices=tuple(PACKAGES)+tuple(IMAGES)+('deploy',),required=True)
    p.add_argument('--target',help='제품 버전(예: 1.30.4). Deploy는 회사 설정을 포함한 JAR의 SHA-256')
    p.add_argument('--media',type=Path)
    p.add_argument('--container')
    p.add_argument('--helper-media',type=Path)
    p.add_argument('--backup',type=Path)
    p.add_argument('--drained',action='store_true',help='이 노드에서 트래픽·신규 작업·쓰기 중단을 운영자가 확인함')
    os.umask(0o077)
    try:
        execute(p.parse_args())
    except (Refused,ValueError,OSError,KeyError,tarfile.TarError,zipfile.BadZipFile,subprocess.TimeoutExpired) as e:
        print('업그레이드 중단: '+str(e),file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':
    sys.exit(main())
