/* Office 알림 전송 기록. 응답 payload와 오류 원문은 화면에 노출하지 않는다. */
var OfficeNotificationOutbox = (function () {
  'use strict';
  var states = new WeakMap();
  var endpoint = '/api/v1/office-notifications/outbox';
  var labels = { PENDING: '대기', SENDING: '전송 중', DELIVERED: '전송 처리됨', DEAD_LETTER: '실패' };

  function current() {
    var root = document.getElementById('office-notification-outbox');
    if (!root) return null;
    if (!states.has(root)) states.set(root, { root: root, seq: 0, initialized: false, loading: false, retrying: false, view: null });
    return states.get(root);
  }
  function el(state, name) { return state.root.querySelector('[data-outbox="' + name + '"]'); }
  function active(state, seq) { return state.root.isConnected && current() === state && state.seq === seq; }
  function message(state, text) { el(state, 'message').textContent = text; }
  function cell(row, text) {
    var td = document.createElement('td');
    td.textContent = text == null || text === '' ? '—' : String(text);
    row.append(td);
    return td;
  }
  function controls(state) {
    var busy = state.loading || state.retrying;
    el(state, 'filter').disabled = busy;
    el(state, 'refresh').disabled = busy;
    el(state, 'prev').disabled = busy || !state.view || state.view.page === 0;
    el(state, 'next').disabled = busy || !state.view || (state.view.page + 1) * state.view.size >= state.view.total;
    el(state, 'rows').querySelectorAll('button').forEach(function (button) { button.disabled = busy; });
    state.root.setAttribute('aria-busy', String(busy));
  }
  function empty(state, text) {
    var row = document.createElement('tr');
    cell(row, text).colSpan = 10;
    el(state, 'rows').replaceChildren(row);
  }
  async function request(url, options) {
    var response = await (options ? csrfFetch(url, options) : fetch(url, { cache: 'no-store' }));
    if (response.status === 403) throw new Error('FORBIDDEN');
    if (!response.ok || response.redirected) throw new Error('REQUEST_FAILED');
    var result = await response.json();
    if (!result || !result.success) throw new Error('REQUEST_FAILED');
    return result.data;
  }
  function validView(view) {
    return view && Object.hasOwn(labels, view.status) && Number.isSafeInteger(view.page) && view.page >= 0
      && Number.isSafeInteger(view.size) && view.size >= 1 && view.size <= 100
      && Number.isSafeInteger(view.total) && view.total >= 0 && Array.isArray(view.items) && view.items.length <= view.size
      && view.counts && Object.keys(labels).every(function (status) {
        return Number.isSafeInteger(view.counts[status]) && view.counts[status] >= 0;
      });
  }
  function render(state) {
    var view = state.view;
    el(state, 'filter').value = view.status;
    Object.keys(labels).forEach(function (status) { el(state, 'count-' + status).textContent = String(view.counts[status]); });
    var pages = Math.max(1, Math.ceil(view.total / view.size));
    el(state, 'page').textContent = (view.page + 1) + '쪽 / ' + pages + '쪽 · ' + view.total + '건';
    el(state, 'rows').replaceChildren();
    if (!view.items.length) { empty(state, '조회된 ' + labels[view.status] + ' 알림이 없습니다.'); return; }
    view.items.forEach(function (item) {
      var row = document.createElement('tr');
      cell(row, item.id);
      cell(row, item.type);
      var target = cell(row, '—');
      if (Number.isSafeInteger(item.targetRequestId) && item.targetRequestId > 0) {
        var link = document.createElement('a');
        link.textContent = '요청 #' + item.targetRequestId;
        link.setAttribute('href', '/deploy/request-detail?id=' + item.targetRequestId);
        target.replaceChildren(link);
      }
      cell(row, Object.hasOwn(labels, item.status) ? labels[item.status] : item.status);
      cell(row, item.attempts);
      cell(row, item.createdAt);
      cell(row, item.nextAttemptAt);
      cell(row, item.deliveredAt);
      cell(row, item.deadLetteredAt);
      var action = cell(row, '재시도 불가');
      if (item.status === 'DEAD_LETTER' && Number.isSafeInteger(item.id) && item.id > 0) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-ghost btn-sm';
        button.textContent = '재시도';
        button.setAttribute('aria-label', '알림 ' + item.id + ' 재시도');
        button.onclick = function () { retry(item.id); };
        action.replaceChildren(button);
      }
      el(state, 'rows').append(row);
    });
  }
  async function load(page, status) {
    var state = current();
    if (!state || state.retrying) return;
    status = status || el(state, 'filter').value || 'DEAD_LETTER';
    page = Number.isSafeInteger(page) && page >= 0 ? page : 0;
    if (!Object.hasOwn(labels, status)) return;
    var seq = ++state.seq;
    state.loading = true;
    state.view = null;
    message(state, '알림 전송 기록을 조회하는 중입니다.');
    el(state, 'page').textContent = '조회 중';
    Object.keys(labels).forEach(function (name) { el(state, 'count-' + name).textContent = '—'; });
    empty(state, '조회 중입니다.');
    controls(state);
    try {
      var view = await request(endpoint + '?status=' + encodeURIComponent(status) + '&page=' + page + '&size=30');
      if (!active(state, seq)) return;
      if (!validView(view)) throw new Error('INVALID_RESPONSE');
      state.view = view;
      render(state);
      message(state, '서버에 기록된 상태입니다. 전송 처리됨은 사용자의 읽음이나 수신 확인을 뜻하지 않습니다.');
    } catch (error) {
      if (!active(state, seq)) return;
      state.view = null;
      empty(state, '알림 기록을 불러오지 못했습니다. 새로고침으로 다시 조회해 주세요.');
      el(state, 'page').textContent = '조회 실패';
      message(state, error.message === 'FORBIDDEN'
        ? '관리자 권한 또는 로그인 상태를 확인한 뒤 다시 조회해 주세요.'
        : '조회에 실패했습니다. 로그인 상태와 연결을 확인한 뒤 다시 조회해 주세요.');
    } finally {
      if (active(state, seq)) { state.loading = false; controls(state); }
    }
  }
  function retry(id) {
    var state = current();
    if (!state || state.loading || state.retrying || !state.view) return;
    var item = state.view.items.find(function (candidate) { return candidate.id === id; });
    if (!item || item.status !== 'DEAD_LETTER' || !Number.isSafeInteger(id) || id <= 0) return;
    var modal = document.getElementById('modal-confirm');
    if (modal && modal.classList.contains('open')) return;
    var seq = state.seq;
    var consumed = false;
    openConfirm('실패 알림 재시도', '알림 ' + id + '의 전송을 다시 요청하시겠습니까? 요청 접수는 전송 완료를 뜻하지 않습니다.', async function () {
      if (consumed) return;
      consumed = true;
      if (!active(state, seq) || state.loading || state.retrying || !state.view) return;
      var page = state.view.page, status = state.view.status;
      state.retrying = true;
      controls(state);
      message(state, '재시도를 요청하는 중입니다.');
      var accepted = false;
      try {
        await request(endpoint + '/' + encodeURIComponent(id) + '/retry', { method: 'POST' });
        if (!active(state, seq)) return;
        accepted = true;
      } catch (error) {
        if (!active(state, seq)) return;
        state.view = null;
        empty(state, '요청 결과를 확인하지 못했습니다. 새로고침으로 상태를 확인해 주세요.');
        message(state, error.message === 'FORBIDDEN'
          ? '관리자 권한과 로그인 상태를 확인해 주세요. 새로고침 후 다시 시도해 주세요.'
          : '재시도 요청 결과를 확인하지 못했습니다. 자동으로 반복하지 않습니다. 새로고침으로 상태를 확인해 주세요.');
      } finally {
        state.retrying = false;
        if (active(state, seq)) controls(state);
      }
      if (accepted && active(state, seq)) {
        var reload = load(page, status), reloadSeq = state.seq;
        await reload;
        if (active(state, reloadSeq)) {
          message(state, '재시도를 요청했습니다. ' + (state.view ? '새로 조회한 상태를 확인해 주세요.' : '상태 재조회에 실패했습니다. 새로고침으로 확인해 주세요.'));
        }
      }
    });
  }
  function init() {
    var state = current();
    if (!state || state.initialized) return;
    state.initialized = true;
    el(state, 'filter').onchange = function () { return load(0, this.value); };
    el(state, 'refresh').onclick = function () { return load(state.view ? state.view.page : 0); };
    el(state, 'prev').onclick = function () { if (state.view) return load(state.view.page - 1, state.view.status); };
    el(state, 'next').onclick = function () { if (state.view) return load(state.view.page + 1, state.view.status); };
    return load(0, 'DEAD_LETTER');
  }
  return { init: init, load: load, retry: retry };
}());
