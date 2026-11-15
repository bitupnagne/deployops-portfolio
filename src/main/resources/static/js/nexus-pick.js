/* =====================================================
   DeployOps — nexus-pick.js
   Nexus 저장소를 손으로 치지 않고 고른다

   프로젝트를 만들 때 Nexus 저장소 이름과 이미지 저장소 주소를 직접 입력하게 되어 있었다.
   그러려면 Nexus 콘솔에 먼저 들어가 무엇이 있는지 보고 와야 하고, 오타를 내도 저장은
   되며 그 사실은 배포가 시작되고 나서야 드러난다. 배포에 필요한 준비를 배포시스템
   밖에서 하게 만들면 그 절차는 문서로만 남고 결국 아무도 정확히 재현하지 못한다.

   그래서 있는 것을 보여 주고 고르게 하며, 없으면 이 화면에서 만든다.
===================================================== */

/** 지금 모달이 채우려는 대상. {inputId, format, mode} */
var _nxTarget = null;
/** 목록에서 고른 저장소. */
var _nxPicked = null;
/** 서버가 저장소별로 확인한 연결 정보. */
var _nxRepositories = [];
var _nxLoadVersion = 0;

/**
 * 저장소 선택 모달을 연다.
 *
 * @param inputId 값을 넣을 입력칸 id
 * @param format  'docker' | 'maven2'
 * @param mode    'name'  저장소 이름만 넣는다 (Nexus Docker/Maven Repository 칸)
 *                'image' 레지스트리 주소 + 이미지 경로를 조합한다 (이미지 저장소 칸)
 *                'manage' 프로젝트 없이 저장소를 조회·생성하고 선택한 이름을 표시한다
 * @param seed    이미지 경로 기본값(프로젝트 이름 등)
 */
function nxOpen(inputId, format, mode, seed, versionPolicy) {
  _nxTarget = { inputId: inputId, format: format, mode: mode || 'name', versionPolicy: versionPolicy || null };
  _nxPicked = null;

  var isImage = _nxTarget.mode === 'image';
  var isManage = _nxTarget.mode === 'manage';
  document.getElementById('modal-nexus-title').textContent =
    isManage ? 'Nexus 저장소 조회·생성' : (isImage ? '이미지 저장소 선택' : 'Nexus 저장소 선택');
  document.getElementById('nx-apply').textContent = isManage ? '선택한 이름 표시' : '이 값으로 넣기';
  var lead = isImage
    ? '이미지를 올릴 docker 저장소를 고르고, 그 안에서 쓸 이미지 경로를 정하세요.'
    : (isManage ? '저장소 목록을 확인하거나 아래에서 hosted 저장소를 만드세요. 선택한 이름은 이 화면에만 표시됩니다.' : '이 프로젝트가 쓸 저장소를 고르세요.');
  document.getElementById('nx-lead').textContent = format === 'docker'
    ? lead + ' 주소와 Registry 응답만 확인하며, 실제 인증·업로드 권한은 이미지 push 시 확인됩니다.'
    : lead;
  document.getElementById('nx-image').style.display = isImage ? '' : 'none';
  document.getElementById('nx-new-port-wrap').style.display =
    format === 'docker' ? '' : 'none';
  document.getElementById('nx-new-name').value = '';
  document.getElementById('nx-new-port').value = '';
  document.getElementById('nx-new-policy-wrap').style.display = format === 'maven2' ? '' : 'none';
  document.getElementById('nx-new-policy').value = versionPolicy || 'RELEASE';
  document.getElementById('nx-new-policy').disabled = !!versionPolicy;
  document.getElementById('nx-registry').textContent = '(저장소를 선택하세요)';

  // 이미지 경로는 지금 값이 있으면 그것을, 없으면 프로젝트 이름을 기본으로 둔다.
  if (isImage) {
    var cur = (document.getElementById(inputId) || {}).value || '';
    var slash = cur.indexOf('/');
    document.getElementById('nx-image-path').value =
      slash > 0 ? cur.slice(slash + 1) : (seed ? seed + '/' + seed : '');
  }

  document.getElementById('modal-nexus').classList.add('open');
  nxLoad();
}

function nxLoad() {
  var loadVersion = ++_nxLoadVersion;
  var target = _nxTarget;
  _nxPicked = null;
  _nxRepositories = [];
  document.getElementById('nx-registry').textContent = '(저장소를 선택하세요)';
  var host = document.getElementById('nx-list');
  host.innerHTML = '<p class="nx-empty">불러오는 중…</p>';

  fetch('/api/v1/nexus/repositories?format=' + encodeURIComponent(_nxTarget.format))
    .then(function (r) { return r.json(); })
    .then(function (res) {
      if (loadVersion !== _nxLoadVersion || target !== _nxTarget) return;
      if (!res.success) {
        if (target.createdName) {
          host.innerHTML = '<p class="nx-empty err">' + escapeHtml(nxCreatedBrowseNotice(target))
            + ' 목록 조회에 실패했습니다.</p>';
          return;
        }
        // Nexus 를 못 읽는 것과 저장소가 없는 것은 다르다 — 원인을 그대로 보여 준다.
        host.innerHTML = '<p class="nx-empty err">' + escapeHtml(res.message || '불러오지 못했습니다.')
          + '</p>';
        return;
      }
      var d = res.data || {};
      var repos = d.repositories || [];
      _nxRepositories = repos;
      var createdNotice = '';
      if (target.createdName) {
        if (repos.some(function (repo) { return repo.name === target.createdName; })) {
          target.createdName = null;
        } else {
          createdNotice = '<p class="nx-empty">' + escapeHtml(nxCreatedBrowseNotice(target)) + '</p>';
        }
      }
      if (!repos.length) {
        host.innerHTML = createdNotice || '<p class="nx-empty">이 계정으로 조회할 수 있는 저장소가 없습니다. 저장소가 있어도 browse 권한이 없으면 목록에 표시되지 않습니다. Nexus 연동 계정의 저장소 browse 권한을 확인하세요.</p>';
        return;
      }
      host.innerHTML = createdNotice + repos.map(function (r) {
        var reason = nxUnavailableReason(r);
        return '<button type="button" class="nx-row" data-name="' + escapeHtml(r.name) + '"'
          + (reason ? ' disabled title="' + escapeHtml(reason) + '"' : '')
          + ' onclick="nxPick(this)">'
          + '<span class="nx-name">' + escapeHtml(r.name) + '</span>'
          + '<span class="nx-type">' + escapeHtml(reason || [r.type, r.versionPolicy, r.registry].filter(Boolean).join(' · ')) + '</span>'
          + '<svg class="nx-check" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
          + ' stroke-width="3" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>'
          + '</button>';
      }).join('');
    })
    .catch(function () {
      if (loadVersion !== _nxLoadVersion || target !== _nxTarget) return;
      host.innerHTML = '<p class="nx-empty err">'
        + (target.createdName ? escapeHtml(nxCreatedBrowseNotice(target)) + ' ' : '')
        + '네트워크 오류가 발생했습니다.</p>';
    });
}

function nxCreatedBrowseNotice(target) {
  return "'" + target.createdName + "' 저장소는 생성됐지만 현재 계정의 목록에 표시되지 않습니다. 다시 만들지 마세요. Nexus 연동 계정에 "
    + 'nx-repository-view-' + target.format + '-' + target.createdName + '-browse 권한을 확인한 뒤 목록을 새로고침하세요.';
}

function nxUnavailableReason(repo) {
  if (repo.unavailableReason) return repo.unavailableReason;
  if (repo.type !== 'hosted') return '업로드용 hosted 저장소를 선택하세요.';
  if (_nxTarget.format === 'docker' && (!repo.endpointReachable || !repo.registry)) {
    return '이 저장소의 레지스트리 연결을 확인할 수 없습니다.';
  }
  if (_nxTarget.versionPolicy && repo.versionPolicy !== _nxTarget.versionPolicy) {
    return _nxTarget.versionPolicy + ' 버전 정책의 저장소가 필요합니다.';
  }
  return '';
}

function nxPick(btn) {
  var repo = _nxRepositories.find(function (r) { return r.name === btn.dataset.name; });
  if (!repo || nxUnavailableReason(repo)) return;
  document.querySelectorAll('#nx-list .nx-row').forEach(function (el) {
    el.classList.remove('on');
  });
  btn.classList.add('on');
  _nxPicked = repo;
  document.getElementById('nx-registry').textContent = repo.registry || '(레지스트리 주소 없음)';
}

function nxApply() {
  if (!_nxTarget) return;
  var input = document.getElementById(_nxTarget.inputId);
  if (!input) return;
  if (!_nxPicked || nxUnavailableReason(_nxPicked)) {
    showToast('사용 가능한 저장소를 고르세요.', 'error');
    return;
  }

  if (_nxTarget.mode === 'image') {
    var path = (document.getElementById('nx-image-path').value || '').trim().replace(/^\/+/, '');
    if (!path) {
      showToast('이미지 경로를 입력하세요.', 'error');
      markInvalid(document.getElementById('nx-image-path'), '이미지 경로를 입력하세요.');
      return;
    }
    input.value = _nxPicked.registry + '/' + path;
  } else {
    input.value = _nxPicked.name;
  }
  input.dispatchEvent(new Event('change', { bubbles: true }));
  closeModal('modal-nexus');
  showToast(_nxTarget.mode === 'manage' ? '선택한 저장소 이름을 표시했습니다.' : '값을 넣었습니다. 저장을 눌러야 반영됩니다.', 'success');
}

/** 저장소를 Nexus 에 새로 만든다. 만든 뒤 목록을 다시 읽어 바로 고를 수 있게 한다. */
function nxCreate() {
  var target = _nxTarget;
  var name = (document.getElementById('nx-new-name').value || '').trim();
  var portEl = document.getElementById('nx-new-port');
  var port = portEl.value ? Number(portEl.value) : null;
  if (!name) {
    markInvalid(document.getElementById('nx-new-name'), '저장소 이름을 입력하세요.');
    showToast('저장소 이름을 입력하세요.', 'error');
    return;
  }

  var btn = document.getElementById('nx-create-btn');
  var body = { format: _nxTarget.format, name: name };
  if (_nxTarget.format === 'docker' && port) body.dockerHttpPort = port;
  if (_nxTarget.format === 'maven2') {
    body.versionPolicy = _nxTarget.versionPolicy || document.getElementById('nx-new-policy').value;
  }

  openConfirm('Nexus 에 저장소를 만들까요?',
    "'" + name + "' 저장소를 " + _nxTarget.format + (body.versionPolicy ? ' / ' + body.versionPolicy : '') + " 형식으로 만듭니다.\n\n"
    + 'Nexus 에 실제로 생성되며 되돌리려면 Nexus 콘솔에서 지워야 합니다.',
    function () {
      btn.disabled = true; btn.textContent = '만드는 중…';
      var t = document.querySelector('meta[name="_csrf"]').content;
      var h = document.querySelector('meta[name="_csrf_header"]').content;
      fetch('/api/v1/nexus/repositories', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [h]: t },
        body: JSON.stringify(body)
      })
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (res.success) {
            if (target !== _nxTarget) return;
            target.createdName = name;
            showToast(res.message || '저장소를 만들었습니다.', res.data && res.data.unavailableReason ? 'error' : 'success');
            document.getElementById('nx-new-name').value = '';
            document.getElementById('nx-create').open = false;
            nxLoad();
            return;
          }
          showToast(res.message || '만들지 못했습니다.', 'error');
        })
        .catch(function () { showToast('네트워크 오류가 발생했습니다.', 'error'); })
        .finally(function () { btn.disabled = false; btn.textContent = '저장소 만들기'; });
    });
}
