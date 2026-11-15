(function () {
  'use strict';
  const MAX_BYTES = 61440;
  let currentSnapshot = null;
  let previewGeneration = 0;
  let currentGeneration = 0;
  let requestGeneration = 0;
  const element = id => document.getElementById(id);
  const draft = () => element('nginx-config-editor');
  const form = () => element('nginx-policy-form');
  const bytes = text => new TextEncoder().encode(text).byteLength;
  const notice = (message, kind) => showToast(message, kind || 'error');
  const selectedServer = () => element('ng-server')?.value || element('nginx-server-workspace')?.dataset.nginxServerId;
  const policyIdentity = () => `${selectedServer() || ''}:${element('ng-policy')?.value.trim() || ''}`;
  const isCurrent = node => node && node.isConnected && node === form();
  function templateData(target) {
    const data = nginxPolicyFormData(target);
    ['rawConfig','certificate','privateKey'].forEach(name=>data.delete(name));
    return data;
  }
  const dataSignature = data => JSON.stringify([...data.entries()].filter(([,value])=>typeof value==='string'));

  function updateStatus() {
    const editor = draft(), status = element('nginx-editor-status');
    if (!editor || !status) return;
    const size = bytes(editor.value);
    status.textContent = editor.value.trim() ? `변경안 ${size.toLocaleString('ko-KR')} / ${MAX_BYTES.toLocaleString('ko-KR')} 바이트 · 승인 전까지 적용되지 않음` : '설정문을 작성해 주세요.';
    editor.setCustomValidity(size > MAX_BYTES ? '설정문은 UTF-8 기준 60KB 이내로 작성해 주세요.' : '');
  }

  function validateDraft() {
    const editor = draft();
    if (!editor) return true;
    updateStatus();
    if (!editor.value.trim()) { notice('승인 요청할 설정문을 작성해 주세요.'); editor.focus(); return false; }
    if (bytes(editor.value) > MAX_BYTES) { notice('설정문은 UTF-8 기준 60KB 이내로 작성해 주세요.'); editor.focus(); return false; }
    return true;
  }

  function replaceDraft(text, expected, reason, guard, beforeApply) {
    const editor = draft();
    if (!editor) return;
    const apply = () => {
      if (!editor.isConnected || draft() !== editor) return;
      if (guard && !guard()) { notice('선택한 서버·정책이나 입력값이 바뀌어 반영하지 않았습니다. 작성한 내용은 유지했습니다.'); return; }
      if (editor.value !== expected) { notice('확인하는 동안 변경안이 수정되었습니다. 작성한 내용은 유지했습니다.'); return; }
      if (beforeApply) beforeApply();
      editor.value = text;
      updateStatus();
      editor.focus();
    };
    if (expected.trim()) openConfirm(reason, '작성 중인 변경안을 선택한 내용으로 교체합니다. 계속할까요?', apply);
    else apply();
  }

  async function generateTemplate() {
    const editor = draft(), target = form();
    if (!editor || !target) return;
    const before = editor.value, generation = ++previewGeneration;
    // The editor itself is initially empty; only validate template input fields here.
    const wasDisabled = editor.disabled;
    editor.disabled = true;
    let valid;
    try { valid = target.reportValidity(); }
    finally { editor.disabled = wasDisabled; }
    if (!valid) return;
    try {
      const data = templateData(target), signature = dataSignature(data);
      const identity = policyIdentity();
      const response = await fetch('/api/v1/nginx-changes/preview', {method:'POST', headers:nginxCsrfHeaders(), body:data});
      const result = await nginxResult(response);
      if (!isCurrent(target) || generation !== previewGeneration) return;
      if (identity !== policyIdentity()) { notice('선택한 서버나 정책이 바뀌었습니다. 새 입력값으로 다시 생성해 주세요.'); return; }
      if (signature !== dataSignature(templateData(target))) { notice('설정 입력값이 바뀌었습니다. 새 입력값으로 다시 생성해 주세요.'); return; }
      if (typeof result.data?.config !== 'string' || !result.data.config.trim()) throw new Error('생성된 설정문이 없습니다. 입력값을 확인해 주세요.');
      replaceDraft(result.data.config, before, '기본 설정 생성', () => isCurrent(target) && generation === previewGeneration
        && identity === policyIdentity() && signature === dataSignature(templateData(target)));
    } catch (error) { if (isCurrent(target) && generation === previewGeneration) notice(error.message); }
  }

  async function loadCurrent() {
    const target = form(), serverId = selectedServer(), policy = element('ng-policy')?.value.trim();
    if (!target || !serverId || !policy) { notice('WEB 서버와 정책 이름을 먼저 입력해 주세요.'); return; }
    const identity = policyIdentity(), generation = ++currentGeneration;
    const status = element('nginx-current-status');
    if (status) status.textContent = '읽는 중';
    try {
      const response = await fetch(`/api/v1/nginx-changes/servers/${encodeURIComponent(serverId)}/configuration?policyName=${encodeURIComponent(policy)}`);
      const result = await nginxResult(response);
      if (!isCurrent(target) || generation !== currentGeneration || identity !== policyIdentity()) return;
      const data = result.data || {};
      currentSnapshot = {identity, exists: data.exists === true, config: String(data.config || ''), sha256: String(data.sha256 || ''), editorSettings: data.editorSettings || null};
      element('nginx-current-editor').value = currentSnapshot.config;
      status.textContent = currentSnapshot.exists ? '실제 관리 파일 읽기 완료' : '이 정책의 관리 파일 없음';
      element('nginx-current-sha').textContent = currentSnapshot.exists ? `SHA-256 ${currentSnapshot.sha256}` : '';
      element('nginx-current-config').open = true;
    } catch (error) {
      if (!isCurrent(target) || generation !== currentGeneration) return;
      currentSnapshot = null;
      element('nginx-current-editor').value = '';
      element('nginx-current-sha').textContent = '';
      status.textContent = '읽기 실패 · 현재 설정 미확인';
      notice(error.message);
    }
  }

  function useCurrent() {
    if (!currentSnapshot || currentSnapshot.identity !== policyIdentity()) { notice('선택한 서버·정책의 현재 설정을 먼저 읽어 주세요.'); return; }
    if (!currentSnapshot.exists) { notice('현재 관리 파일이 없습니다. 입력값으로 기본 설정을 생성해 주세요.'); return; }
    const snapshot=currentSnapshot, target=form(), signature=dataSignature(templateData(target));
    replaceDraft(snapshot.config, draft()?.value || '', '현재 설정으로 편집 시작',
      () => snapshot===currentSnapshot && snapshot.identity===policyIdentity() && isCurrent(target)
        && signature===dataSignature(templateData(target)),
      () => applyCurrentSettings(snapshot.editorSettings,target));
  }

  function applyCurrentSettings(settings,target) {
    if (!settings) {
      const details=target.querySelector?.('.nginx-template-settings');if(details)details.open=true;
      notice('현재 원문과 일치하는 적용 이력의 입력값이 없습니다. 원문은 불러왔으며 서비스 방식·도메인·포트·WAS 또는 VIP를 직접 확인해 주세요.','warning');
      return;
    }
    // Explicit allowlist: never hydrate uploaded files, rawConfig, server identity, or hidden security inputs.
    const names=['policyType','serverName','listenPort','webRoot','apiProxyEnabled','apiPrefix','websocketEnabled',
      'tlsEnabled','tls12','tls13','http2Enabled','redirectHttp','hstsEnabled','clientMaxBodyMb',
      'proxyReadTimeoutSec','proxySendTimeoutSec','proxyBuffering','securityHeaders','allowedCidrs',
      'certificateName','upstreamMaxFails','upstreamFailTimeoutSec','proxyConnectTimeoutSec'];
    names.forEach(name=>{const input=target.elements[name];if(!input || !Object.hasOwn(settings,name))return;
      if(input.type==='checkbox'||typeof input.checked==='boolean'&&typeof settings[name]==='boolean')input.checked=settings[name]===true;
      else input.value=settings[name]==null?'':String(settings[name]);});
    const vip=element('ng-was-vip');if(vip)vip.value=settings.wasVipSelection||'';
    const root=element('ng-upstream-rows'), template=element('ng-upstream-row-template');
    if(root&&template) {
      root.replaceChildren();
      const endpoints=Array.isArray(settings.upstreams)&&settings.upstreams.length?settings.upstreams:[{}];
      endpoints.forEach(endpoint=>{const fragment=template.content.cloneNode(true);
        fragment.querySelector('[data-upstream-server]').value=endpoint.serverId==null?'':String(endpoint.serverId);
        fragment.querySelector('[data-upstream-port]').value=String(endpoint.port||8080);root.appendChild(fragment);});
    }
    if(typeof toggleTlsFields==='function')toggleTlsFields();
    if(typeof toggleNginxPolicyType==='function')toggleNginxPolicyType();
    if(typeof syncNginxUpstreams==='function')syncNginxUpstreams();
    // Existing custom select buttons do not observe native value/disabled property changes.
    target.querySelectorAll?.('select').forEach(select=>{
      if(window.DeploySelect?.sync)window.DeploySelect.sync(select);
      if(select._cs?.btn)select._cs.btn.disabled=select.disabled;
    });
    if(settings.upstreamsResolved===false || settings.wasVipSelection && vip?.value!==settings.wasVipSelection)
      notice('원문과 기본 입력값을 불러왔습니다. 현재 등록 정보와 연결할 WAS 또는 VIP를 다시 선택해 주세요.','warning');
    else notice('','success');
  }

  function serverBlock(text, caret) {
    // Preserve positions while masking quoted strings/comments and escaped characters.
    let masked = '', quote = '', comment = false;
    for (let index = 0; index < text.length; index++) {
      const char = text[index];
      if (comment) { masked += char === '\n' ? '\n' : ' '; if (char === '\n') comment = false; continue; }
      if (char === '\\') { masked += ' '; if (index + 1 < text.length) {masked += ' '; index++;} continue; }
      if (quote) { if (char === quote) quote = ''; masked += char === '\n' ? '\n' : ' '; continue; }
      if (char === '"' || char === "'") { quote = char; masked += ' '; continue; }
      if (char === '#') { comment = true; masked += ' '; continue; }
      masked += char;
    }
    const blocks = [], matcher = /\bserver\s*\{/g;
    let match;
    while ((match = matcher.exec(masked))) {
      const start = masked.indexOf('{', match.index);
      let depth = 1;
      for (let index = start + 1; index < masked.length; index++) {
        if (masked[index] === '{') depth++;
        else if (masked[index] === '}' && --depth === 0) { blocks.push({start,end:index,masked}); matcher.lastIndex=index+1; break; }
      }
    }
    return blocks.find(block => caret > block.start && caret <= block.end)
      || blocks.findLast(block => /\blocation\b/.test(masked.slice(block.start,block.end)))
      || blocks.at(-1) || null;
  }

  function proxyBlock(text, server, caret) {
    const matcher = /\blocation\b[^{;]*\{/g;
    matcher.lastIndex = server.start;
    const candidates = [];
    let match;
    while ((match = matcher.exec(server.masked)) && match.index < server.end) {
      const start = server.masked.indexOf('{',match.index);
      let depth=1;
      for(let index=start+1;index<server.end;index++) {
        if(server.masked[index]==='{')depth++;
        else if(server.masked[index]==='}' && --depth===0) {
          if(/\bproxy_pass\s/.test(server.masked.slice(start,index))) candidates.push({start,end:index});
          matcher.lastIndex=index+1;break;
        }
      }
    }
    return candidates.find(block=>caret>block.start&&caret<=block.end)||candidates[0]||null;
  }

  function blockRange(text, masked, expression) {
    expression.lastIndex=0;
    let match;
    while((match=expression.exec(text))) {
      if(!/\S/.test(masked.slice(match.index,match.index+8)))continue;
      const start=text.indexOf('{',match.index);
      let depth=1;
      for(let index=start+1;index<text.length;index++) {
        if(masked[index]==='{')depth++;
        else if(masked[index]==='}'&&--depth===0)return {begin:match.index,start,end:index+1};
      }
    }
    return null;
  }

  function rootLocation(text, server) {
    const offset=server.start+1;
    const range=blockRange(text.slice(offset,server.end),server.masked.slice(offset,server.end),/\blocation\s+(?:=\s+)?(?:\/|"\/"|'\/')\s*\{/g);
    return range?{begin:range.begin+offset,start:range.start+offset,end:range.end+offset}:null;
  }

  async function insertStructuredFeature(feature) {
    const target=form(), editor=draft();
    if(!target||!editor)return;
    if(!serverBlock(editor.value,editor.selectionStart)){notice('기본 설정을 생성하거나 현재 설정을 먼저 불러와 주세요.');return;}
    const originalMode=['policyType','tlsEnabled','tls12','tls13'].map(name=>({name,value:target.elements[name]?.value,checked:target.elements[name]?.checked}));
    const updateMode=()=>{if(typeof toggleTlsFields==='function')toggleTlsFields();if(typeof toggleNginxPolicyType==='function')toggleNginxPolicyType();};
    const applyMode=()=>{
      if(feature==='tls')['tlsEnabled','tls12','tls13'].forEach(name=>{if(target.elements[name])target.elements[name].checked=true;});
      else target.elements.policyType.value=feature==='reverse-proxy'?'REVERSE_PROXY':feature==='static-spa'?'STATIC_SPA':'STATIC_FILES';
      updateMode();
    };
    const restoreMode=()=>{originalMode.forEach(({name,value,checked})=>{const input=target.elements[name];if(input){input.value=value;if(typeof checked==='boolean')input.checked=checked;}});updateMode();};
    const before=editor.value, identity=policyIdentity(), generation=++previewGeneration;
    try {
      let data;
      try{applyMode();data=templateData(target);}finally{restoreMode();}
      const signature=dataSignature(templateData(target));
      const response=await fetch('/api/v1/nginx-changes/preview',{method:'POST',headers:nginxCsrfHeaders(),body:data});
      const result=await nginxResult(response);
      if(!isCurrent(target)||generation!==previewGeneration)return;
      if(identity!==policyIdentity()||signature!==dataSignature(templateData(target))||editor.value!==before){notice('입력값이나 변경안이 바뀌어 기능을 추가하지 않았습니다. 작성한 내용은 유지했습니다.');return;}
      const generated=String(result.data?.config||''), generatedServer=serverBlock(generated,0);
      if(!generatedServer)throw new Error('생성된 설정문을 확인할 수 없습니다.');
      let next=before;
      if(feature==='tls') {
        const selected=serverBlock(next,editor.selectionStart);
        const directives=/^[ \t]*(?:listen|ssl_certificate|ssl_certificate_key|ssl_protocols)[ \t]+[^;\n]*;[ \t]*\n?/gm;
        const lines=generated.slice(generatedServer.start+1,generatedServer.end).match(directives)||[];
        const rest=next.slice(selected.start+1,selected.end).replace(directives,'');
        next=next.slice(0,selected.start+1)+'\n'+lines.join('')+rest+next.slice(selected.end);
        const redirect=generated.match(/server\s*\{\s*listen\s+80;[^{}]*return\s+301[^;]+;\s*\}/);
        if(redirect&&!/\blisten\s+80(?:\s|;)/.test(next))next=redirect[0]+'\n'+next;
      } else {
        const generatedLocation=rootLocation(generated,generatedServer);
        if(!generatedLocation)throw new Error('생성된 기본 location 설정을 찾지 못했습니다.');
        const name='deploy_policy_'+(target.elements.policyName?.value||element('ng-policy')?.value||'').replace(/[^A-Za-z0-9]/g,'_');
        const generatedGroup=blockRange(generated,generatedServer.masked,new RegExp('\\bupstream\\s+'+name+'\\s*\\{','g'));
        const oldServer=serverBlock(next,0), oldGroup=blockRange(next,oldServer.masked,new RegExp('\\bupstream\\s+'+name+'\\s*\\{','g'));
        if(generatedGroup) {
          const group=generated.slice(generatedGroup.begin,generatedGroup.end);
          next=oldGroup?next.slice(0,oldGroup.begin)+group+next.slice(oldGroup.end):group+'\n'+next;
        } else if(oldGroup)next=next.slice(0,oldGroup.begin)+next.slice(oldGroup.end);
        let current=serverBlock(next,0);
        if(feature==='reverse-proxy'&&!/\bset\s+\$deploy_request_host\s/.test(next.slice(current.start,current.end))) {
          const setup='\n    set $deploy_request_host $http_host;\n    if ($deploy_request_host = "") { set $deploy_request_host $host; }\n';
          next=next.slice(0,current.start+1)+setup+next.slice(current.start+1);current=serverBlock(next,0);
        }
        const existing=rootLocation(next,current), replacement=generated.slice(generatedLocation.begin,generatedLocation.end);
        next=existing?next.slice(0,existing.begin)+replacement+next.slice(existing.end):next.slice(0,current.end)+'\n'+replacement+'\n'+next.slice(current.end);
      }
      if(bytes(next)>MAX_BYTES)throw new Error('기능 추가 후 설정문이 UTF-8 기준 60KB를 초과합니다.');
      openConfirm('기능 설정 반영',feature==='tls'?'선택한 포트의 SSL listen·정책 인증서 지시문을 반영합니다. 인증서와 개인키 업로드가 필요합니다.':'선택한 서비스 방식의 기본 location과 관리 WAS 목록을 반영합니다. 그 밖의 수동 편집 내용은 유지합니다.',()=>{
        if(!isCurrent(target)||identity!==policyIdentity()||signature!==dataSignature(templateData(target))||editor.value!==before){notice('입력값이나 변경안이 바뀌어 반영하지 않았습니다.');return;}
        applyMode();editor.value=next;updateStatus();editor.focus();
        if(feature==='tls'){const settings=element('ng-tls-files')?.closest('details');if(settings)settings.open=true;notice('TLS 설정문을 반영했습니다. 인증서와 개인키 파일을 확인해 주세요.','success');}
        else notice('선택한 기능의 설정문을 반영했습니다.','success');
      });
    } catch(error) {
      if(isCurrent(target)&&generation===previewGeneration){const settings=target.querySelector?.('.nginx-template-settings');if(settings)settings.open=true;notice(error.message);}
    }
  }

  function featureText(feature, scopeText) {
    const target = form();
    const value = name => target?.elements[name]?.value || '';
    const number = (name, fallback) => /^\d+$/.test(value(name)) ? value(name) : String(fallback);
    if (feature === 'websocket') {
      const version = /\bproxy_http_version\s+([^;\s]+)\s*;/.exec(scopeText)?.[1];
      if (version && version !== '1.1') throw new Error('기존 proxy_http_version을 1.1로 변경한 뒤 WebSocket을 추가해 주세요.');
      return '  # WebSocket\n' + (/\bproxy_http_version\s+1\.1\s*;/.test(scopeText) ? '' : '  proxy_http_version 1.1;\n') + '  proxy_set_header Upgrade $http_upgrade;\n  proxy_set_header Connection "upgrade";\n';
    }
    if (feature === 'security-headers') return '  add_header X-Content-Type-Options nosniff always;\n  add_header X-Frame-Options SAMEORIGIN always;\n  add_header Referrer-Policy strict-origin-when-cross-origin always;\n';
    if (feature === 'timeouts') return `  proxy_connect_timeout ${number('proxyConnectTimeoutSec',3)}s;\n  proxy_read_timeout ${number('proxyReadTimeoutSec',300)}s;\n  proxy_send_timeout ${number('proxySendTimeoutSec',300)}s;\n`;
    if (feature === 'upload') return `  client_max_body_size ${number('clientMaxBodyMb',50)}m;\n`;
    if (feature === 'gzip') return '  gzip on;\n  gzip_vary on;\n  gzip_types text/plain text/css application/javascript application/json image/svg+xml;\n';
    throw new Error('추가할 기능을 선택해 주세요.');
  }

  function insertFeature() {
    const editor = draft();
    if (!editor) return;
    try {
      const feature = element('nginx-config-feature')?.value;
      if (['tls','reverse-proxy','static-spa','static-files'].includes(feature)) return insertStructuredFeature(feature);
      let start = editor.selectionStart, end = editor.selectionEnd;
      let block = serverBlock(editor.value,start);
      if(block && (feature==='websocket'||feature==='timeouts')) block=proxyBlock(editor.value,block,start)||block;
      const scopeText=block?editor.value.slice(block.start+1,block.end):'';
      const snippet = featureText(feature,scopeText);
      if(start===end) {
        const duplicate = {
          'websocket':/\bproxy_set_header\s+(?:Upgrade|Connection)\s/,
          'upload':/\bclient_max_body_size\s/,
          'timeouts':/\bproxy_(?:connect|read|send)_timeout\s/,
          'security-headers':/\badd_header\s+(?:X-Content-Type-Options|X-Frame-Options|Referrer-Policy)\s/,
          'gzip':/\bgzip\s/
        }[feature];
        if(duplicate?.test(scopeText)) throw new Error('이 기능의 지시문이 이미 있습니다. 기존 값을 수정하거나 교체할 영역을 선택한 뒤 추가해 주세요.');
      }
      if (start === end && (!block || start <= block.start || start > block.end)) {
        if (!block) throw new Error('기본 설정을 생성하거나 server 블록을 먼저 작성해 주세요.');
        start = end = block.end;
      }
      const insertion = `${start > 0 && editor.value[start - 1] !== '\n' ? '\n' : ''}${snippet}`;
      const next = editor.value.slice(0,start) + insertion + editor.value.slice(end);
      if (bytes(next) > MAX_BYTES) throw new Error('추가하면 설정문이 UTF-8 기준 60KB를 초과합니다.');
      editor.value = next;
      editor.focus();
      editor.setSelectionRange(start + insertion.length,start + insertion.length);
      updateStatus();
      notice('기능 지시문을 추가했습니다. 기존 지시문과의 중복을 확인해 주세요.', 'success');
    } catch (error) { notice(error.message); }
  }

  async function showRequest(id) {
    const modal = element('nginx-config-viewer-modal');
    if (!modal) return;
    const generation = ++requestGeneration;
    element('nginx-config-viewer-title').textContent = '승인 요청 설정 원문';
    modal.style.display = 'flex';
    element('nginx-request-config').value = '';
    element('nginx-request-config-sha').textContent = '';
    element('nginx-request-config-status').textContent = `요청 #${id} 설정문을 읽는 중입니다.`;
    try {
      const response = await fetch(`/api/v1/nginx-changes/${encodeURIComponent(id)}/config`);
      const result = await nginxResult(response);
      if (!modal.isConnected || generation !== requestGeneration) return;
      element('nginx-request-config').value = String(result.data?.config || '');
      element('nginx-request-config-sha').textContent = `SHA-256 ${result.data?.sha256 || ''}`;
      element('nginx-request-config-status').textContent = result.data?.legacyTyped ? `요청 #${id} · 기존 구조화 정책에서 확인한 설정문` : `요청 #${id} · 승인 요청에 저장된 설정문 (읽기 전용)`;
    } catch (error) { if (modal.isConnected && generation === requestGeneration) element('nginx-request-config-status').textContent = error.message; }
  }

  async function showCurrentRequest(button) {
    const modal = element('nginx-config-viewer-modal');
    if (!modal) return;
    const generation = ++requestGeneration;
    modal.style.display = 'flex';
    element('nginx-config-viewer-title').textContent = '현재 WEB 서버에 적용된 설정';
    element('nginx-request-config').value = '';
    element('nginx-request-config-sha').textContent = '';
    element('nginx-request-config-status').textContent = '실제 관리 파일을 읽는 중입니다.';
    try {
      const response = await fetch(`/api/v1/nginx-changes/servers/${encodeURIComponent(button.dataset.serverId)}/configuration?policyName=${encodeURIComponent(button.dataset.policyName)}`);
      const result = await nginxResult(response);
      if (!modal.isConnected || generation !== requestGeneration) return;
      element('nginx-request-config').value = String(result.data?.config || '');
      element('nginx-request-config-sha').textContent = result.data?.exists ? `SHA-256 ${result.data.sha256 || ''}` : '';
      element('nginx-request-config-status').textContent = result.data?.exists ? `${button.dataset.policyName} · 현재 실제 관리 파일 (읽기 전용)` : `${button.dataset.policyName} · 현재 관리 파일 없음`;
    } catch (error) { if (modal.isConnected && generation === requestGeneration) element('nginx-request-config-status').textContent = error.message; }
  }

  Object.assign(window, {
    updateNginxEditorStatus: updateStatus,
    validateNginxEditorDraft: validateDraft,
    generateNginxEditorTemplate: generateTemplate,
    loadCurrentNginxConfiguration: loadCurrent,
    useCurrentNginxConfiguration: useCurrent,
    insertNginxEditorFeature: insertFeature,
    showNginxRequestConfig: showRequest,
    showCurrentNginxRequestConfig: showCurrentRequest,
    closeNginxRequestConfig: () => { requestGeneration++; const modal = element('nginx-config-viewer-modal'); if(modal) modal.style.display='none'; }
  });
  const editorForm = form();
  if (editorForm?.addEventListener) editorForm.addEventListener('invalid', event => {
    const input = event.target;
    const settings = input.closest('details');
    if (settings) settings.open = true;
    const button = input._cs?.btn;
    if (!button) return;
    // The native select remains required; cancel only its invisible browser tooltip.
    // Delegation also covers upstream rows added after this form was initialized.
    event.preventDefault();
    button.setAttribute('aria-invalid', 'true');
    if (editorForm.querySelector(':invalid') !== input) return;
    const label = input.getAttribute('aria-label') || input.labels?.[0]?.textContent?.trim() || '필수 선택 항목';
    notice(`${label}: 값을 선택해 주세요.`);
    button.focus();
  }, true);
  if (editorForm?.addEventListener) editorForm.addEventListener('change', event => {
    if (event.target.validity?.valid) event.target._cs?.btn?.removeAttribute('aria-invalid');
  });
}());
