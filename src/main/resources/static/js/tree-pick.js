/* =====================================================
   DeployOps — tree-pick.js
   저장소 경로를 탐색기처럼 열어 고른다

   빌드 파일 경로·산출물 경로·Dockerfile 위치·Docker 컨텍스트를 지금까지 손으로
   치게 했다. 그러려면 저장소 구조를 외우고 있어야 하고, 오타를 내도 저장은 되며
   그 사실은 빌드가 깨질 때에야 드러난다 — 그때는 이미 배포 중이다.

   GitLab 저장소 트리를 한 단계씩 읽어 실제로 있는 것만 고르게 한다.
===================================================== */

/** 지금 탐색 중인 대상. {inputId, projectId, mode:'file'|'dir', title} */
var _trTarget = null;
/** 현재 열어 둔 디렉터리(저장소 루트는 ''). */
var _trPath = '';

/**
 * 경로 탐색기를 연다.
 *
 * @param inputId   값을 넣을 입력칸 id
 * @param projectId 배포시스템 프로젝트 id
 * @param mode      'file' 파일을 고른다 · 'dir' 폴더를 고른다
 * @param title     모달 제목
 */
function trOpen(inputId, projectId, mode, title) {
  if (!projectId) {
    showToast('프로젝트를 먼저 저장해 주세요.', 'error');
    return;
  }
  _trTarget = { inputId: inputId, projectId: projectId, mode: mode || 'file' };
  document.getElementById('modal-tree-title').textContent = title || '경로 선택';
  document.getElementById('tr-lead').textContent = _trTarget.mode === 'dir'
    ? '폴더를 눌러 들어가고, 지금 열어 둔 폴더를 그대로 쓰려면 아래 버튼을 누르세요.'
    : '폴더를 눌러 들어가고, 쓰려는 파일을 고르세요.';

  // 지금 값이 있으면 그 파일이 있는 폴더에서 시작한다 — 루트부터 다시 찾게 하지 않는다.
  var cur = (document.getElementById(inputId) || {}).value || '';
  var slash = cur.lastIndexOf('/');
  _trPath = slash > 0 ? cur.slice(0, slash) : '';

  document.getElementById('tr-sel-path').value = cur;
  document.getElementById('modal-tree').classList.add('open');
  trLoad(_trPath);
}

function trLoad(path) {
  _trPath = path || '';
  var host = document.getElementById('tr-list');
  host.innerHTML = '<p class="tr-empty">불러오는 중…</p>';
  trCrumb();

  fetch('/api/v1/gitlab/deploy-projects/' + _trTarget.projectId
        + '/tree?path=' + encodeURIComponent(_trPath))
    .then(function (r) { return r.json(); })
    .then(function (res) {
      if (!res.success) {
        host.innerHTML = '<p class="tr-empty err">' + escapeHtml(res.message || '불러오지 못했습니다.') + '</p>';
        return;
      }
      var entries = (res.data && res.data.entries) || [];
      if (!entries.length) {
        host.innerHTML = '<p class="tr-empty">이 폴더는 비어 있습니다.</p>';
        return;
      }
      host.innerHTML = entries.map(function (e) {
        var dir = e.type === 'tree';
        // 폴더를 고르는 중이면 파일은 눌러도 소용없으므로 흐리게 둔다.
        var dim = (!dir && _trTarget.mode === 'dir') ? ' dim' : '';
        return '<button type="button" class="tr-row' + dim + '"'
          + ' data-path="' + escapeHtml(e.path) + '" data-dir="' + dir + '"'
          + ' onclick="trClick(this)">'
          + (dir
              ? '<svg class="tr-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>'
              : '<svg class="tr-ico file" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>')
          + '<span class="tr-name">' + escapeHtml(e.name) + '</span>'
          + (dir ? '<span class="tr-arrow" aria-hidden="true">›</span>' : '')
          + '</button>';
      }).join('');
    })
    .catch(function () {
      host.innerHTML = '<p class="tr-empty err">네트워크 오류가 발생했습니다.</p>';
    });
}

function trClick(btn) {
  if (btn.dataset.dir === 'true') {
    trLoad(btn.dataset.path);
    // 폴더를 고르는 중이면 들어간 폴더가 곧 선택값이다.
    if (_trTarget.mode === 'dir') {
      document.getElementById('tr-sel-path').value = btn.dataset.path;
    }
    return;
  }
  if (_trTarget.mode === 'dir') return;   // 폴더를 고르는 중에는 파일을 담지 않는다
  document.getElementById('tr-sel-path').value = btn.dataset.path;
  document.querySelectorAll('#tr-list .tr-row').forEach(function (el) { el.classList.remove('on'); });
  btn.classList.add('on');
}

/** 현재 위치를 조각별로 눌러 되돌아갈 수 있게 그린다. */
function trCrumb() {
  var host = document.getElementById('tr-crumb');
  var parts = _trPath ? _trPath.split('/') : [];
  var html = '<button type="button" class="tr-cr" onclick="trLoad(\'\')">저장소 루트</button>';
  var acc = '';
  parts.forEach(function (seg) {
    acc = acc ? acc + '/' + seg : seg;
    html += '<span class="tr-cr-sep">/</span>'
      + '<button type="button" class="tr-cr" onclick="trLoad(\'' + acc.replace(/'/g, "\\'") + '\')">'
      + escapeHtml(seg) + '</button>';
  });
  // 폴더를 고르는 중이면 "여기를 쓰겠다" 를 명시적으로 누를 수 있어야 한다.
  if (_trTarget && _trTarget.mode === 'dir') {
    html += '<button type="button" class="tr-here" onclick="trUseHere()">여기 사용</button>';
  }
  host.innerHTML = html;
}

function trUseHere() {
  document.getElementById('tr-sel-path').value = _trPath || '.';
}

function trApply() {
  if (!_trTarget) return;
  var value = (document.getElementById('tr-sel-path').value || '').trim();
  if (!value) {
    showToast('경로를 고르세요.', 'error');
    return;
  }
  var input = document.getElementById(_trTarget.inputId);
  if (!input) return;
  input.value = value;
  input.dispatchEvent(new Event('change', { bubbles: true }));
  closeModal('modal-tree');
  showToast('경로를 넣었습니다. 저장을 눌러야 반영됩니다.', 'success');
}
