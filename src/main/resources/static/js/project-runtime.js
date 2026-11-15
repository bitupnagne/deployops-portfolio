var ProjectRuntime = (function () {
  'use strict';
  var states = new WeakMap();
  function current() {
    var root = document.getElementById('tab-runtime');
    if (!root) return null;
    if (!states.has(root)) states.set(root, {root: root, projectId: root.dataset.projectId, seq: 0, busy: false, data: null});
    return states.get(root);
  }
  function el(s, name) { return s.root.querySelector('[data-runtime="' + name + '"]'); }
  function alive(s, seq) { return s.root.isConnected && document.getElementById('tab-runtime') === s.root && s.seq === seq; }
  function status(s, message) { el(s, 'status').textContent = message; }
  function component(s) { return Number(el(s, 'component').value); }
  function base(s) { return '/api/v1/projects/' + encodeURIComponent(s.projectId) + '/runtime/' + el(s, 'environment').value; }
  function clearSecret(s) { el(s, 'content').value = ''; el(s, 'file').value = ''; }
  function lock(s, busy) {
    s.busy = busy;
    ['environment', 'component', 'reload', 'server', 'lines'].forEach(function (name) { el(s, name).disabled = busy; });
    el(s, 'editor').disabled = busy || !s.data || s.data.canManage !== true;
    el(s, 'read-logs').disabled = busy || !s.data || s.data.canReadLogs !== true;
  }
  async function request(url, method, body) {
    var options;
    if (method) {
      var header = document.querySelector('meta[name="_csrf_header"]');
      var token = document.querySelector('meta[name="_csrf"]');
      if (!header || !token) throw new Error('request');
      options = {method: method, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)};
      options.headers[header.content] = token.content;
    }
    var response = await fetch(url, options);
    var result = await response.json();
    if (!response.ok || result.success !== true) throw new Error('request');
    return result.data;
  }
  function render(s) {
    var id = component(s), data = s.data;
    var revisions = data.revisions.filter(function (r) { return Number(r.componentId) === id; });
    var binding = data.bindings.find(function (b) { return Number(b.componentId) === id; });
    var mode = binding ? binding.mode : (id === 0 ? 'NONE' : 'INHERIT');
    el(s, 'editor').hidden = data.canManage !== true;
    var summary = el(s, 'metadata'); summary.replaceChildren();
    var selected = document.createElement('p');
    selected.textContent = '저장된 적용 방식: ' + ({INHERIT: '프로젝트 기본 설정 상속', MANAGED_REVISION: '리비전 #' + (binding && binding.revisionId), NONE: '환경변수 적용하지 않음'}[mode] || '확인 필요');
    summary.append(selected);
    var effective = mode === 'INHERIT' ? data.bindings.find(function (b) { return Number(b.componentId) === 0; }) : binding;
    var storage = document.createElement('p');
    storage.textContent = '배포할 로그 저장: ' + (effective && effective.persistentLogs ? '유지' : '사용하지 않음')
      + ' · 임시 공간: ' + (effective && effective.tmpMiB || 0) + 'MiB'
      + ' · 종료 유예: ' + (effective && effective.stopGraceSeconds || 30) + '초'
      + (mode === 'INHERIT' ? ' · 기본 리비전: ' + (effective && effective.revisionId != null ? '#' + effective.revisionId : '없음') : '');
    summary.append(storage);
    revisions.forEach(function (r) {
      var row = document.createElement('p');
      row.textContent = '리비전 #' + r.id + ' · ' + (r.createdAt || '저장됨') + ' · 변수명: ' + (r.keyNames || []).join(', ');
      summary.append(row);
    });
    if (!revisions.length) { var empty = document.createElement('p'); empty.textContent = '이 배포 단위에 저장된 리비전이 없습니다.'; summary.append(empty); }
    var select = el(s, 'revision'); select.replaceChildren(new Option('리비전을 선택해 주세요.', ''));
    revisions.forEach(function (r) { select.add(new Option('리비전 #' + r.id, r.id)); });
    select.value = binding && binding.revisionId != null ? String(binding.revisionId) : '';
    el(s, 'mode').options[0].disabled = id === 0;
    el(s, 'mode').value = mode;
    el(s, 'persistent-logs').checked = !!(binding && binding.persistentLogs);
    el(s, 'tmp').value = String(binding && binding.tmpMiB || 0);
    el(s, 'stop-grace').value = String(binding && binding.stopGraceSeconds || 30);
    el(s, 'binding-reason').value = '';
    changeMode();
    var server = el(s, 'server'); server.replaceChildren(new Option('서버를 선택해 주세요.', ''));
    var seen = new Set();
    Array.from(el(s, 'server-source').options).forEach(function (option) {
      if (option.dataset.environment === el(s, 'environment').value && !seen.has(option.value)) {
        seen.add(option.value); server.add(new Option(option.textContent, option.value));
      }
    });
    el(s, 'log-status').textContent = data.canReadLogs === true ? '서버를 선택한 뒤 파일 로그를 조회하세요.' : '이 환경의 런타임 파일 로그 조회 권한이 없습니다.';
  }
  async function open() {
    var s = current(); if (!s || s.busy) return;
    var seq = ++s.seq, url = base(s);
    clearSecret(s); el(s, 'revision-reason').value = ''; s.data = null;
    el(s, 'editor').hidden = true; el(s, 'metadata').replaceChildren();
    el(s, 'log-text').textContent = ''; el(s, 'log-status').textContent = '권한을 확인하는 중입니다.';
    lock(s, true); status(s, '런타임 설정을 불러오는 중입니다.');
    try {
      var data = await request(url);
      if (!alive(s, seq)) return;
      if (!data || !Array.isArray(data.revisions) || !Array.isArray(data.bindings)) throw new Error('request');
      s.data = data; render(s); status(s, '아직 서버에 적용되는 단계는 아닙니다.');
    } catch (_) { if (alive(s, seq)) status(s, '설정을 불러오지 못했습니다. 권한과 연결 상태를 확인한 뒤 다시 불러오세요.'); }
    finally { if (alive(s, seq)) lock(s, false); }
  }
  function changeEnvironment() { return open(); }
  function changeComponent() {
    var s = current(); if (!s || s.busy) return;
    ++s.seq; clearSecret(s); el(s, 'revision-reason').value = ''; el(s, 'log-text').textContent = '';
    if (s.data) render(s);
  }
  function changeMode() {
    var s = current(); if (!s) return;
    var mode = el(s, 'mode').value, inherit = mode === 'INHERIT';
    el(s, 'revision').disabled = mode !== 'MANAGED_REVISION';
    el(s, 'persistent-logs').disabled = inherit; el(s, 'tmp').disabled = inherit; el(s, 'stop-grace').disabled = inherit;
    if (inherit) { el(s, 'persistent-logs').checked = false; el(s, 'tmp').value = '0'; }
  }
  function reason(s, name) { return el(s, name).value.trim(); }
  function validReason(value) { return value.length > 0 && value.length <= 400; }
  async function save(s, suffix, method, body, secret) {
    var seq = ++s.seq, url = base(s) + suffix;
    lock(s, true); status(s, '저장 중입니다.');
    try {
      await request(url, method, body);
      if (secret) clearSecret(s);
      if (!alive(s, seq)) return;
      lock(s, false); await open();
      if (alive(s, seq + 1) && s.data) status(s, '설정을 저장했습니다. 새 배포 요청의 승인 내용에서 확인해 주세요.');
    } catch (_) {
      if (alive(s, seq)) status(s, '저장하지 못했습니다. 권한·입력 형식·배포 단위와 기존 환경변수 파일 경로 충돌을 확인하세요. 입력 내용은 서버 오류 메시지에 표시하지 않습니다.');
    } finally { if (alive(s, seq)) lock(s, false); }
  }
  function saveRevision() {
    var s = current(); if (!s || s.busy || !s.data || s.data.canManage !== true) return;
    var content = el(s, 'content').value, why = reason(s, 'revision-reason');
    if (!content.trim() || new TextEncoder().encode(content).length > 65536 || !validReason(why)) {
      status(s, '환경변수 내용은 64KiB 이내로, 저장 사유는 1~400자로 입력하세요.'); return;
    }
    return save(s, '/revisions', 'POST', {componentId: component(s), content: content, reason: why}, true);
  }
  function saveBinding() {
    var s = current(); if (!s || s.busy || !s.data || s.data.canManage !== true) return;
    var id = component(s), mode = el(s, 'mode').value, raw = el(s, 'tmp').value.trim();
    var graceRaw=el(s, 'stop-grace').value.trim(), grace=Number(graceRaw);
    var tmp = Number(raw), why = reason(s, 'binding-reason'), revision = Number(el(s, 'revision').value);
    if (!['INHERIT', 'NONE', 'MANAGED_REVISION'].includes(mode) || (id === 0 && mode === 'INHERIT') || !validReason(why)
      || (mode !== 'INHERIT' && (!graceRaw || !Number.isInteger(grace) || grace < 1 || grace > 300))
      || !raw || !Number.isInteger(tmp) || !(tmp === 0 || tmp >= 16 && tmp <= 512)
      || (mode === 'MANAGED_REVISION' && !s.data.revisions.some(function (r) { return Number(r.componentId) === id && Number(r.id) === revision; }))) {
      status(s, '적용 방식·리비전·임시 공간 범위와 변경 사유(1~400자)를 확인하세요.'); return;
    }
    return save(s, '/binding', 'PUT', {componentId: id, mode: mode, revisionId: mode === 'MANAGED_REVISION' ? revision : null,
      persistentLogs: mode === 'INHERIT' ? false : el(s, 'persistent-logs').checked, tmpMiB: mode === 'INHERIT' ? 0 : tmp, reason: why, stopGraceSeconds: mode === 'INHERIT' ? null : grace}, false);
  }
  async function uploadFile() {
    var s = current(); if (!s || s.busy || !s.data || s.data.canManage !== true) return;
    var file = el(s, 'file').files[0]; if (!file) return;
    clearSecret(s);
    if (file.size > 65536) { status(s, '64KiB 이하의 환경변수 파일을 선택해 주세요.'); return; }
    var seq = ++s.seq; lock(s, true);
    try {
      var bytes = await file.arrayBuffer();
      if (bytes.byteLength > 65536) throw new Error('size');
      var text = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes);
      if (alive(s, seq)) { el(s, 'content').value = text; status(s, '파일 내용을 가져왔습니다. 저장 전 형식과 사유를 확인하세요.'); }
    }
    catch (_) { if (alive(s, seq)) status(s, '파일을 읽지 못했습니다. 64KiB 이하의 UTF-8 파일인지 확인하세요.'); }
    finally { if (alive(s, seq)) lock(s, false); }
  }
  async function readLogs() {
    var s = current(); if (!s || s.busy || !s.data || s.data.canReadLogs !== true) return;
    var serverId = el(s, 'server').value, lines = Number(el(s, 'lines').value);
    if (!serverId || !Array.from(el(s, 'server').options).some(function (o) { return o.value === serverId; }) || !Number.isInteger(lines) || lines < 1 || lines > 1000) {
      el(s, 'log-status').textContent = '대상 서버와 1~1000 사이의 줄 수를 선택하세요.'; return;
    }
    var seq = ++s.seq, url = base(s) + '/logs?serverId=' + encodeURIComponent(serverId) + '&componentId=' + component(s) + '&lines=' + lines;
    lock(s, true); el(s, 'log-text').textContent = ''; el(s, 'log-status').textContent = '파일 로그를 읽는 중입니다.';
    try {
      var data = await request(url); if (!alive(s, seq)) return;
      el(s, 'log-text').textContent = String(data.text || '').slice(0, 262144);
      el(s, 'log-status').textContent = data.truncated || String(data.text || '').length > 262144 ? '로그 일부만 표시했습니다. 출력 한도에 도달했습니다.' : (data.text ? '파일 로그를 불러왔습니다.' : '표시할 로그가 없습니다.');
    } catch (_) { if (alive(s, seq)) el(s, 'log-status').textContent = '파일 로그를 읽지 못했습니다. 권한과 관리형 로그 배포 상태를 확인하세요.'; }
    finally { if (alive(s, seq)) lock(s, false); }
  }
  return {open: open, changeEnvironment: changeEnvironment, changeComponent: changeComponent, changeMode: changeMode,
    saveRevision: saveRevision, saveBinding: saveBinding, uploadFile: uploadFile, readLogs: readLogs};
})();
