(() => {
  'use strict';
  const context=document.getElementById('nexus-ops-context'),body=document.getElementById('nxpromotion-body');
  if(!context||!body||context.nexusPromotionCleanup)return;
  const script=document.currentScript,content=document.getElementById('content');
  if(script&&script.deployPageNavigation!==undefined&&(script.deployPageContent!==content||!content||content.deployPageNavigation!==script.deployPageNavigation||!content.contains(context)))return;
  const uid=Number(context.dataset.userId),role=context.dataset.role,error=document.getElementById('nxpromotion-error');
  let disposed=false,busy=false,loading=false,ready=false,previous='',timer;
  function active(){return !disposed&&context.isConnected&&document.getElementById('nexus-ops-context')===context;}
  function cleanup(){disposed=true;clearInterval(timer);window.removeEventListener('deploy:page-leave',cleanup);window.removeEventListener('pagehide',cleanup);delete context.nexusPromotionCleanup;}
  context.nexusPromotionCleanup=cleanup;window.addEventListener('deploy:page-leave',cleanup);window.addEventListener('pagehide',cleanup);
  async function call(url,options){const response=await fetch(url,options),data=await response.json();if(!response.ok||!data.success)throw new Error(data.message||'운영 전환 요청을 처리하지 못했습니다.');return data.data;}
  function headers(){const token=document.querySelector('meta[name="_csrf"]'),header=document.querySelector('meta[name="_csrf_header"]');return {'Content-Type':'application/json',[header.content]:token.content};}
  function externalStopped(){return document.getElementById('nxpromotion-external').checked;}
  async function send(url,value){if(!active()||busy)return;busy=true;error.textContent='';try{await call(url,{method:'POST',headers:headers(),body:JSON.stringify(value)});if(active()){previous='';await load();}}catch(failure){if(active())error.textContent=failure.message;}finally{busy=false;}}
  async function load(){
    if(!active()||loading)return;loading=true;
    try{
      const [state,rows,restores]=await Promise.all([call('/api/v1/nexus/promotion-requests/readiness'),call('/api/v1/nexus/promotion-requests'),call('/api/v1/nexus/restore-requests')]);
      if(!active())return;ready=state.ready===true;
      document.getElementById('nxpromotion-ready').textContent=ready?'운영 전환 실행기 준비됨':'운영 전환 실행기 미준비: 원본 Nexus·저장소·키와 실행기 설치 상태를 확인하세요.';
      const request=document.getElementById('nxpromotion-request');if(request)request.disabled=!ready;
      const select=document.getElementById('nxpromotion-restore');
      if(select){const value=select.value,eligible=restores.filter(row=>row.status==='OPERATOR_CONFIRMED'&&!rows.some(p=>p.restoreRequestId===row.id&&!['FAILED','REJECTED'].includes(p.status)));const options=JSON.stringify(eligible.map(row=>row.id));if(select.dataset.options!==options){select.replaceChildren(new Option('선택',''));for(const row of eligible)select.append(new Option('복원 #'+row.id+' / 백업 #'+row.backupRequestId+' / '+row.cloneName,String(row.id)));select.value=value;select.dataset.options=options;}}
      const next=JSON.stringify([rows,ready]);if(next===previous)return;previous=next;body.replaceChildren();
      if(!rows.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=5;td.textContent='운영 전환 요청이 없습니다.';tr.append(td);body.append(tr);}
      const labels={PENDING:'승인 대기',REJECTED:'반려',QUEUED:'전환 대기',RUNNING:'운영 전환 중',COMPLETED:'운영 접속점 전환 완료',FAILED:'실행 전 확인 실패',RECOVERY_REQUIRED:'결과 미확정·차단 유지'};
      for(const row of rows){
        const tr=document.createElement('tr');for(const value of ['#'+row.id+' / 복원 #'+row.restoreRequestId+'\n'+row.reason,(row.requestedBy||'-')+' / '+(row.approvedBy||'-'),(labels[row.status]||'확인 필요')+(row.errorCode?'\n'+row.errorCode:''),'운영: '+(row.canonicalContainerId||'-')+'\n복구용: '+(row.rollbackContainerId||'-')]){const td=document.createElement('td');td.textContent=value;td.style.whiteSpace='pre-line';td.style.overflowWrap='anywhere';tr.append(td);}
        const td=document.createElement('td');
        if(row.status==='PENDING'&&Number(row.requestedById)!==uid&&['APPROVER','SUPER_ADMIN'].includes(role)){
          const button=document.createElement('button');button.type='button';button.className='btn btn-primary sm';button.textContent='승인·운영 전환';button.disabled=!ready;
          button.onclick=()=>{if(!externalStopped()){showToast('외부 게시 작업 중지를 확인해 주세요.','error');return;}openConfirm('Nexus 복원본 운영 전환 승인','기존 Nexus가 잠시 중지됩니다. 백업 시점의 복원본을 운영 접속점으로 전환하고 기존 컨테이너·볼륨·키는 복구용으로 보존합니다.',()=>send('/api/v1/nexus/promotion-requests/'+row.id+'/approve',{externalWritersStopped:true}));};td.append(button);
          const reject=document.createElement('button');reject.type='button';reject.className='btn btn-ghost sm';reject.textContent='반려';reject.onclick=()=>{const reason=window.prompt('운영 전환 반려 사유를 입력하세요.');if(reason?.trim())send('/api/v1/nexus/promotion-requests/'+row.id+'/reject',{reason:reason.trim()});};td.append(reject);
        }else td.textContent=row.status==='COMPLETED'?'기존 복구용 자료 보존 · CI·게시 확인 별도':(row.rejectionReason||'-');
        tr.append(td);body.append(tr);
      }
    }catch(failure){if(active()){ready=false;error.textContent=failure.message;const request=document.getElementById('nxpromotion-request');if(request)request.disabled=true;}}
    finally{loading=false;}
  }
  const request=document.getElementById('nxpromotion-request');if(request)request.onclick=()=>{const restoreRequestId=Number(document.getElementById('nxpromotion-restore').value),reason=document.getElementById('nxpromotion-reason').value.trim();if(!ready||!restoreRequestId||!reason||!externalStopped()){showToast('복원본, 사유와 외부 게시 작업 중지 확인이 필요합니다.','error');return;}send('/api/v1/nexus/promotion-requests',{restoreRequestId,reason,externalWritersStopped:true});};
  document.getElementById('nxpromotion-refresh').onclick=load;load();timer=setInterval(()=>{if(!active()){cleanup();return;}if(!document.hidden&&!busy)load();},5000);
})();
