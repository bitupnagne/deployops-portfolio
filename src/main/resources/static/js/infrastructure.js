(() => {
  'use strict';
  const server = document.getElementById('infra-server');
  const service = document.getElementById('infra-service');
  const message = document.getElementById('infra-message');
  const result = document.getElementById('infra-result');
  let polling;
  async function history() {
    const container=document.getElementById('infra-history');
    container.replaceChildren();
    if (!server.value) return;
    try {
      for (const item of await request('/' + server.value + '/jobs')) {
        const button=document.createElement('button');
        button.type='button'; button.className='btn btn-ghost';
        button.textContent=item.createdAt + ' · ' + item.action + ' · ' + item.state;
        button.addEventListener('click', () => { clearTimeout(polling); poll(item.taskId); });
        container.append(button);
      }
    } catch (error) { message.textContent=error.message; }
  }
  server.addEventListener('change', history);
  document.getElementById('infra-history-refresh').addEventListener('click', history);
  async function request(path, body) {
    const headers = {Accept: 'application/json'};
    if (body) {
      headers['Content-Type'] = 'application/json';
      headers[document.querySelector('meta[name="_csrf_header"]').content] = document.querySelector('meta[name="_csrf"]').content;
    }
    const response = await fetch('/api/v1/infrastructure' + path, {method: body ? 'POST' : 'GET', headers, body: body ? JSON.stringify(body) : undefined});
    const data = await response.json();
    if (!response.ok || data.success === false) throw new Error(data.message || '작업을 완료하지 못했습니다.');
    return data.data;
  }
  async function poll(id) {
    if (!document.getElementById('infra-result')) return;
    try {
      const state = await request('/jobs/' + encodeURIComponent(id));
      message.textContent = '작업 ' + id + ' · ' + state.state;
      result.textContent = state.stdout || '';
      if (['QUEUED', 'CLAIMED', 'RUNNING'].includes(state.state)) polling = setTimeout(() => poll(id), 2500);
      else document.querySelectorAll('[data-infra-action]').forEach(button => button.disabled = false);
    } catch (error) { message.textContent = error.message; document.querySelectorAll('[data-infra-action]').forEach(button => button.disabled = false); }
  }
  document.querySelectorAll('[data-infra-action]').forEach(button => button.addEventListener('click', async () => {
    const action = button.dataset.infraAction;
    const changes = !['status', 'logs'].includes(action);
    if (changes && !confirm(server.selectedOptions[0].textContent + ' / ' + service.selectedOptions[0].textContent + ' — ' + button.textContent + ' 작업을 진행할까요? 서비스 작업과 백업·복구에는 시간이 걸릴 수 있습니다.')) return;
    try {
      clearTimeout(polling);
      document.querySelectorAll('[data-infra-action]').forEach(control => control.disabled = true);
      const data = await request('/' + server.value + '/jobs', {action, service: service.value, confirmed: changes, lines: 200, backupId: document.getElementById('infra-backup-id').value.trim()});
      if (data.request.backupId) document.getElementById('infra-backup-id').value = data.request.backupId;
      await history();
      await poll(data.taskId);
    } catch (error) { message.textContent = error.message; document.querySelectorAll('[data-infra-action]').forEach(control => control.disabled = false); }
  }));
  document.getElementById('infra-nexus-bind')?.addEventListener('click', async () => {
    if (!confirm('선택한 서버에 Nexus 데이터와 백업 실행기가 설치되어 있는지 확인하셨습니까?')) return;
    try { await request('/nexus-binding', {serverId: Number(server.value)}); message.textContent = 'Nexus 실행 호스트를 연결했습니다.'; }
    catch (error) { message.textContent = error.message; }
  });
  request('/servers').then(rows => {
    for (const row of rows) {
      const option = document.createElement('option');
      option.value = row.id;
      option.textContent = row.name + ' · ' + row.ip + ' · ' + row.role + ' · ' + (row.registration.registrationState || '등록 필요');
      server.append(option);
    }
    if (!rows.length) message.textContent = '서버 관리에서 인프라 서버를 등록하고 에이전트를 승인해 주세요.';
    history();
  }).catch(error => message.textContent = error.message);
})();
