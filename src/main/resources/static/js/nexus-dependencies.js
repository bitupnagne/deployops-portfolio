/* Stream Blob slices; asynchronous results belong to one page and project generation. */
(() => {
  'use strict';
  const root = document.getElementById('nxdependency');
  if (!root) return;
  const element = id => root.querySelector('#nxd-' + id);
  let generation = 0, binding, permissions = {}, rows = [], running = false, stopped = false, xhr, buildPlan;
  const context = () => ({generation, projectId: element('project').value});
  const current = ctx => root.isConnected && document.getElementById('nxdependency') === root
    && ctx.generation === generation && ctx.projectId === element('project').value;
  const stale = () => Object.assign(Error('이전 프로젝트 요청은 적용하지 않았습니다.'), {stale:true});
  const requireCurrent = ctx => { if (!current(ctx)) throw stale(); };
  const base = ctx => '/api/v1/nexus/deploy-projects/' + encodeURIComponent(ctx.projectId) + '/dependencies';
  const headers = json => {
    const token = document.querySelector('meta[name="_csrf"]');
    const name = document.querySelector('meta[name="_csrf_header"]');
    return {...(json ? {'Content-Type':'application/json'} : {}), ...(token && name ? {[name.content]:token.content} : {})};
  };
  const message = text => { element('message').textContent = text; };
  function controls(loading = false) {
    ['format','repository','namespace','save','list'].forEach(id => element(id).disabled = loading || running || permissions.canManage !== true);
    ['start','files','folder','prefix'].forEach(id => element(id).disabled = loading || running || permissions.canUpload !== true);
    element('project').disabled = running;
    element('refresh').disabled = loading || permissions.canUpload !== true;
    ['build-load','build-preview','build-verify','build-common','build-reason'].forEach(id =>
      element(id).disabled = loading || running || permissions.canUpload !== true);
    element('build-apply').disabled = loading || running || permissions.canManage !== true;
  }
  async function request(ctx, path, method = 'GET', body) {
    requireCurrent(ctx);
    let response, result;
    try {
      response = await fetch(base(ctx) + path, {method, cache:'no-store', headers:headers(body !== undefined),
        ...(body === undefined ? {} : {body:JSON.stringify(body)})});
      result = await response.json();
    } catch(error) { requireCurrent(ctx); throw error; }
    requireCurrent(ctx);
    if (!response.ok || !result.success) throw Error(result.message || result.detail || '요청을 처리하지 못했습니다.');
    return result.data;
  }
  const size = bytes => Number(bytes).toLocaleString() + ' B';
  function updateMeter(cell, value, total) {
    const safe = Math.max(0,Math.min(Number(value) || 0,Number(total) || 0));
    const percent = total > 0 ? Math.min(100,safe / total * 100) : 0;
    cell.querySelector('progress').max = total || 1;
    cell.querySelector('progress').value = safe;
    cell.querySelector('div').textContent = percent.toFixed(1) + '% · ' + size(safe) + ' / ' + size(total);
  }
  function meter(value, total, label) {
    const cell = document.createElement('td'), progress = document.createElement('progress'), text = document.createElement('div');
    progress.setAttribute('aria-label',label); progress.style.maxWidth = '100%';
    cell.append(progress,text); updateMeter(cell,value,total); return cell;
  }
  function render() {
    const body = element('rows'); body.replaceChildren();
    const ctx = context();
    rows.forEach(row => {
      const tr = document.createElement('tr'), file = document.createElement('td'), status = document.createElement('td'), actions = document.createElement('td');
      tr.dataset.uploadId = row.id;
      file.textContent = row.repositoryName + ' / ' + row.targetPath; file.style.overflowWrap='anywhere';
      status.textContent = row.status + ' · ' + (row.message || '');
      const add = (label, action) => {
        const button = document.createElement('button'); button.type='button'; button.className='btn btn-ghost btn-sm'; button.textContent=label;
        button.addEventListener('click', async () => {
          if(action==='checksums' && !window.confirm('원격 원본 SHA-256을 확인한 뒤 누락된 Maven checksum만 등록합니다. 기존 본문과 checksum은 덮어쓰지 않습니다. 진행할까요?'))return;
          button.disabled=true;
          try { await request(ctx,'/uploads/'+row.id+'/'+action,'POST'); await refresh(ctx); }
          catch(e) { if(current(ctx) && !e.stale) message(e.message); }
          finally { if(current(ctx)) button.disabled=false; }
        }); actions.append(button);
      };
      if (row.status === 'READY') add('Nexus 등록','publish');
      if (row.status === 'VERIFY_REQUIRED') add('등록 결과 확인','verify');
      if (row.repositoryFormat==='maven2' && !/\.(sha1|sha256|sha512|md5|asc)$/i.test(row.targetPath)
          && ['COMPLETED','VERIFY_REQUIRED'].includes(row.status)) add('누락 checksum 등록 재개','checksums');
      if (['RECEIVING','READY','PUBLISHING'].includes(row.status)) add('취소','cancel');
      const received = meter(row.receivedBytes,row.totalBytes,'브라우저에서 서버로 파일 전송');
      received.dataset.receiveId = row.id;
      tr.append(file,received,meter(row.publishedBytes,row.totalBytes,'서버에서 Nexus로 파일 전송'),status,actions);
      body.append(tr);
    });
  }
  function upsert(row) {
    const index = rows.findIndex(value => value.id === row.id);
    if (index < 0) rows.unshift(row); else rows[index] = row;
    render();
  }
  async function refresh(ctx = context()) {
    if (!ctx.projectId) return;
    const loaded = await request(ctx,'/uploads');
    requireCurrent(ctx); rows = loaded; render();
  }
  async function load() {
    generation++; stopped = true;
    if (xhr) xhr.abort();
    const ctx = context();
    binding = null; permissions = {}; rows = []; buildPlan = null; render(); controls(true);
    element('selection-summary').textContent = '';
    element('build-xml').textContent = '';
    element('build-common').replaceChildren(new Option('먼저 연결 조회',''));
    element('build-status').textContent = '연결 준비와 파이프라인 실제 빌드 성공은 별도로 확인합니다.';
    ['repository','namespace'].forEach(id => element(id).value = '');
    if (!ctx.projectId) { controls(); message('프로젝트를 선택하세요.'); return; }
    try {
      const result = await request(ctx,'');
      binding=result.binding; permissions=result;
      element('format').value=binding.format; element('repository').value=binding.repository; element('namespace').value=binding.namespace;
      if(result.canUpload) await refresh(ctx);
      else message('조회 권한만 있습니다. 파일 반입에는 운영자 역할과 프로젝트 CI 관리 권한이 필요합니다.');
    } finally { if(current(ctx)) controls(); }
  }
  function sendChunk(ctx,row,file,offset,length) {
    requireCurrent(ctx);
    return new Promise((resolve,reject) => {
      const transfer = new XMLHttpRequest(); xhr = transfer;
      transfer.open('PUT',base(ctx)+'/uploads/'+row.id+'/chunk?offset='+offset+'&length='+length);
      Object.entries(headers(false)).forEach(([key,value]) => transfer.setRequestHeader(key,value));
      transfer.setRequestHeader('Content-Type','application/octet-stream');
      transfer.upload.onprogress = event => {
        if(!current(ctx)) { transfer.abort(); return; }
        const transferred=Math.min(file.size,offset+event.loaded);
        const visible=Math.max(Number(row.receivedBytes) || 0,transferred);
        const cell=Array.from(element('rows').querySelectorAll('[data-receive-id]')).find(node => node.dataset.receiveId===row.id);
        if(cell) updateMeter(cell,visible,file.size);
        message(file.name+' · '+(visible / file.size * 100).toFixed(1)+'% · '+size(visible)+' / '+size(file.size)
          +(offset < row.receivedBytes ? ' · 기존 파일 내용 확인 중' : ''));
      };
      transfer.onload = () => {
        try {
          requireCurrent(ctx);
          const data=JSON.parse(transfer.responseText);
          if(transfer.status<200 || transfer.status>=300 || !data.success) throw Error(data.message || data.detail || '청크 전송 실패');
          resolve(data.data);
        } catch(e) { reject(e); }
      };
      transfer.onerror = () => reject(Error('연결이 끊겼습니다. 같은 파일을 다시 선택해 재개할 수 있습니다.'));
      transfer.onabort = () => reject(current(ctx) ? Error('파일 전송을 일시정지했습니다. 같은 파일로 재개할 수 있습니다.') : stale());
      transfer.send(file.slice(offset,offset+length));
    });
  }
  async function start() {
    if(running) return;
    const ctx=context();
    if(!ctx.projectId || !binding?.repository || permissions.canUpload !== true) throw Error('프로젝트 저장소 연결과 업로드 권한을 먼저 확인하세요.');
    const files=[...element('files').files,...element('folder').files];
    if(!files.length) throw Error('파일 또는 폴더를 선택하세요.');
    const prefix=element('prefix').value.trim().replace(/\/$/,'');
    const selectedBinding={...binding};
    const selected=files.map(file => {
      let relative=file.webkitRelativePath ? file.webkitRelativePath.split('/').slice(1).join('/') : file.name;
      if(prefix) relative=prefix+'/'+relative;
      return {file,relative};
    });
    // Repository paths are case-sensitive; only the checksum suffix is case-insensitive.
    const payloads=new Set(selected.filter(item=>!/\.(sha1|sha256|sha512|md5|asc)$/i.test(item.relative)).map(item=>item.relative));
    const queue=selected.filter(item => selectedBinding.format!=='maven2'
      || !/\.(sha1|md5|sha256)$/i.test(item.relative)
      || !payloads.has(item.relative.replace(/\.(sha1|md5|sha256)$/i,'')));
    const omitted=selected.length-queue.length;
    element('selection-summary').textContent='선택 '+selected.length+'개 · 전송 '+queue.length+'개'
      +(omitted?' · 본문과 함께 선택한 Maven checksum '+omitted+'개 자동 제외(서버에서 생성)':' · 자동 제외 없음');
    running=true; stopped=false; controls();
    try {
      await refresh(ctx);
      for(const {file,relative} of queue) {
        if(stopped) break;
        requireCurrent(ctx);
        const target=selectedBinding.format==='raw' && selectedBinding.namespace ? selectedBinding.namespace+'/'+relative : relative;
        let row=rows.find(value => value.status==='RECEIVING' && value.repositoryName===selectedBinding.repository
          && value.repositoryFormat===selectedBinding.format && value.targetPath===target && value.totalBytes===file.size)
          || await request(ctx,'/uploads','POST',{relativePath:relative,totalBytes:file.size});
        upsert(row);
        // Compare the existing prefix before appending. Equal name and length do not prove equal contents.
        for(let offset=0;offset<file.size;offset+=8*1024*1024) {
          if(stopped) break;
          row=await sendChunk(ctx,row,file,offset,Math.min(8*1024*1024,file.size-offset));
          requireCurrent(ctx); upsert(row);
        }
        if(stopped) break;
        await request(ctx,'/uploads/'+row.id+'/publish','POST');
        do {
          await new Promise(resolve => setTimeout(resolve,1500));
          await refresh(ctx); row=rows.find(value => value.id===row.id);
        } while(row?.status==='PUBLISHING' && !stopped);
        if(row?.status!=='COMPLETED' && !stopped) throw Error('Nexus 등록이 완료되지 않았습니다. 이력의 상태와 결과를 확인하세요.');
      }
      if(!stopped) message('선택한 파일의 Nexus 등록과 내용 확인이 완료됐습니다.');
    } finally {
      running=false; xhr=null;
      if(current(ctx)) { controls(); await refresh(ctx); }
    }
  }
  const action = fn => async() => {
    const ctx=context();
    try { await fn(); } catch(e) { if(root.isConnected && !e.stale && (current(ctx) || fn===load)) message(e.message); }
  };
  element('project').addEventListener('change',action(load));
  element('save').addEventListener('click',action(async() => {
    const ctx=context();
    await request(ctx,'','PUT',{format:element('format').value,repository:element('repository').value.trim(),namespace:element('namespace').value.trim()});
    requireCurrent(ctx); await load(); message('프로젝트 저장소 연결을 저장했습니다.');
  }));
  element('list').addEventListener('click',action(async() => {
    const ctx=context(), format=element('format').value;
    const repositories=await request(ctx,'/repositories?format='+encodeURIComponent(format));
    requireCurrent(ctx);
    if(element('format').value!==format) return;
    element('repositories').replaceChildren(...repositories.map(row => {const option=document.createElement('option');option.value=row.name;return option;}));
    message('저장소 입력란에서 선택하세요.');
  }));
  element('start').addEventListener('click',action(start));
  element('pause').addEventListener('click',() => { stopped=true; if(xhr) xhr.abort(); message('브라우저 전송을 일시정지합니다. 이미 시작한 Nexus 등록은 이력에서 취소하거나 결과를 확인하세요.'); });
  element('refresh').addEventListener('click',action(() => refresh()));
  const buildQuery = () => '?commonRepository='+encodeURIComponent(element('build-common').value);
  const buildResult = result => {
    element('build-status').textContent = 'File 설정 일치: '+(result.settingsMatch?'확인':'미확인')
      +' · 인증 변수: '+(result.credentialsConfigured?'확인':'미등록 또는 확인 불가')+' · '+result.message;
  };
  element('build-load').addEventListener('click',action(async() => {
    const ctx=context(), result=await request(ctx,'/maven-build');
    element('build-common').replaceChildren(new Option('선택',''),...result.repositories.map(row=>new Option(row.name+' ('+row.type+')',row.name)));
    element('build-common').value=result.commonRepository; buildPlan=null;
    element('build-status').textContent='기존 Maven 저장소를 선택하고 생성할 설정을 미리 확인하세요.';
  }));
  element('build-common').addEventListener('change',()=>{buildPlan=null;element('build-xml').textContent='';});
  element('build-preview').addEventListener('click',action(async() => {
    const ctx=context(), common=element('build-common').value;
    const result=await request(ctx,'/maven-build/preview'+buildQuery());
    if(element('build-common').value!==common)return;
    buildPlan=result;element('build-xml').textContent=result.settingsXml;
    element('build-status').textContent='전용 group: '+result.groupName+' · '+result.warning;
  }));
  element('build-apply').addEventListener('click',action(async() => {
    if(!buildPlan || buildPlan.commonRepository!==element('build-common').value)throw Error('Maven 설정 미리보기를 먼저 확인하세요.');
    if(!element('build-reason').value.trim())throw Error('변경 사유를 입력하세요.');
    if(!window.confirm('프로젝트 전용 Nexus group을 준비하고 이 프로젝트의 기존 MAVEN_SETTINGS_FILE 전체를 교체합니다. CI 브랜치 파일은 변경하지 않습니다. 적용할까요?'))return;
    const ctx=context(), plan=buildPlan, button=element('build-apply');button.disabled=true;
    try {
      const result=await request(ctx,'/maven-build/apply','POST',{commonRepository:plan.commonRepository,
        expectedUploadRepository:plan.uploadRepository,reason:element('build-reason').value.trim()});
      if(element('build-common').value===plan.commonRepository)buildResult(result);
    } finally {if(current(ctx))controls();}
  }));
  element('build-verify').addEventListener('click',action(async() => {
    const ctx=context(), common=element('build-common').value;
    const result=await request(ctx,'/maven-build/verify'+buildQuery());
    if(element('build-common').value===common)buildResult(result);
  }));
  const unload=() => {stopped=true;generation++;if(xhr)xhr.abort();};
  window.addEventListener('beforeunload',unload);
  const observer=new MutationObserver(() => {
    if(!root.isConnected) {unload();observer.disconnect();window.removeEventListener('beforeunload',unload);}
  });
  observer.observe(document.body,{childList:true,subtree:true});
  controls();
  const initialProject=new URLSearchParams(window.location.search).get('dependencyProjectId');
  if(initialProject && [...element('project').options].some(option=>option.value===initialProject)) {
    element('project').value=initialProject;
    action(load)();
  }
})();
