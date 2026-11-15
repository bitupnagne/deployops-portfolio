(function () {
  'use strict';
  let active = null;
  const labels = { NOT_REGISTERED: '미등록', AWAITING_ENROLLMENT: '설치 등록 대기', PENDING: '관리자 승인 대기', ACTIVE: '등록 승인됨', REVOKED: '등록 해제됨' };
  const taskLabels = {QUEUED:'전달 대기',CLAIMED:'수신됨',RUNNING:'실행 중',SUCCEEDED:'완료',FAILED:'실패',UNKNOWN:'결과 확인 필요',CANCELLED:'취소',RESOLVED_UNKNOWN:'관리자 확인 종료'};
  function readinessText(value) {
    if (!value || typeof value !== 'object') return '연결·준비 검사를 실행해 주세요.';
    const checks=value.checks || {};
    const parts=[value.ready === true ? '배포 준비됨' : '준비 확인 필요'];
    if(checks.docker) parts.push(checks.docker.ready
      ? `Docker ${checks.docker.version || '정상'}`
      : value.role === 'WEB' ? 'Docker 미준비 (nginx 기반 WEB 배포에는 불필요)' : 'Docker 확인 필요');
    if(checks.nginx) parts.push(`nginx ${checks.nginx.ready ? '실행·설정 정상' : '실행 또는 설정 확인 필요'}`);
    if(checks.helpers) parts.push(checks.helpers.ready ? '필수 제어 기능 정상' : '제어 기능 설치 확인 필요');
    return parts.join(' · ');
  }
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
  async function api(path, method = 'GET', value) {
    const headers = { 'Content-Type': 'application/json' };
    const csrf = document.querySelector('meta[name="_csrf"]');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    if (csrf && csrfHeader) headers[csrfHeader.content] = csrf.content;
    const response = await fetch(path, { method, headers, body: value === undefined ? undefined : JSON.stringify(value) });
    const body = await response.json();
    if (!response.ok || body.success === false) throw new Error(body.message || '에이전트 요청을 처리하지 못했습니다.');
    return body.data;
  }
  function endpoint(state, suffix = '') { return `/api/v1/servers/${state.id}/agent${suffix}`; }
  function toggleFields(prefix) {
    const mode = document.getElementById(`${prefix}-control`);
    if (!mode) return;
    mode.value = 'AGENT';
  }
  function close() {
    if (!active) return;
    const state = active; active = null;
    state.host.remove();
    if (state.opener && state.opener.isConnected) state.opener.focus();
  }
  async function refresh(state) {
    const sequence = state.refreshSequence = (state.refreshSequence || 0) + 1;
    const data = await api(endpoint(state));
    if (active !== state || sequence !== state.refreshSequence) return;
    state.data = data;
    const busy = data.busyTask;
    const connected = ['PENDING','ACTIVE'].includes(data.registrationState) && data.connected;
    const preparation = busy ? (busy.state === 'UNKNOWN' ? '실행 결과 확인 필요 · 상태 확인 전에는 배포하지 않습니다.' : '작업 진행 중 · 완료 후 다시 검사해 주세요.')
      : data.registrationState !== 'ACTIVE' ? '등록 승인이 필요합니다.'
      : !connected ? '에이전트 연결을 확인해 주세요.' : readinessText(data.readiness);
    const field = state.host.querySelector('[data-agent-information]');
    const items = [
      ['등록', labels[data.registrationState] || data.registrationState || '미등록'],
      ['연결', connected ? '연결됨' : '연결 대기 또는 끊김'],
      ['마지막 응답', data.lastSeenAt || '-'], ['호스트', data.hostname || '-'],
      ['유형 / 버전', `${data.role || '-'} / ${data.version || '-'}`], ['서버 식별', data.machineId || '-'],
      ['준비 상태', preparation]
    ];
    field.innerHTML = items.map(([name, value]) => `<dt>${esc(name)}</dt><dd>${esc(value)}</dd>`).join('');
    state.host.querySelector('[data-agent-task]').textContent = busy ? `작업 ${busy.id} · ${taskLabels[busy.state] || busy.state}` : '진행 중인 작업 없음';
    state.host.querySelector('[data-agent-history]').innerHTML = (data.recentTasks || []).map(task => `<tr><td class="mono">${esc(task.id)}<br>${esc(task.kind)}</td><td>${esc(taskLabels[task.state] || task.state)}</td><td>${esc(task.finishedAt || '-')}</td><td>${esc(task.exitCode == null ? '-' : task.exitCode)}</td></tr>`).join('') || '<tr><td colspan="4">아직 작업 기록이 없습니다.</td></tr>';
    state.host.querySelector('[data-agent-approve]').hidden = !state.admin || data.registrationState !== 'PENDING';
    state.host.querySelector('[data-agent-revoke]').hidden = !state.admin || !['ACTIVE','PENDING'].includes(data.registrationState);
    state.host.querySelector('[data-agent-resolve]').hidden = !state.admin || data.registrationState !== 'REVOKED' || !busy || !['UNKNOWN','UNCERTAIN'].includes(busy.state);
    state.host.querySelector('[data-agent-resume-query]').hidden = !state.admin || !busy || busy.readOnlyQueryRecoverable !== true;
    const codeButton = state.host.querySelector('[data-agent-code]');
    codeButton.disabled = data.registrationState === 'ACTIVE' || !!busy;
  }
  async function action(state, button, callback) {
    if (state.running) return;
    state.running = true;
    button.disabled = true;
    const result = state.host.querySelector('[data-agent-result]');
    result.textContent = '처리 중입니다…';
    try { await callback(result); }
    catch (error) { if (active === state) result.textContent = error.message; }
    finally { state.running = false; if (button.isConnected) button.disabled = false; }
  }
  function open(button) {
    close();
    const id = Number(button.dataset.agentServer);
    if (!Number.isSafeInteger(id) || id < 1) return;
    const host = document.createElement('div'); host.className = 'modal-overlay';
    host.innerHTML = `<div class="modal agent-modal" role="dialog" aria-modal="true" aria-labelledby="agent-dialog-title">
      <div class="modal-header"><h3 id="agent-dialog-title" class="modal-title">에이전트 · ${esc(button.dataset.agentName || id)}</h3><button type="button" class="modal-close" data-agent-close aria-label="닫기">×</button></div>
      <div class="modal-body"><p class="form-hint">서버의 에이전트가 Deploy에 연결해 승인된 작업을 수행합니다. 서버 로그인 비밀번호를 입력하지 않습니다.</p>
        <dl class="agent-information" data-agent-information><dt>상태</dt><dd>확인 중…</dd></dl>
        <p data-agent-task class="form-hint"></p><details><summary>최근 에이전트 작업</summary><div class="table-scroll"><table class="data-table"><thead><tr><th>작업</th><th>상태</th><th>완료</th><th>종료 코드</th></tr></thead><tbody data-agent-history></tbody></table></div></details>
        <div class="agent-actions"><button type="button" class="btn btn-ghost sm" data-agent-refresh>상태 새로고침</button><button type="button" class="btn btn-primary sm" data-agent-test>연결·준비 검사</button></div>
        <section data-agent-admin-section><h4>설치·등록</h4><p class="form-hint">등록 코드를 대상 서버의 root 전용 파일(권한 600)에 보관하고, 설치 응답파일의 DEPLOY_AGENT_ENROLLMENT_CODE_FILE에 그 파일 경로를 입력하세요. 설치 후 호스트와 유형을 확인하고 등록을 승인합니다. 코드는 한 번만 표시됩니다.</p>
          <button type="button" class="btn btn-ghost sm" data-agent-code>등록 코드 발급</button>
          <div data-agent-code-result hidden><label class="form-label" for="agent-registration-code">일회성 등록 코드</label><input id="agent-registration-code" class="form-input mono" readonly autocomplete="off"><p class="form-hint" data-agent-expiry></p></div>
          <div class="agent-actions"><button type="button" class="btn btn-primary sm" data-agent-approve hidden>등록 승인</button><button type="button" class="btn btn-ghost sm" data-agent-revoke hidden>등록 해제</button></div>
          <div data-agent-resume-query hidden><label class="form-label" for="agent-query-reason">읽기 전용 조회 재개 사유</label><input id="agent-query-reason" class="form-input" maxlength="500"><label class="agent-confirm"><input type="checkbox" data-agent-query-ack>미확정 상태 조회만 종료하며 백업·복원 작업을 성공 처리하거나 재실행하지 않음을 확인했습니다.</label><button type="button" class="btn btn-primary sm" data-agent-query-button>읽기 전용 조회 재개</button><p class="form-hint">고정 Nexus 결과 파일의 읽기·존재 검사에만 사용할 수 있습니다. 이후 백업·복원 화면에서 상태를 새로 조회하세요.</p></div>
          <div data-agent-resolve hidden><label class="form-label" for="agent-resolve-reason">결과 불명 작업 종료 사유</label><input id="agent-resolve-reason" class="form-input" maxlength="500"><label class="agent-confirm"><input type="checkbox" data-agent-stopped>해당 서버에서 이전 작업과 프로세스가 중지된 것을 직접 확인했습니다.</label><button type="button" class="btn btn-ghost sm" data-agent-resolve-button>결과 불명 작업 종료</button><p class="form-hint">작업 성공으로 처리하지 않습니다. 서버 상태를 확인하고 새 등록·배포를 진행하세요.</p></div>
        </section><p class="agent-result" data-agent-result role="status" aria-live="polite"></p><details data-agent-diagnostic-panel hidden><summary>상세 진단 결과</summary><pre class="agent-result" data-agent-diagnostic></pre></details>
      </div><div class="modal-footer"><button type="button" class="btn btn-ghost" data-agent-close>닫기</button></div></div>`;
    document.body.append(host);
    const state = { id, host, opener: button, admin: button.dataset.agentAdmin === 'true', running: false };
    active = state;
    host.querySelector('[data-agent-admin-section]').hidden = !state.admin;
    host.querySelector('[data-agent-test]').hidden = button.dataset.agentTestAllowed === 'false';
    host.querySelectorAll('[data-agent-close]').forEach(item => item.onclick = close);
    host.addEventListener('keydown', event => {
      if (event.key === 'Escape') close();
      if (event.key !== 'Tab') return;
      const controls = Array.from(host.querySelectorAll('button,input')).filter(item => !item.disabled && item.getClientRects().length);
      if (!controls.length) return;
      if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls[controls.length - 1].focus(); }
      else if (!event.shiftKey && document.activeElement === controls[controls.length - 1]) { event.preventDefault(); controls[0].focus(); }
    });
    const bind = (selector, callback) => { const item = host.querySelector(selector); item.onclick = () => action(state, item, callback); };
    bind('[data-agent-refresh]', async output => { await refresh(state); if (active === state) output.textContent = '상태를 갱신했습니다.'; });
    bind('[data-agent-test]', async output => {
      const data = await api(`/api/v1/servers/${id}/check`, 'POST');
      if (active !== state) return;
      output.textContent = data.message || (data.ready ? '배포 준비 검사를 통과했습니다.' : '준비 검사를 통과하지 못했습니다.');
      if (data.checks) { host.querySelector('[data-agent-diagnostic-panel]').hidden=false; host.querySelector('[data-agent-diagnostic]').textContent=typeof data.checks === 'string' ? data.checks : JSON.stringify(data.checks, null, 2); }
      await refresh(state);
    });
    bind('[data-agent-code]', async output => {
      const data = await api(endpoint(state, '/enrollment-code'), 'POST', {});
      if (active !== state) return;
      host.querySelector('[data-agent-code-result]').hidden = false;
      host.querySelector('#agent-registration-code').value = data.enrollmentCode;
      host.querySelector('[data-agent-expiry]').textContent = `만료: ${data.expiresAt || '-'} · 서버 ID: ${id}`;
      output.textContent = '코드를 발급했습니다. 대상 서버 설치에 사용하세요.'; await refresh(state);
    });
    bind('[data-agent-approve]', async output => {
      if (!state.data || !state.data.agentBindingId || !state.data.machineId) throw new Error('등록 정보를 새로 확인해 주세요.');
      await api(endpoint(state, '/approve'), 'POST', {agentBindingId:state.data.agentBindingId,machineId:state.data.machineId,role:state.data.role});
      if (active === state) { output.textContent = '등록을 승인했습니다. 연결·준비 검사를 진행하세요.'; await refresh(state); }
    });
    bind('[data-agent-revoke]', async output => {
      if (!window.confirm('이 서버의 에이전트 등록을 해제할까요? 실행 중인 작업을 중지시키는 동작은 아닙니다.')) return;
      await api(endpoint(state, '/revoke'), 'POST', {}); if (active === state) { output.textContent = '등록을 해제했습니다.'; await refresh(state); }
    });
    bind('[data-agent-query-button]', async output => {
      const reason = host.querySelector('#agent-query-reason').value.trim();
      if (!reason || !host.querySelector('[data-agent-query-ack]').checked) throw new Error('조회 재개 사유와 읽기 전용 작업 확인이 필요합니다.');
      if (!state.data?.busyTask?.readOnlyQueryRecoverable) throw new Error('작업 상태를 새로 확인해 주세요.');
      await api(endpoint(state, `/tasks/${state.data.busyTask.id}/resume-query`), 'POST', {reason, acknowledged: true});
      if (active === state) { await refresh(state); output.textContent = '미확정 조회를 종료했습니다. 백업·복원 화면에서 상태를 새로 조회하세요. 원래 작업을 성공 처리하거나 재실행하지 않았습니다.'; }
    });
    bind('[data-agent-resolve-button]', async output => {
      const reason = host.querySelector('#agent-resolve-reason').value.trim();
      if (!reason || !host.querySelector('[data-agent-stopped]').checked) throw new Error('이전 작업 중지 확인과 종료 사유를 입력해 주세요.');
      await api(endpoint(state, `/tasks/${state.data.busyTask.id}/resolve`), 'POST', {reason, executionStopped: true});
      if (active === state) { output.textContent = '결과 불명 작업을 종료했습니다. 실제 서비스 상태를 확인하세요.'; await refresh(state); }
    });
    refresh(state).catch(error => { if (active === state) host.querySelector('[data-agent-result]').textContent = error.message; });
    host.querySelector('[data-agent-close]').focus();
  }
  window.DeployAgent = { open, close, toggleFields };
})();
