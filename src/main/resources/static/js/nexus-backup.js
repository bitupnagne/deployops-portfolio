(() => {
  'use strict';
  const context=document.getElementById('nexus-ops-context');
  if(!context||!context.isConnected||context.nexusBackupCleanup) return;
  const script=document.currentScript,content=document.getElementById('content');
  // 지연 로드된 이전 fragment 스크립트가 새 Nexus 화면을 초기화하지 않게 한다.
  if(script&&script.deployPageNavigation!==undefined&&
    (script.deployPageContent!==content||!content||content.deployPageNavigation!==script.deployPageNavigation||!content.contains(context)))return;
  const body=document.getElementById('nxbackup-body'),error=document.getElementById('nxbackup-error');
  const labels={PENDING:'승인 대기',REJECTED:'반려',QUEUED:'백업 대기',RUNNING:'백업 진행',COMPLETED:'백업 완료',FAILED:'백업 실패',RECOVERY_REQUIRED:'원본 복구 필요',RECONCILING:'결과 확인 필요'};
  const stages={PREFLIGHT:'사전 확인',STOPPING:'정지 백업 및 원본 복구 실행 중',COPYING:'데이터 복사',VERIFYING:'백업 검증',RESTORING_ORIGINAL:'원본 상태 복구',DONE:'완료'};
  const errors={PREFLIGHT_REJECTED:'유지보수 설정을 확인하세요.',BACKUP_FAILED:'백업에 실패했습니다. 관리자가 안전한 실행 결과를 확인해야 합니다.',ORIGINAL_RECOVERY_FAILED:'원본 복구가 필요합니다. 유지보수 차단이 유지됩니다.',RESULT_UNCERTAIN:'접수 또는 결과를 확정하지 못했습니다. 관리자가 실행기 상태를 확인해야 합니다. 재요청하지 마세요.',REJECTED:'반려된 요청입니다.'};
  const uid=Number(context.dataset.userId),role=context.dataset.role;
  let previous='',busy=false,loading=false,disposed=false,timer=null,mutationError=false;
  function isActive(){return !disposed&&context.isConnected&&document.getElementById('nexus-ops-context')===context;}
  function cleanup(){disposed=true;clearInterval(timer);window.removeEventListener('deploy:page-leave',cleanup);window.removeEventListener('pagehide',cleanup);delete context.nexusBackupCleanup;}
  context.nexusBackupCleanup=cleanup;
  window.addEventListener('deploy:page-leave',cleanup);
  window.addEventListener('pagehide',cleanup);
  function stopped(){return document.getElementById('nxbackup-external').checked;}
  function headers(){const token=document.querySelector('meta[name="_csrf"]'),header=document.querySelector('meta[name="_csrf_header"]');return {'Content-Type':'application/json',[header.content]:token.content};}
  async function call(url,options){const r=await fetch(url,options),b=await r.json();if(!r.ok||!b.success)throw new Error(b.message||'백업 요청을 처리하지 못했습니다.');return b;}
  let backupReady=false,backupReadiness='loading';
  const backupReadinessHints={loading:'백업 실행기 준비 상태를 확인하고 있습니다.',unready:'백업 실행기 미준비: 관리자가 유지보수 설정을 확인해 주세요.',unavailable:'백업 실행기 준비 상태를 확인하지 못했습니다. 다시 조회해 주세요.',ready:'백업 실행기 준비됨'};
  function applyBackupReadiness(button){button.disabled=!backupReady;button.title=backupReadinessHints[backupReadiness];}
  function setBackupReadiness(state){
    backupReadiness=state;backupReady=state==='ready';
    document.getElementById('nxbackup-ready').textContent=backupReadinessHints[state];
    const request=document.getElementById('nxbackup-request');if(request)applyBackupReadiness(request);
    for(const button of body.querySelectorAll('button'))if(button.dataset.backupApprove==='true')applyBackupReadiness(button);
  }
  async function loadBackupReadiness(){
    setBackupReadiness('loading');
    try{const response=await call('/api/v1/nexus/backup-requests/readiness');if(isActive()){
      setBackupReadiness(response.data&&response.data.ready===true?'ready':'unready');
      if(!backupReady){const hints={OPERATOR_STORAGE_AND_WRITER_CONFIRMATION_REQUIRED:'로컬 H2·파일 저장소와 외부 게시 작업 중지에 대한 관리자 설정이 필요합니다.',SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE:'실제 실행기에서 Nexus 저장소·키 파일·기동 상태를 확인하지 못했습니다. 키 파일이 /root 또는 /home 아래에 있으면 서비스 접근 제한을 확인하세요.',RUNNER_STATE_UNAVAILABLE:'실행기 상태를 읽지 못했습니다. 서버 목록에서 Nexus 실행 서버의 에이전트 연결과 결과 확인 대기 작업을 확인하세요. 대기 작업이 없으면 실행기 서비스를 점검하세요.'};const hint=hints[response.data?.reasonCode];if(hint)document.getElementById('nxbackup-ready').textContent='백업 실행기 미준비: '+hint;}
    }}
    catch(e){if(isActive())setBackupReadiness('unavailable');}
  }
  function displayTimestamp(value){return value?String(value).replace(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})?$/,(_,date,time,zone)=>date+' '+time+(zone||'')):'-';}
  const restoreBody=document.getElementById('nxrestore-body');
  const restoreError=document.getElementById('nxrestore-error');
  const restoreDetail=document.getElementById('nxrestore-detail');
  let restoreReady=false,restoreReadiness='loading',restorePrevious='',detailSequence=0;
  let restoreRows=[],selectedRestoreId=null,selectedRestoreSnapshot='',detailFields=null,detailForm=null,detailPending=0;
  let detailRetry=null;
  const detailRetryLimit=3;
  const restoreReadinessHints={loading:'격리복원 실행기 준비 상태를 확인하고 있습니다.',unready:'격리복원 실행기 미준비: 서버 목록에서 Nexus 실행 서버의 에이전트 상태를 확인하고 설치기의 격리복원 설정을 점검하세요.',unavailable:'격리복원 실행기 준비 상태를 확인하지 못했습니다. 다시 조회해 주세요.',ready:'격리복원 실행기 준비됨'};
  function applyRestoreRequestReadiness(button){
    button.disabled=!restoreReady;
    button.title=restoreReady?'완료 백업의 격리복원을 신청합니다.':restoreReadinessHints[restoreReadiness];
  }
  function setRestoreReadiness(state){
    restoreReadiness=state;restoreReady=state==='ready';
    document.getElementById('nxrestore-ready').textContent=restoreReadinessHints[state];
    for(const button of body.querySelectorAll('button'))if(button.dataset.restoreRequest==='true')applyRestoreRequestReadiness(button);
    if(restoreBody)for(const button of restoreBody.querySelectorAll('button'))if(button.dataset.restoreRecheck==='true'){
      button.disabled=!restoreReady;button.title=restoreReady?'원본과 대상 상태를 읽어서 확인하며 복원을 재실행하지 않습니다.':restoreReadinessHints[state];
    }
  }
  const restoreLabels={PENDING:'승인 대기',REJECTED:'반려',QUEUED:'복원 대기',RUNNING:'격리복원 진행',RECONCILING:'결과 확인 필요',RECOVERY_REQUIRED:'미확정·차단 유지',FAILED:'격리복원 실패',HEALTH_READY:'건강 준비·기능 확인 전',OPERATOR_CONFIRMED:'운영자 수동 확인'};
  function restoreButton(id,key,label,callback){
    const button=document.createElement('button');
    button.type='button';button.className='btn btn-ghost sm';
    button.dataset.backupFocus='restore-'+id+'-'+key;button.textContent=label;
    if(key==='request'){button.dataset.restoreRequest='true';applyRestoreRequestReadiness(button);}
    if(key==='recheck'||key==='clone-recheck'){button.dataset.restoreRecheck='true';button.disabled=!restoreReady;button.title=restoreReady?'복원 재실행·자원 삭제 없이 확인합니다.':restoreReadinessHints[restoreReadiness];}
    button.onclick=()=>{if(isActive()&&!button.disabled)callback();};return button;
  }
  async function loadRestores(){
    if(!restoreBody||!isActive())return;
    setRestoreReadiness('loading');
    try{
      const [state,response]=await Promise.all([
        call('/api/v1/nexus/restore-requests/readiness'),call('/api/v1/nexus/restore-requests')]);
      if(!isActive())return;
      setRestoreReadiness(state.data&&state.data.ready===true?'ready':'unready');
      if(!detailRetry)restoreError.textContent='';
      const rows=response.data||[],next=JSON.stringify(rows);
      restoreRows=rows;
      const selected=rows.find(row=>row.id===selectedRestoreId);
      const selectedSnapshot=JSON.stringify(selected);
      const retryAvailable=detailRetry&&detailRetry.snapshot===selectedSnapshot&&detailRetry.attempts<=detailRetryLimit;
      const retryExhausted=detailRetry&&detailRetry.snapshot===selectedSnapshot&&detailRetry.attempts>detailRetryLimit;
      if(selected&&!detailPending&&!retryExhausted&&(selectedSnapshot!==selectedRestoreSnapshot||retryAvailable))showRestoreDetail(selectedRestoreId,true);
      if(next===restorePrevious)return;
      restorePrevious=next;
      const active=document.activeElement,key=restoreBody.contains(active)?active.dataset.backupFocus:null;
      restoreBody.replaceChildren();
      if(!rows.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=5;td.textContent='격리복원 요청이 없습니다.';tr.append(td);restoreBody.append(tr);}
      for(const row of rows){
        const tr=document.createElement('tr');
        const values=[`#${row.id} / 백업 #${row.backupRequestId}\n${row.reason}`,`${row.requestedBy||'-'} / ${row.approvedBy||'-'}`,restoreLabels[row.status]||'확인 필요',row.cloneName?`${row.cloneName}\n${row.cloneContainerId}\n${row.confirmedAt?'운영자 확인 '+displayTimestamp(row.confirmedAt):''}`:(row.rejectionReason||row.errorCode||'-')];
        for(const value of values){const td=document.createElement('td');td.textContent=value;td.style.whiteSpace='pre-line';td.style.overflowWrap='anywhere';tr.append(td);}
        const td=document.createElement('td');
        td.append(restoreButton(row.id,'detail','상세',()=>showRestoreDetail(row.id)));
        if(row.status==='PENDING'&&Number(row.requestedById)!==uid&&['APPROVER','SUPER_ADMIN'].includes(role)){
          td.append(restoreButton(row.id,'approve','격리복원 승인',()=>openConfirm('격리복원 승인','원본·기존 clone과 별도의 새 clone을 만듭니다. 포트·경로는 root 설정으로 결정됩니다.',()=>send(`/api/v1/nexus/restore-requests/${row.id}/approve`,{}))));
          td.append(restoreButton(row.id,'reject','반려',()=>{const reason=window.prompt('반려 사유를 입력하세요.');if(reason&&reason.trim())send(`/api/v1/nexus/restore-requests/${row.id}/reject`,{reason:reason.trim()});}));
        }
        if(['RECOVERY_REQUIRED','RECONCILING'].includes(row.status)&&!row.cloneContainerId&&['OPERATOR','SUPER_ADMIN'].includes(role)){
          if(row.activeCheckId){const note=document.createElement('span');note.textContent='상태 확인 #'+row.activeCheckId+' 결과 대기·차단 유지';td.append(note);}
          else {td.append(restoreButton(row.id,'recheck','실행 전 실패 상태 재확인',()=>openConfirm('실행 전 실패 상태 재확인',
            '복원을 다시 실행하거나 자원을 삭제하지 않습니다. 원본 보존과 컨테이너 실행 전 실패가 확정되면 실패로 종료합니다. 확인할 수 없으면 차단을 유지합니다.',
            ()=>send(`/api/v1/nexus/restore-requests/${row.id}/recheck`,{}))));
            if(row.status==='RECOVERY_REQUIRED'&&row.errorCode==='RESULT_UNCERTAIN'&&['cloneContainerId','cloneImageId','cloneName','cloneVolume','cloneNetwork','cloneAccess'].every(k=>row[k]==null))td.append(restoreButton(row.id,'clone-recheck','기존 복원본 준비 상태 확인',()=>openConfirm('기존 복원본 준비 상태 확인',
              '이미 생성된 복원본의 식별·기동·접속 준비와 원본 보존만 읽어서 확인합니다. 복원을 다시 실행하거나 자원을 삭제하지 않습니다. 증거가 일치해도 정상 Nexus GUI의 여섯 항목 기능 검증은 별도로 진행해야 합니다. 확인할 수 없으면 차단을 유지합니다.',
              ()=>send(`/api/v1/nexus/restore-requests/${row.id}/existing-clone-recheck`,{}))));}
          if(row.lastCheckStatus==='UNCONFIRMED'){const note=document.createElement('span');note.textContent='이전 확인 미확정·차단 유지';td.append(note);}
        }
        tr.append(td);restoreBody.append(tr);
      }
      if(key){const button=Array.from(restoreBody.querySelectorAll('button')).find(b=>b.dataset.backupFocus===key);(button||document.getElementById('nxbackup-refresh')).focus({preventScroll:true});}
    }catch(e){if(isActive()){setRestoreReadiness('unavailable');restoreError.textContent=e.message;}}
  }
  function requestRestore(row){
    if(!isActive()||!restoreReady){showToast('격리복원 실행기가 준비되지 않았습니다.','error');return;}
    const reason=window.prompt('격리복원 사유를 입력하세요.');
    if(reason&&reason.trim())send('/api/v1/nexus/restore-requests',{backupRequestId:row.id,reason:reason.trim()});
  }
  async function showRestoreDetail(id,refresh=false){
    selectedRestoreId=id;
    const requestedSnapshot=JSON.stringify(restoreRows.find(row=>row.id===id));
    if(!refresh||detailRetry&&detailRetry.snapshot!==requestedSnapshot)detailRetry=null;
    const sequence=++detailSequence;
    detailPending=sequence;
    try{
      const response=await call(`/api/v1/nexus/restore-requests/${id}`);
      if(!isActive()||sequence!==detailSequence)return;
      const row=response.data;
      const expected=JSON.parse(row.expectedArtifacts||'[]');
      if(!Array.isArray(expected))throw new Error('자료 확인 기준을 읽을 수 없습니다.');
      restoreError.textContent='';
      const neverExecuted=['PENDING','REJECTED'].includes(row.status)&&row.submitted===false&&['cloneContainerId','cloneImageId','cloneName','cloneVolume','cloneNetwork','cloneAccess'].every(key=>row[key]===null)&&expected.length===0;
      const canConfirm=row.status==='HEALTH_READY'&&row.mutationUncertain===false&&row.originalProjectionPreserved===true&&['OPERATOR','SUPER_ADMIN'].includes(role);
      const formKey=JSON.stringify([row.id,row.backupRequestId,row.cloneContainerId,row.manifestSha256,expected]);
      const keepForm=refresh&&canConfirm&&detailForm&&detailForm.key===formKey&&restoreDetail.contains(detailForm.form);
      if(keepForm)detailFields.replaceChildren();
      else{detailForm=null;detailFields=document.createElement('div');restoreDetail.replaceChildren(detailFields);}
      const identity=document.createElement('p');
      identity.textContent=neverExecuted
        ?`복원 #${row.id} · 백업 #${row.backupRequestId}\n복원본 생성 전\n${row.status==='REJECTED'?'반려 · 실행되지 않음':'승인 대기 · 실행 전'}\n원본 보존·자료 검증: 해당 없음(실행 전)`
        :`복원 #${row.id} · 백업 #${row.backupRequestId}\n${restoreLabels[row.status]||'확인 필요'} · 원본 보존 ${row.originalProjectionPreserved===true?'확인':'미확정'} · 실행 불확실성 ${row.mutationUncertain===true?'있음':'없음'}`;
      identity.style.whiteSpace='pre-line';identity.style.overflowWrap='anywhere';detailFields.append(identity);
      const identifiers=document.createElement('details'),identifierTitle=document.createElement('summary');
      identifierTitle.textContent='백업·복원본 식별 정보';identifiers.append(identifierTitle);
      function identifier(label,value){const line=document.createElement('p');line.textContent=label+': '+(value||'미제공');line.className='mono';line.style.overflowWrap='anywhere';identifiers.append(line);}
      identifier('백업 목록 SHA-256',row.manifestSha256);if(row.cloneContainerId)identifier('복원본 컨테이너',row.cloneContainerId);detailFields.append(identifiers);
      if(row.confirmedAt){
        const evidence=document.createElement('p');
        const names={LOGIN:'Nexus 로그인',H2_LOCAL:'로컬 H2 데이터베이스',FILE_BLOB_LOCAL:'로컬 파일 저장소',TASKS:'예약 작업',USERS_ROLES_REALMS:'사용자·역할·인증 realm',REPOSITORIES:'저장소 목록'};
        const checked=JSON.parse(row.operatorChecklist||'[]'),artifacts=JSON.parse(row.artifactConfirmations||'[]');
        const checkedText=Array.isArray(checked)&&checked.length?checked.map(key=>names[key]||'알 수 없는 확인 항목').join(', '):'미제공';
        const artifactText=Array.isArray(artifacts)&&artifacts.length?artifacts.map(item=>item.ref+' 다운로드 확인').join(', '):'사전 지정된 개별 자료 없음 · 개별 파일 자동 검증 불가';
        evidence.textContent=`운영자 확인자 #${row.confirmedById} · ${displayTimestamp(row.confirmedAt)}\n확인 항목: ${checkedText}\n자료 확인: ${artifactText}\n운영자 수동 기록이며 자동 검증 성공·원장 해소·자동 정리를 뜻하지 않습니다.`;
        evidence.style.whiteSpace='pre-line';detailFields.append(evidence);
      }
      if(row.cloneAccess){
        const access=JSON.parse(row.cloneAccess),connection=document.createElement('p');
        if(access.schema===2&&access.transport==='EXECUTION_HOST_BRIDGE_ACCESS'){
          identifier('복원본 네트워크',access.networkId);
          connection.textContent=`복원본은 실행 서버 ${access.executionHost}의 내부 주소 ${access.containerIpv4}:${access.containerPort}에서 동작합니다. 승인된 관리 접속 경로에서 위 컨테이너와 네트워크 식별 정보를 대조한 뒤 복원본의 Nexus 화면을 여세요. 복원본에 접속할 수 없으면 아래 확인 항목을 선택하지 마세요.`;
        }else{
          const gui=access.ports.find(port=>port.containerPort===8081);
          connection.textContent=`실행 서버 ${access.executionHost}의 Docker 내부 전용 접속: ${gui?gui.hostIp+':'+gui.hostPort:'미제공'}. 현재 브라우저의 localhost 주소가 아닙니다. 정상 QA relay를 정확한 실행 서버·clone ID·이 publish 포트와 대조한 뒤 Nexus GUI를 확인하세요. 일반 네트워크 공개 바인딩은 제공하지 않습니다.`;
        }
        connection.style.overflowWrap='anywhere';detailFields.append(connection);
      }
      if(!neverExecuted){const note=document.createElement('p');note.textContent=expected.length?'root가 보존한 실제 원본 다운로드 기준 영수증과 일치하는 자료만 입력할 수 있습니다. 입력은 운영자의 수동 확인 기록입니다.':'개별 자료 기준 미제공: archive 전체 SHA로 Maven 개별 파일 SHA를 도출할 수 없습니다. 자동 자료 검증은 불가합니다.';detailFields.append(note);}
      if(!canConfirm){selectedRestoreSnapshot=requestedSnapshot;detailRetry=null;return;}
      if(!keepForm){
        const form=document.createElement('form');
        const checklist=[['LOGIN','정상 Nexus GUI 로그인'],['H2_LOCAL','H2 로컬 DB'],['FILE_BLOB_LOCAL','로컬 파일 blob'],['TASKS','작업 목록'],['USERS_ROLES_REALMS','사용자·역할·realm'],['REPOSITORIES','저장소 목록']];
        const inputs=checklist.map(([value,label])=>{const line=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.required=true;input.value=value;line.append(input,document.createTextNode(' '+label));line.style.display='block';form.append(line);return input;});
        const artifacts=expected.map(item=>{const label=document.createElement('label'),input=document.createElement('input');label.textContent=item.ref+' · 원본 기준 영수증 '+item.baselineReceiptSha256;input.className='form-input mono';input.required=true;input.pattern='[0-9a-f]{64}';input.maxLength=64;input.setAttribute('aria-label',item.ref+' 실제 clone 다운로드 SHA256');label.append(input);form.append(label);return {ref:item.ref,input};});
        const submit=document.createElement('button');submit.type='submit';submit.className='btn btn-primary';submit.textContent='운영자 수동 확인 저장';form.append(submit);
        detailForm={key:formKey,form,inputs,artifacts};restoreDetail.append(form);
      }
      const {form,inputs,artifacts}=detailForm;
      form.onsubmit=event=>{event.preventDefault();if(!isActive()||sequence!==detailSequence||!form.reportValidity())return;send(`/api/v1/nexus/restore-requests/${id}/functional-confirmation`,{cloneContainerId:row.cloneContainerId,manifestSha256:row.manifestSha256,checklist:inputs.filter(i=>i.checked).map(i=>i.value),artifacts:artifacts.map(a=>({ref:a.ref,downloadedSha256:a.input.value}))}).then(()=>{if(isActive()&&sequence===detailSequence)showRestoreDetail(id);});};
      selectedRestoreSnapshot=requestedSnapshot;
      detailRetry=null;
    }catch(e){if(isActive()&&sequence===detailSequence){
      detailRetry={snapshot:requestedSnapshot,attempts:(detailRetry?.attempts||0)+1};
      restoreError.textContent=e.message+(detailRetry.attempts>detailRetryLimit?' 자동 재조회에 실패했습니다. 상세 버튼으로 다시 조회해 주세요.':' 다음 조회에서 상세를 다시 확인합니다.');
    }}
    finally{if(sequence===detailSequence)detailPending=0;}
  }

  async function load(){if(!isActive()||loading)return;loading=true;try{await loadBackupReadiness();if(!isActive())return;await loadRestores();if(!isActive())return;const r=await call('/api/v1/nexus/backup-requests');if(!isActive())return;const rows=r.data||[],next=JSON.stringify(rows);if(!mutationError)error.textContent='';if(next===previous)return;previous=next;const active=document.activeElement,focusKey=body.contains(active)?active.dataset.backupFocus:null;body.replaceChildren();if(!rows.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=5;td.textContent='백업 요청이 없습니다.';tr.append(td);body.append(tr);return;}for(const row of rows){const tr=document.createElement('tr');const values=[`#${row.id} · ${row.reason}`,`${row.requestedBy||'-'} / ${row.approvedBy||'-'}`,`${labels[row.status]||'결과 확인 필요'} / ${stages[row.stage]||'확인 중'}\n요청 ${displayTimestamp(row.createdAt)}\n${row.status==='REJECTED'?'반려':'승인'} ${displayTimestamp(row.approvedAt)}\n${row.status==='REJECTED'?'처리(서버 시간)':'완료(UTC)'} ${displayTimestamp(row.completedAt)}`,row.artifactId?`${row.artifactId}\n${row.manifestSha256||''}`:(row.rejectionReason||errors[row.errorCode]||'-')];for(const value of values){const td=document.createElement('td');td.textContent=value;td.style.whiteSpace='pre-line';if(tr.children.length===2){td.title=`요청 ${row.createdAt||'-'}\n${row.status==='REJECTED'?'반려':'승인'} ${row.approvedAt||'-'}\n${row.status==='REJECTED'?'처리(서버 시간)':'완료(UTC)'} ${row.completedAt||'-'}`;}tr.append(td);}const td=document.createElement('td');if(row.status==='PENDING'&&Number(row.requestedById)!==uid&&['APPROVER','SUPER_ADMIN'].includes(role)){for(const [action,label]of [['approve','승인·백업'],['reject','반려']]){const button=document.createElement('button');button.type='button';button.dataset.backupFocus=row.id+'-'+action;if(action==='approve'){button.dataset.backupApprove='true';applyBackupReadiness(button);}button.className='btn btn-ghost sm';button.textContent=label;button.onclick=()=>decide(row.id,action);td.append(button);}}else if(['RECOVERY_REQUIRED','RECONCILING'].includes(row.status)&&['OPERATOR','SUPER_ADMIN'].includes(role)){if(row.activeCheckId){const note=document.createElement('span');note.textContent='확인 결과 대기·미수신 · #'+row.activeCheckId;td.append(note);const button=document.createElement('button');button.type='button';button.className='btn btn-ghost sm';button.dataset.backupFocus=row.id+'-supersede-'+row.activeCheckId;button.textContent='다시 확인 요청';button.onclick=()=>openConfirm('미확정 확인 요청 대체','이전 확인 결과가 없거나 확인할 수 없을 때 새 확인 ID로 다시 읽기 확인을 요청합니다. 이전 결과는 반영하지 않으며 유지보수 차단을 유지합니다. Nexus 시작·중지·백업 재실행은 하지 않습니다.',()=>send(`/api/v1/nexus/backup-requests/${row.id}/rechecks/${row.activeCheckId}/supersede`,{}));td.append(button);}else{const button=document.createElement('button');button.type='button';button.className='btn btn-ghost sm';button.dataset.backupFocus=row.id+'-recheck';button.textContent='복구 상태 재확인';button.onclick=()=>openConfirm('원본 상태 재확인','원본 상태를 읽어서 확인합니다. Nexus를 시작·중지하거나 백업을 다시 실행하지 않습니다. 기준 정보가 없거나 상태가 다르면 차단을 유지합니다.',()=>send(`/api/v1/nexus/backup-requests/${row.id}/recheck`,{}));td.append(button);}}else td.textContent='-';if(restoreBody&&row.status==='COMPLETED'&&['OPERATOR','SUPER_ADMIN'].includes(role))td.append(restoreButton(row.id,'request','격리복원 신청',()=>requestRestore(row)));tr.append(td);body.append(tr);}if(focusKey){const restored=Array.from(body.querySelectorAll('button')).find(b=>b.dataset.backupFocus===focusKey);(restored||document.getElementById('nxbackup-refresh')).focus({preventScroll:true});}}catch(e){if(isActive()&&!mutationError)error.textContent=e.message;}finally{loading=false;}}
  async function send(url,payload){if(!isActive()||busy)return;busy=true;mutationError=false;error.textContent='';try{const r=await call(url,{method:'POST',headers:headers(),body:JSON.stringify(payload)});if(!isActive())return;showToast(r.message,'success');await load();}catch(e){if(isActive()){mutationError=true;error.textContent=e.message;showToast(e.message,'error');}}finally{busy=false;}}
  function decide(id,action){if(!isActive())return;if(action==='approve'&&!backupReady){showToast(backupReadinessHints[backupReadiness],'error');return;}if(action==='approve'&&!stopped()){showToast('외부 게시 작업 중지 확인이 필요합니다.','error');return;}const reason=action==='reject'?window.prompt('반려 사유를 입력하세요.'):null;if(action==='reject'&&(!reason||!reason.trim()))return;openConfirm(action==='approve'?'Nexus 백업 승인':'Nexus 백업 반려',action==='approve'?'Nexus가 잠시 중지됩니다. 외부 게시 작업이 중지된 유지보수 창에서 백업을 시작합니다.':'이 백업 요청을 반려합니다.',()=>send(`/api/v1/nexus/backup-requests/${id}/${action}`,{externalWritersStopped:action==='approve',reason:reason?reason.trim():null}));}
  document.getElementById('nxbackup-refresh').onclick=load;
  const request=document.getElementById('nxbackup-request');if(request)request.onclick=()=>{if(!backupReady){showToast(backupReadinessHints[backupReadiness],'error');return;}const reason=document.getElementById('nxbackup-reason').value.trim();if(!reason||!stopped()){showToast('백업 사유와 외부 게시 작업 중지 확인이 필요합니다.','error');return;}send('/api/v1/nexus/backup-requests',{reason,externalWritersStopped:true});};
  load();timer=setInterval(()=>{if(!isActive()){cleanup();return;}if(!document.hidden&&!busy)load();},5000);
})();
