var ProjectRollout = (function () {
  'use strict';
  var states = new WeakMap();
  function current() {
    var root = document.getElementById('tab-rollout');
    if (!root) return null;
    if (!states.has(root)) states.set(root, {root: root, seq: 0, data: null, busy: false});
    return states.get(root);
  }
  function el(s, key) { return s.root.querySelector('[data-rollout="' + key + '"]'); }
  function alive(s, seq) { return s.root.isConnected && document.getElementById('tab-rollout') === s.root && s.seq === seq; }
  function url(s) { return '/api/v1/projects/' + encodeURIComponent(s.root.dataset.projectId) + '/rollout-routes?environment=' + encodeURIComponent(el(s,'environment').value); }
  function status(s, message) { el(s,'status').textContent = message; }
  function lock(s, value) {
    s.busy = value;
    ['environment','reload','save'].forEach(function (key) { el(s,key).disabled = value; });
    el(s,'choices').disabled = value || !s.data || s.data.canManage !== true;
  }
  async function request(url, options) {
    var response = await fetch(url, options || {cache:'no-store'});
    var data = await response.json();
    if (!response.ok || data.success !== true) throw new Error(data.message || '연결 요청에 실패했습니다.');
    return data.data;
  }
  function key(row) { return String(row.webServerId) + ':' + row.policyName; }
  function render(s) {
    var data = s.data, rows = el(s,'rows'); rows.replaceChildren();
    var choices = data.choices || [], bindings = data.bindings || [];
    var selected = new Set(bindings.map(key));
    var missing = bindings.filter(function (binding) { return !choices.some(function (choice) { return key(binding) === key(choice); }); });
    choices.forEach(function (choice) {
      var label = document.createElement('label');
      label.style.cssText = 'display:flex;gap:10px;align-items:flex-start;padding:12px 0;border-bottom:1px solid var(--line);overflow-wrap:anywhere';
      var input = document.createElement('input'); input.type = 'checkbox'; input.checked = selected.has(key(choice));
      input.dataset.webServerId = choice.webServerId; input.dataset.policyName = choice.policyName;
      var text = document.createElement('span');
      var title = document.createElement('strong'); title.textContent = choice.webServerName + ' · ' + choice.policyName;
      var detail = document.createElement('span'); detail.className = 'form-hint'; detail.style.display = 'block';
      detail.textContent = (choice.upstreams || []).map(function (row) { return row.host + ':' + row.port; }).join(', ')
        + ' / 적용 #' + choice.nativeRequestId;
      text.append(title,detail); label.append(input,text); rows.append(label);
    });
    if (!choices.length) {
      var empty = document.createElement('p'); empty.className = 'form-hint';
      empty.textContent = '연결 가능한 적용 정책이 없습니다. 서버 매핑과 WEB nginx 적용 상태를 확인하세요.'; rows.append(empty);
    }
    if (missing.length) {
      var warning = document.createElement('p'); warning.className = 'notice-box warning';
      warning.textContent = '현재 선택할 수 없는 기존 연결: ' + missing.map(function (row) { return 'WEB #' + row.webServerId + ' · ' + row.policyName; }).join(', ')
        + '. 정책 또는 서버 매핑을 확인하세요. 저장하면 목록에서 선택한 연결로 교체됩니다.';
      rows.append(warning);
    }
    el(s,'save').hidden = data.canManage !== true;
    status(s, bindings.length + '개 연결됨' + (data.canManage !== true ? ' · 조회 전용' : ''));
  }
  async function open() {
    var s = current(); if (!s || s.busy) return;
    var seq = ++s.seq; lock(s,true); s.data = null;
    el(s,'rows').replaceChildren(); el(s,'save').hidden = true; status(s,'조회 중…');
    try { var data = await request(url(s)); if (!alive(s,seq)) return; s.data = data; render(s); }
    catch (error) { if (alive(s,seq)) status(s,error.message); }
    finally { if (alive(s,seq)) lock(s,false); }
  }
  async function save() {
    var s = current(); if (!s || s.busy || !s.data || s.data.canManage !== true) return;
    var seq = ++s.seq, endpoint = url(s);
    var bindings = Array.from(el(s,'rows').querySelectorAll('input:checked')).map(function (input) {
      return {webServerId:Number(input.dataset.webServerId),policyName:input.dataset.policyName};
    });
    lock(s,true); status(s,'저장 중…');
    try {
      var token = document.querySelector('meta[name="_csrf"]'), header = document.querySelector('meta[name="_csrf_header"]');
      if (!token || !header) throw new Error('인증 정보를 확인한 뒤 다시 로그인하세요.');
      var headers = {'Content-Type':'application/json'}; headers[header.content] = token.content;
      await request(endpoint,{method:'PUT',headers:headers,body:JSON.stringify({bindings:bindings})});
      if (!alive(s,seq)) return;
      s.data = await request(endpoint);
      if (!alive(s,seq)) return;
      render(s); status(s,'저장했습니다. 이후 새 배포 요청부터 적용됩니다.');
    } catch (error) { if (alive(s,seq)) status(s,error.message); }
    finally { if (alive(s,seq)) lock(s,false); }
  }
  return {open:open,save:save};
})();
