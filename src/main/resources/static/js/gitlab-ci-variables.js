(function () {
  'use strict';

  var labels = {
    NEXUS_REGISTRY_USER: 'Nexus 컨테이너 저장소 사용자',
    NEXUS_REGISTRY_PASSWORD: 'Nexus 컨테이너 저장소 비밀번호',
    DOCKER_BUILD_IMAGE: 'Docker 빌드 이미지',
    CI_BUILD_IMAGE: '기타 빌드 이미지',
    MAVEN_BUILD_IMAGE: 'Maven 빌드 이미지',
    MAVEN_SETTINGS_FILE: 'Maven settings.xml 파일',
    NEXUS_MAVEN_USER: 'Nexus Maven 저장소 사용자',
    NEXUS_MAVEN_PASSWORD: 'Nexus Maven 저장소 비밀번호',
    RUNTIME_BASE_IMAGE: '서비스 런타임 베이스 이미지'
  };

  function current() {
    var root = document.getElementById('gitlab-ci-variables');
    var project = document.getElementById('project-data');
    return root && project && project.dataset.id ? {root: root, projectId: project.dataset.id} : null;
  }
  function part(root, name) { return root.querySelector('[data-ci-vars="' + name + '"]'); }
  function status(root, message) { var node = part(root, 'status'); if (node) node.textContent = message; }
  function endpoint(projectId, key) {
    var base = '/api/v1/gitlab/deploy-projects/' + encodeURIComponent(projectId) + '/ci-variables';
    return key ? base + '/' + encodeURIComponent(key) : base;
  }
  async function request(url, options) {
    var response = await csrfFetch(url, options || {headers: {'Cache-Control': 'no-store'}});
    var body = await response.json().catch(function () { return {}; });
    if (!response.ok || body.success === false) {
      var error = new Error(body.message || 'GitLab CI 변수 요청에 실패했습니다.');
      error.status = response.status;
      throw error;
    }
    return body.data;
  }
  function make(tag, className, content) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (content != null) node.textContent = content;
    return node;
  }
  function render(state, data) {
    var list = part(state.root, 'list');
    list.replaceChildren();
    var reload = part(state.root, 'reload');
    if (reload) reload.disabled = false;
    if (!data.canManage) {
      status(state.root, '현재 계정은 조회만 가능합니다. Super Admin에게 이 프로젝트의 GitLab CI 변수 관리 권한을 요청하세요.');
    } else {
      status(state.root, '현재 프로젝트 변수 설정 상태입니다. 실제 값은 GitLab에서 읽어오거나 화면에 표시하지 않습니다.');
    }
    (data.variables || []).forEach(function (variable) {
      var card = make('section', 'form-group full');
      card.style.cssText = 'border:1px solid var(--border);border-radius:8px;padding:12px;margin:0';
      var heading = make('div', '', labels[variable.key] || variable.key);
      heading.style.fontWeight = '600';
      if ((data.requiredVariables || []).includes(variable.key)) {
        heading.append(make('span', 'badge', ' · 현재 빌드에 필요'));
      }
      card.append(heading);
      var meta = make('p', 'form-hint', (variable.configured ? '설정됨' : '미설정')
        + ' · ' + (variable.variableType === 'file' ? 'File 변수' : '환경 변수')
        + ' · ' + (variable.protected ? '보호 변수' : '보호되지 않은 변수') + (variable.masked ? ' · 마스킹' : '')
        + ' · 환경 범위 ' + (variable.environmentScope || '*'));
      meta.style.margin = '4px 0 8px';
      card.append(meta);
      if (data.canManage) {
        var input;
        if (variable.variableType === 'file') {
          input = make('textarea', 'form-textarea'); input.rows = 5;
          input.placeholder = 'settings.xml 내용을 입력하세요. 기존 값은 불러오지 않습니다.';
        } else {
          input = make('input', 'form-input');
          input.type = variable.masked ? 'password' : 'text';
          input.autocomplete = 'new-password';
          input.placeholder = variable.configured ? '기존 값 유지: 비워두세요. 교체할 때만 새 값을 입력합니다.' : '신규 값을 입력하세요.';
        }
        input.setAttribute('aria-label', (labels[variable.key] || variable.key) + ' 새 값');
        input.dataset.variableInput = variable.key;
        input.maxLength = 10000;
        card.append(input);
        var reason = make('input', 'form-input'); reason.type = 'text'; reason.maxLength = 200;
        reason.placeholder = '변경 사유 (필수)'; reason.dataset.variableReason = variable.key;
        reason.setAttribute('aria-label', (labels[variable.key] || variable.key) + ' 변경 사유');
        reason.style.marginTop = '8px'; card.append(reason);
        var actions = make('div', 'page-actions'); actions.style.marginTop = '8px';
        var save = make('button', 'btn btn-primary', variable.configured ? '새 값으로 교체' : '변수 등록');
        save.type = 'button'; save.dataset.variableSave = variable.key; actions.append(save);
        if (variable.configured) {
          var remove = make('button', 'btn btn-ghost', '변수 삭제'); remove.type = 'button';
          remove.dataset.variableDelete = variable.key; actions.append(remove);
        }
        card.append(actions);
      }
      list.append(card);
    });
  }
  async function load(state) {
    var reload = part(state.root, 'reload'); if (reload) reload.disabled = true;
    status(state.root, 'GitLab 변수 메타데이터를 조회하는 중입니다.');
    try { render(state, await request(endpoint(state.projectId))); return true; }
    catch (error) { status(state.root, error.message || '조회에 실패했습니다.'); if (reload) reload.disabled = false; return false; }
  }
  async function reconcileUnknown(state, message) {
    var refreshed = await load(state);
    status(state.root, message + (refreshed
      ? ' 설정 존재 여부와 보호 상태를 다시 조회했습니다. 값의 적용 여부는 이 화면에서 확인할 수 없습니다. 재전송 전에 확인해 주세요.'
      : ' 상태 재조회도 실패했습니다. 연결을 복구한 뒤 새로고침하여 확인해 주세요.'));
  }
  async function save(state, key, input, reason) {
    var value = input.value;
    if (!value) { status(state.root, '새 값을 입력하세요. 기존 값은 빈 입력으로 유지됩니다.'); input.focus(); return; }
    if (!reason.value.trim()) { status(state.root, '변경 사유를 입력하세요.'); reason.focus(); return; }
    openConfirm('GitLab CI 변수 변경', (labels[key] || key) + ' 값을 GitLab에 저장합니다. 값은 조회하거나 감사 기록에 남기지 않습니다.', async function () {
      input.disabled = true; reason.disabled = true;
      try {
        await request(endpoint(state.projectId, key), {method: 'PUT', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({value: value, reason: reason.value.trim()})});
        input.value = ''; reason.value = '';
        showToast('변수를 저장했습니다.', 'success');
        await load(state);
      } catch (error) {
        input.value = ''; status(state.root, error.message || '저장에 실패했습니다.');
        input.disabled = false; reason.disabled = false;
        if (error.status === 504) await reconcileUnknown(state, error.message);
      }
    });
  }
  async function remove(state, key, reason) {
    if (!reason.value.trim()) { status(state.root, '변경 사유를 입력하세요.'); reason.focus(); return; }
    openConfirm('GitLab CI 변수 삭제', (labels[key] || key) + ' 변수를 GitLab 프로젝트에서 삭제합니다.', async function () {
      reason.disabled = true;
      try {
        await request(endpoint(state.projectId, key), {method: 'DELETE', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({reason: reason.value.trim()})});
        showToast('변수를 삭제했습니다.', 'success');
        await load(state);
      } catch (error) {
        status(state.root, error.message || '삭제에 실패했습니다.'); reason.disabled = false;
        if (error.status === 504) await reconcileUnknown(state, error.message);
      }
    });
  }
  document.addEventListener('click', function (event) {
    var state = current(); if (!state) return;
    var target = event.target.closest('[data-ci-vars="reload"], [data-variable-save], [data-variable-delete]');
    if (!target || !state.root.contains(target)) return;
    if (target.dataset.ciVars === 'reload') { load(state); return; }
    var key = target.dataset.variableSave || target.dataset.variableDelete;
    var input = state.root.querySelector('[data-variable-input="' + key + '"]');
    var reason = state.root.querySelector('[data-variable-reason="' + key + '"]');
    if (target.dataset.variableSave) save(state, key, input, reason); else remove(state, key, reason);
  });
  window.GitlabCiVariables = {load: function () { var state = current(); if (state) return load(state); }};
})();
