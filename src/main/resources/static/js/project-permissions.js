/* 프로젝트 권한 편집기. 페이지 조각 교체 후의 이전 응답은 새 화면에 적용하지 않는다. */
var ProjectPermissions = (function () {
  'use strict';
  var states = new WeakMap();
  var labels = {
    PROJECT_VIEW: '프로젝트 조회', DEPLOY_REQUEST: '배포 요청', MR_REVIEW: 'MR 검토',
    CI_VARIABLE_MANAGE: 'GitLab CI 변수 관리',
    RUNTIME_CONFIG_MANAGE: '런타임 설정 관리', RUNTIME_LOG_READ: '런타임 파일 로그 조회',
    MR_APPROVE: 'MR 승인', MR_MERGE: 'MR 병합', TEST_APPROVE: 'TEST 승인·완료 확인',
    PROD_APPROVE: 'PROD 승인', TEST_EXECUTE: 'TEST 실행', PROD_EXECUTE: 'PROD 실행',
    ROLLBACK_EXECUTE: '롤백 요청 (실행 권한 별도)'
  };
  function current() {
    var root = document.getElementById('tab-permissions');
    if (!root) return null;
    if (!states.has(root)) states.set(root, { root: root, projectId: root.dataset.projectId, seq: 0, view: null, loaded: false, saving: false });
    return states.get(root);
  }
  function el(state, name) { return state.root.querySelector('[data-permission="' + name + '"]'); }
  function active(state, sequence) { return state.root.isConnected && state.seq === sequence; }
  function key(grant) { return grant.permission + '/' + grant.environment; }
  function label(grant) { return (labels[grant.permission] || grant.permission) + ' · ' + grant.environment; }
  function status(state, text) { el(state, 'status').textContent = text; }
  async function request(url, options) {
    var response = await fetch(url, options);
    var result = await response.json();
    if (!response.ok || !result.success) {
      var error = new Error(result.message || '권한 정보를 처리하지 못했습니다.');
      error.status = response.status;
      throw error;
    }
    return result.data;
  }
  function endpoint(state, userId) {
    return '/api/v1/projects/' + encodeURIComponent(state.projectId) + '/permissions'
      + (userId == null ? '' : '/' + encodeURIComponent(userId));
  }
  async function open() {
    var state = current();
    if (!state || state.loaded) return;
    var sequence = ++state.seq;
    status(state, '사용자 목록을 불러오는 중입니다.');
    try {
      var data = await request(endpoint(state));
      if (!active(state, sequence)) return;
      var select = el(state, 'user');
      select.replaceChildren(new Option('사용자를 선택해 주세요.', ''));
      data.users.forEach(function (user) {
        select.add(new Option(user.name + ' (' + user.loginId + ') · ' + user.roleLabel + (user.active ? '' : ' · 비활성'), user.id));
      });
      state.loaded = true;
      status(state, '권한을 확인할 사용자를 선택해 주세요.');
    } catch (error) { if (active(state, sequence)) status(state, error.message); }
  }
  function render(state, view) {
    state.view = view;
    el(state, 'editor').hidden = false;
    el(state, 'reason').value = '';
    el(state, 'reason').disabled = !view.editable;
    el(state, 'save').disabled = !view.editable;
    el(state, 'notes').replaceChildren();
    view.roleNotes.concat(view.editRestriction ? [view.editRestriction] : []).forEach(function (note) {
      var paragraph = document.createElement('p'); paragraph.textContent = note; el(state, 'notes').append(paragraph);
    });
    var grants = new Map();
    view.availableGrants.forEach(function (grant) { grants.set(key(grant), { grant: grant, active: false, supported: true }); });
    view.rawPermissions.forEach(function (row) {
      var entry = grants.get(key(row.grant)) || { grant: row.grant, supported: false };
      entry.active = row.active; grants.set(key(row.grant), entry);
    });
    el(state, 'grants').replaceChildren();
    grants.forEach(function (entry) {
      var wrapper = document.createElement('label'); wrapper.className = 'toggle-field';
      var input = document.createElement('input'); input.type = 'checkbox'; input.checked = entry.active;
      input.permissionGrant = entry.grant;
      input.disabled = !view.editable || (!entry.active && (!view.canGrant || !entry.supported));
      wrapper.append(input, document.createTextNode(' ' + label(entry.grant) + (entry.supported ? '' : ' · 기존 항목')));
      el(state, 'grants').append(wrapper);
    });
    el(state, 'effective').replaceChildren();
    view.effectivePermissions.forEach(function (row) {
      var item = document.createElement('li'); item.textContent = label(row.grant) + ' — ' + row.source;
      el(state, 'effective').append(item);
    });
    if (!view.effectivePermissions.length) {
      var empty = document.createElement('li'); empty.textContent = '현재 적용되는 프로젝트 권한이 없습니다.';
      el(state, 'effective').append(empty);
    }
    status(state, view.user.name + ' · 직접 부여와 역할 권한을 확인한 뒤 저장해 주세요.');
  }
  async function selectUser() {
    var state = current();
    if (!state || state.saving) return;
    var userId = el(state, 'user').value;
    var sequence = ++state.seq;
    state.view = null;
    el(state, 'editor').hidden = true;
    if (!userId) { status(state, '사용자를 선택해 주세요.'); return; }
    status(state, '권한을 불러오는 중입니다.');
    try {
      var view = await request(endpoint(state, userId));
      if (active(state, sequence)) render(state, view);
    } catch (error) { if (active(state, sequence)) status(state, error.message); }
  }
  function save() {
    var state = current();
    if (!state || state.saving || !state.view || !state.view.editable) return;
    var view = state.view;
    var reason = el(state, 'reason').value.trim();
    if (!reason) { status(state, '변경 사유를 입력해 주세요.'); el(state, 'reason').focus(); return; }
    var grants = Array.from(el(state, 'grants').querySelectorAll('input[type="checkbox"]'))
      .filter(function (input) { return input.checked; }).map(function (input) { return input.permissionGrant; });
    var before = new Set(view.rawPermissions.filter(function (row) { return row.active; }).map(function (row) { return key(row.grant); }));
    var after = new Set(grants.map(key));
    var added = grants.filter(function (grant) { return !before.has(key(grant)); }).map(label);
    var removed = view.rawPermissions.filter(function (row) { return row.active && !after.has(key(row.grant)); }).map(function (row) { return label(row.grant); });
    if (!added.length && !removed.length) { status(state, '변경된 직접 권한이 없습니다.'); return; }
    var payload = { grants: grants, reason: reason, expectedRevision: view.revision };
    openConfirm('프로젝트 권한을 변경할까요?', view.user.name + ' / ' + view.projectName
      + '\n부여: ' + (added.join(', ') || '없음') + '\n회수: ' + (removed.join(', ') || '없음')
      + '\n\n역할에서 자동으로 생기는 권한과 승인 정책은 유지됩니다.', async function () {
        if (!state.root.isConnected || state.view !== view || state.saving) return;
        state.saving = true;
        el(state, 'save').disabled = true; el(state, 'user').disabled = true; el(state, 'reload').disabled = true;
        var inputs = Array.from(el(state, 'grants').querySelectorAll('input[type="checkbox"]'));
        var disabledBefore = inputs.map(function (input) { return input.disabled; });
        inputs.forEach(function (input) { input.disabled = true; });
        el(state, 'reason').disabled = true;
        try {
          var csrf = document.querySelector('meta[name="_csrf"]').content;
          var header = document.querySelector('meta[name="_csrf_header"]').content;
          var updated = await request(endpoint(state, view.user.id), {
            method: 'PUT', headers: { 'Content-Type': 'application/json', [header]: csrf }, body: JSON.stringify(payload)
          });
          if (state.root.isConnected && state.view === view) { render(state, updated); status(state, '프로젝트 권한을 저장했습니다.'); }
        } catch (error) {
          if (state.root.isConnected) status(state, error.status === 409
            ? '다른 변경이 먼저 저장되었습니다. 현재 입력은 유지했습니다. 내용을 확인한 뒤 최신 권한을 불러와 다시 적용해 주세요.' : error.message);
        } finally {
          state.saving = false;
          if (state.root.isConnected) {
            el(state, 'save').disabled = !state.view || !state.view.editable;
            el(state, 'user').disabled = false; el(state, 'reload').disabled = false;
            el(state, 'reason').disabled = !state.view || !state.view.editable;
            if (state.view === view) inputs.forEach(function (input, index) { input.disabled = disabledBefore[index]; });
          }
        }
      });
  }
  return { open: open, selectUser: selectUser, save: save };
})();
