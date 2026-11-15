(function () {
  'use strict';
  let generation = 0, dialog;
  const element = (tag, text, className) => {
    const node = document.createElement(tag); if (text != null) node.textContent = text;
    if (className) node.className = className; return node;
  };
  function button(text, action) { const b = element('button', text, 'btn btn-ghost'); b.type = 'button'; b.onclick = action; return b; }
  function field(parent, text, type = 'text') {
    const label = element('label', null, 'form-group storage-field');
    if (type === 'checkbox') label.className += ' storage-checkbox';
    label.append(element('span', text, 'form-label'));
    const input = element(type === 'select' ? 'select' : 'input', null, type === 'select' ? 'form-select' : 'form-input');
    // This dialog changes options and disabled state after async loads. The shared
    // select enhancer snapshots them only at creation; retain the native control.
    if (type === 'select') input.setAttribute('data-no-custom-select', '');
    if (type !== 'select') input.type = type;
    label.append(input); parent.append(label); return input;
  }
  function option(select, value, text) { const node = element('option', text); node.value = value; select.append(node); }
  function shell(title) {
    if (dialog) dialog.remove(); const token = ++generation;
    dialog = element('dialog', null, 'storage-dialog'); dialog.setAttribute('aria-label', title);
    Object.assign(dialog.style, { position: 'fixed', inset: '0', margin: 'auto', width: 'min(920px, calc(100vw - 28px))', maxHeight: '90vh', overflow: 'auto', border: '1px solid var(--border)', borderRadius: '12px', padding: '24px', background: 'var(--surface, #fff)', color: 'var(--text, #172b4d)' });
    const head = element('div', null, 'page-header'); head.append(element('h2', title, 'page-title'), button('닫기', () => dialog.close()));
    const message = element('p', '', 'notice-box'); message.setAttribute('role', 'status'); message.setAttribute('aria-live', 'polite');
    const body = element('div', null, 'storage-dialog-body');
    const style = element('style', `
      .storage-dialog { box-sizing:border-box; }
      .storage-dialog::backdrop { background:rgba(15,23,42,.42); }
      .storage-dialog .page-header { display:flex; align-items:center; gap:16px; margin:0 0 18px; }
      .storage-dialog .page-title { margin:0; flex:1; }
      .storage-dialog .notice-box { margin:0 0 18px; overflow-wrap:anywhere; }
      .storage-dialog .storage-field { display:grid; grid-template-columns:minmax(0,1fr); gap:7px; min-width:0; margin:0 0 16px; }
      .storage-dialog .storage-field .form-label { display:block; margin:0; }
      .storage-dialog .storage-field input:not([type=checkbox]), .storage-dialog .storage-field select { display:block; width:100%; min-width:0; box-sizing:border-box; }
      .storage-dialog .storage-checkbox { display:flex; flex-direction:row; align-items:center; justify-content:flex-start; text-align:left; gap:10px; min-height:38px; }
      .storage-dialog .storage-checkbox input { width:auto; flex:none; margin:0; }
      .storage-dialog-body > .btn { margin:6px 8px 0 0; }
      .storage-dialog .storage-server-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); column-gap:20px; row-gap:0; }
      .storage-dialog .storage-server-grid > p { grid-column:1/-1; margin:0 0 18px; line-height:1.6; }
      .storage-dialog .storage-server-grid > .btn { margin:4px 0 0; }
      .storage-dialog .storage-server-grid > .storage-checkbox { align-self:end; margin-bottom:16px; }
      .storage-dialog fieldset { min-width:0; }
      .storage-dialog pre { overflow-wrap:anywhere; }
      @media(max-width:640px) { .storage-dialog { padding:18px !important; max-height:94vh !important; } .storage-dialog .storage-server-grid { grid-template-columns:minmax(0,1fr); } .storage-dialog-body > .btn { max-width:100%; white-space:normal; } }
    `);
    dialog.append(head, message, body, style); document.body.append(dialog);
    dialog.addEventListener('close', () => { if (generation === token) ++generation; });
    dialog.showModal(); return { token, body, message, current: () => token === generation && dialog.open };
  }
  async function api(path, method = 'GET', body) {
    const headers = { 'Content-Type': 'application/json' };
    if (method !== 'GET') {
      const name = document.querySelector('meta[name="_csrf_header"]')?.content, value = document.querySelector('meta[name="_csrf"]')?.content;
      if (!name || !value) throw new Error('로그인 상태를 새로 확인해 주세요.'); headers[name] = value;
    }
    const response = await fetch(path, { method, headers, body: body == null ? undefined : JSON.stringify(body), cache: 'no-store' });
    const result = await response.json(); if (!response.ok || !result.success) throw new Error(result.message || '저장소 요청을 처리하지 못했습니다.');
    return result.data;
  }
  async function run(state, task) {
    if (state.busy) return;
    state.busy = true;
    const controls = [...state.body.querySelectorAll('button'), ...(state.contextControls || [])]; const old = controls.map(b => b.disabled);
    controls.forEach(b => b.disabled = true); state.message.textContent = '처리 중입니다…';
    try { await task(); } catch (error) { if (state.current()) state.message.textContent = error.message; }
    finally { state.busy = false; if (state.current()) { controls.forEach((b, i) => b.disabled = old[i]); state.syncControls?.(); } }
  }
  async function openServer(id) {
    if (!/^[1-9][0-9]*$/.test(String(id))) return;
    const state = shell('WAS 허용 저장소');
    state.body.className += ' storage-server-grid';
    state.body.append(element('p', 'NAS는 OS에서 먼저 마운트하세요. 여기서는 이미 존재하는 경로와 실제 마운트 원본을 등록합니다. 자격 증명과 OS 마운트는 변경하지 않습니다.', 'form-hint'));
    const list = field(state.body, '등록된 저장소', 'select'); option(list, '', '새 저장소');
    const key = field(state.body, '공통 저장소 키 (예: shared-data)'); key.maxLength = 64;
    const kind = field(state.body, '종류', 'select'); option(kind, 'NAS', 'NAS'); option(kind, 'LOCAL', '로컬 디렉터리');
    const root = field(state.body, '서버의 기존 절대 경로'); root.placeholder = '/mnt/company-data';
    const target = field(state.body, '확인된 마운트 지점'), source = field(state.body, '확인된 마운트 원본'), fs = field(state.body, '파일시스템');
    [target, source, fs].forEach(input => input.readOnly = true);
    const enabled = field(state.body, '저장소 사용', 'checkbox'); enabled.checked = true;
    const reason = field(state.body, '변경 사유'); reason.maxLength = 400;
    let locations = [];
    const clearIdentity = () => { target.value = ''; source.value = ''; fs.value = ''; };
    root.addEventListener('input', clearIdentity); kind.addEventListener('change', clearIdentity);
    list.onchange = () => {
      const value = locations.find(l => l.storageKey === list.value); key.value = value?.storageKey || ''; key.readOnly = !!value;
      kind.value = value?.kind || 'NAS'; root.value = value?.rootPath || ''; target.value = value?.mountTarget || ''; source.value = value?.mountSource || ''; fs.value = value?.fsType || ''; enabled.checked = value?.enabled ?? true;
    };
    state.body.append(button('서버에서 경로·마운트 확인', () => run(state, async () => {
      const expectedRoot = root.value, expectedKind = kind.value;
      const value = await api(`/api/v1/servers/${id}/storage/inspect`, 'POST', { rootPath: expectedRoot, kind: expectedKind });
      if (!state.current() || root.value !== expectedRoot || kind.value !== expectedKind) return;
      target.value = value.mountTarget; source.value = value.mountSource; fs.value = value.fsType;
      state.message.textContent = '현재 마운트 원본을 확인했습니다. 등록 후 프로젝트에서 UID:GID 접근 권한도 검사하세요.';
    })));
    state.body.append(button('허용 저장소 저장', () => run(state, async () => {
      if (!target.value || !source.value || !fs.value) throw new Error('먼저 서버에서 경로·마운트를 확인해 주세요.');
      await api(`/api/v1/servers/${id}/storage`, 'PUT', { storageKey: key.value, kind: kind.value, rootPath: root.value, mountTarget: target.value, mountSource: source.value, fsType: fs.value, enabled: enabled.checked, reason: reason.value });
      if (!state.current()) return; await reload(); if (state.current()) state.message.textContent = '허용 저장소를 저장했습니다. 사용 해제·원본 변경은 기존 승인과 재기동을 차단할 수 있습니다.';
    })));
    async function reload() {
      const values = await api(`/api/v1/servers/${id}/storage`); if (!state.current()) return;
      locations = values; const selected = key.value; list.replaceChildren(); option(list, '', '새 저장소');
      locations.forEach(l => option(list, l.storageKey, `${l.storageKey} · ${l.rootPath}${l.enabled ? '' : ' (사용 해제)'}`));
      list.value = selected; if (!list.value) list.value = '';
    }
    await run(state, async () => { await reload(); if (state.current()) state.message.textContent = '동일 저장소 키를 대상 WAS마다 등록하면 서버별 실제 경로를 연결할 수 있습니다.'; });
  }
  async function openProject(id) {
    if (!/^[1-9][0-9]*$/.test(String(id))) return;
    const state = shell('프로젝트 WAS 저장소 연결');
    state.body.append(element('p', '새 배포 승인 때 설정이 고정됩니다. 파일 내용은 롤백하지 않습니다. 마운트 컨테이너는 재부팅 때 저장소 확인 후 기동하며, 실행 중 NAS 장애를 계속 감시하는 기능은 아닙니다. 여러 WAS의 동시 쓰기는 애플리케이션에서 제어하세요.', 'form-hint'));
    const env = field(state.body, '환경', 'select'); option(env, 'TEST', 'TEST'); option(env, 'PROD', 'PROD');
    const component = field(state.body, 'WAS 배포 단위', 'select'), inventory = element('pre', '', 'form-hint'), rows = element('div');
    inventory.style.whiteSpace = 'pre-wrap'; state.body.append(inventory, rows);
    const reason = field(state.body, '변경 사유'); reason.maxLength = 400;
    const server = field(state.body, '경로·권한 검사 대상 WAS', 'select');
    let view, context = 0, loadedEnvironment = null;
    const ready = () => view?.canManage && loadedEnvironment === env.value;
    function addRow(value = {}) {
      const row = element('fieldset', null, 'section-card'); row.style.padding = '12px'; row.style.marginBottom = '12px'; row.append(element('legend', '마운트'));
      const key = field(row, '저장소 키', 'select');
      const lists = Object.values(view?.locations || {});
      const keys = lists.length ? lists[0].map(l => l.storageKey).filter(k => lists.every(list => list.some(l => l.storageKey === k))) : [];
      option(key, '', '모든 WAS에 등록된 공통 키 선택'); keys.forEach(k => option(key, k, k));
      if (value.storageKey && !keys.includes(value.storageKey)) option(key, value.storageKey, value.storageKey + ' (일부 WAS 등록 누락)');
      key.value = value.storageKey || ''; key.dataset.mountField = 'storageKey';
      const rel = field(row, '저장소 내부 상대 경로 (빈 값은 등록된 루트)'); rel.value = value.relativePath || ''; rel.dataset.mountField = 'relativePath';
      const target = field(row, '컨테이너 절대 경로'); target.placeholder = '/app/data'; target.value = value.containerPath || ''; target.dataset.mountField = 'containerPath';
      const ro = field(row, '읽기 전용', 'checkbox'); ro.checked = value.readOnly ?? true; ro.dataset.mountField = 'readOnly';
      const remove = button('연결 제거', () => row.remove()); row.append(remove); rows.append(row);
      row.querySelectorAll('input,select,button').forEach(control => control.disabled = !view?.canManage);
    }
    const add = button('마운트 추가', () => addRow()), save = button('연결 저장', () => run(state, async () => {
      if (!ready()) throw new Error('현재 환경의 저장소 설정을 먼저 불러와 주세요.');
      const selectedEnv = env.value, selectedComponent = component.value, version = context;
      if (!selectedComponent) throw new Error('WAS 배포 단위를 선택해 주세요.');
      const mounts = Array.from(rows.children).map(row => Object.fromEntries(Array.from(row.querySelectorAll('[data-mount-field]')).map(input => [input.dataset.mountField, input.type === 'checkbox' ? input.checked : input.value])));
      await api(`/api/v1/projects/${id}/storage/${selectedEnv}`, 'PUT', { componentId: Number(selectedComponent), mounts, reason: reason.value });
      if (!state.current() || version !== context) return;
      await reload(selectedComponent); if (state.current()) state.message.textContent = '연결 저장 완료. 각 WAS를 선택해 경로·UID:GID 접근 검사를 실행하세요. 새 배포 승인을 받아야 적용됩니다.';
    })), check = button('저장된 연결 경로·권한 검사', () => run(state, async () => {
      if (!ready()) throw new Error('현재 환경의 저장소 설정을 먼저 불러와 주세요.');
      const version = context;
      if (!component.value || !server.value) throw new Error('배포 단위와 검사할 WAS를 선택해 주세요.');
      await api(`/api/v1/projects/${id}/storage/${env.value}/check?serverId=${encodeURIComponent(server.value)}&componentId=${encodeURIComponent(component.value)}`, 'POST');
      if (state.current() && version === context) state.message.textContent = '저장된 설정의 실제 마운트 원본과 UID:GID 접근 검사 통과. 편집 중인 미저장 값은 검사 대상이 아닙니다.';
    }));
    state.body.append(add, save, check);
    state.contextControls = [env, component, server];
    state.syncControls = () => {
      const disabled = !ready() || !view?.components.length;
      [add, save, check, reason].forEach(control => control.disabled = disabled);
      rows.querySelectorAll('input,select,button').forEach(control => control.disabled = disabled);
    };
    function renderRows() { rows.replaceChildren(); (view?.bindings || []).filter(b => String(b.componentId) === component.value).forEach(addRow); }
    component.onchange = () => { ++context; renderRows(); state.message.textContent = '선택한 배포 단위의 저장된 연결입니다.'; };
    async function reload(selected) {
      const version = ++context, selectedEnv = env.value;
      view = null; loadedEnvironment = null; rows.replaceChildren(); component.replaceChildren(); server.replaceChildren(); inventory.textContent = ''; state.syncControls();
      const value = await api(`/api/v1/projects/${id}/storage/${selectedEnv}`);
      if (!state.current() || version !== context || selectedEnv !== env.value) return;
      view = value; loadedEnvironment = selectedEnv; component.replaceChildren(); value.components.forEach(c => option(component, c.id, c.name)); if (selected != null && value.components.some(c => String(c.id) === String(selected))) component.value = selected;
      server.replaceChildren(); Object.entries(value.serverNames).forEach(([serverId, name]) => option(server, serverId, name));
      inventory.textContent = Object.entries(value.locations).map(([serverId, list]) => `${value.serverNames[serverId]}: ${list.length ? list.map(l => l.storageKey + ' → ' + l.rootPath).join(', ') : '허용 저장소 없음'}`).join('\n');
      [add, save, check, reason].forEach(control => control.disabled = !value.canManage || !value.components.length); renderRows();
      state.message.textContent = `${selectedEnv} 환경 · ${value.canManage ? '설정을 저장한 다음 각 WAS의 연결을 검사하세요.' : '조회 화면입니다. 환경별 런타임 설정 관리 권한이 필요합니다.'}`;
    }
    env.onchange = () => run(state, () => reload());
    await run(state, async () => { await reload(); if (state.current()) state.message.textContent = view?.canManage ? '설정을 저장한 다음 각 WAS의 연결을 검사하세요.' : '조회 화면입니다. 환경별 런타임 설정 관리 권한이 필요합니다.'; });
  }
  window.DeployStorage = { openServer, openProject };
})();
