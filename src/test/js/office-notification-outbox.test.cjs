const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/office-notification-outbox.js'), 'utf8');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/integration.html'), 'utf8').replace(/\r\n/g, '\n');
const layout = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/layout/main.html'), 'utf8');

test('outbox filter keeps native controls so asynchronous disabled state remains visible and enforceable', () => {
  const filter = html.match(/<select\b[^>]*\bid="office-outbox-filter"[^>]*>/);
  assert.ok(filter, 'outbox status filter exists');
  assert.match(filter[0], /\bdata-no-custom-select(?:\s|>)/);
});

class Element {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.value = ''; this.textContent = ''; this.disabled = false;
    this.isConnected = true; this.attrs = {}; this.open = false;
    this.classList = { contains: name => name === 'open' && this.open };
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.textContent = ''; }
  setAttribute(name, value) { this.attrs[name] = value; }
  querySelectorAll(tag) { return this.children.flatMap(child => [ ...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag) ]); }
  set innerHTML(_value) { throw new Error('Server data must not use HTML sinks'); }
}
const statuses = ['PENDING', 'SENDING', 'DELIVERED', 'DEAD_LETTER'];
const item = (id = 44, status = 'DEAD_LETTER', overrides = {}) => ({id, type: 'DEPLOY_FAILED', status, attempts: 12,
  createdAt: '2026-10-01T10:00:00', nextAttemptAt: null, deliveredAt: null, deadLetteredAt: '2026-10-01T11:00:00', ...overrides});
const view = (overrides = {}) => ({status: 'DEAD_LETTER', page: 0, size: 30, total: 1,
  counts: {PENDING: 2, SENDING: 3, DELIVERED: 4, DEAD_LETTER: 1}, items: [item()], ...overrides});
const response = (data, status = 200, envelope = {}) => ({ok: status < 400, status,
  json: async () => ({success: status < 400, data, ...envelope})});
function setup(fetchImpl = async () => response(view())) {
  const names = ['filter', 'refresh', 'prev', 'next', 'rows', 'message', 'page', ...statuses.map(s => 'count-' + s)];
  const fields = Object.fromEntries(names.map(name => [name, new Element()]));
  fields.filter.value = 'DEAD_LETTER';
  let currentRoot = new Element();
  currentRoot.querySelector = selector => fields[selector.match(/"([^"]+)"/)[1]];
  const originalRoot = currentRoot;
  const modal = new Element(), requests = [], confirmations = [];
  const context = {document: {getElementById: id => id === 'modal-confirm' ? modal : currentRoot,
    createElement: tag => new Element(tag)},
    fetch: async (url, options) => { requests.push({url, options}); return fetchImpl(url, options); },
    csrfFetch: async (url, options) => {
      options.headers = {'X-CSRF-TOKEN': 'csrf-token'};
      requests.push({url, options}); return fetchImpl(url, options);
    },
    openConfirm: (title, description, callback) => {
      modal.open = true;
      confirmations.push({title, description, confirm: () => { modal.open = false; return callback(); }});
    }};
  vm.createContext(context); vm.runInContext(source, context);
  return {api: context.OfficeNotificationOutbox, fields, requests, confirmations, modal, root: originalRoot,
    cancel() { modal.open = false; }, detach() { originalRoot.isConnected = false; currentRoot = null; },
    buttons: () => fields.rows.querySelectorAll('button'),
    rows: () => fields.rows.children,
    text: () => {
      const collect = element => element.textContent + element.children.map(collect).join(' ');
      return names.map(name => collect(fields[name])).join(' ');
    }};
}

test('initializes once with DEAD_LETTER and displays server counts and normalized page, full/SPA wiring', async () => {
  const app = setup(async () => response(view({status: 'PENDING', page: 1, size: 2, total: 7, items: [item(1, 'PENDING')]})));
  await app.api.init(); await app.api.init();
  assert.equal(app.requests.length, 1);
  assert.equal(app.requests[0].url, '/api/v1/office-notifications/outbox?status=DEAD_LETTER&page=0&size=30');
  assert.equal(app.fields.filter.value, 'PENDING');
  assert.equal(app.fields['count-SENDING'].textContent, '3');
  assert.equal(app.fields.page.textContent, '2쪽 / 4쪽 · 7건');
  assert.equal(app.fields.prev.disabled, false); assert.equal(app.fields.next.disabled, false);
  assert.equal(app.buttons().length, 0);
  await app.fields.next.onclick();
  assert.match(app.requests[1].url, /status=PENDING&page=2/);
  assert.ok(layout.includes('/js/office-notification-outbox.js'));
  const init = html.slice(html.indexOf('  (function () {\n    function initOfficeOutbox'), html.indexOf('  }());', html.indexOf('function initOfficeOutbox')) + 9);
  assert.ok(init.includes('DOMContentLoaded'));
  let calls = 0, listener;
  const spa = {OfficeNotificationOutbox: {init: () => calls++}};
  vm.runInNewContext(init, spa); assert.equal(calls, 1);
  const full = {window: {addEventListener: (_name, callback, options) => {listener = callback; assert.equal(options.once, true);}}};
  vm.runInNewContext(init, full); assert.equal(calls, 1);
  full.OfficeNotificationOutbox = {init: () => calls++}; listener(); assert.equal(calls, 2);
});

test('target request renders a local escaped id link and leaves retry behavior available', async () => {
  const app = setup(async () => response(view({items: [item(1, 'DEAD_LETTER', {targetRequestId: 31,
    body: '<script>private</script>', linkUrl: 'https://private.invalid', recipientOfficeUserId: 200})]})));
  await app.api.init();
  const target = app.rows()[0].children[2];
  assert.equal(target.children[0].tag, 'a');
  assert.equal(target.children[0].textContent, '요청 #31');
  assert.equal(target.children[0].attrs.href, '/deploy/request-detail?id=31');
  assert.equal(app.buttons().length, 1);
  assert.doesNotMatch(app.text(), /private|script|200/);
  assert.equal(app.rows()[0].children.length, 10);
});

test('untrusted or missing target ids never become links and empty rows span all columns', async () => {
  for (const targetRequestId of [null, undefined, 0, -1, '31', '<img>', 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    const app = setup(async () => response(view({items: [item(1, 'DELIVERED', {targetRequestId})]})));
    await app.api.init();
    assert.equal(app.rows()[0].children[2].textContent, '—');
    assert.equal(app.rows()[0].children[2].children.length, 0);
  }
  const empty = setup(async () => response(view({items: [], total: 0})));
  await empty.api.init();
  assert.equal(empty.rows()[0].children[0].colSpan, 10);
});

test('empty state, next/previous boundary and changing filter reset page to zero', async () => {
  const app = setup(async url => response(view({status: url.includes('PENDING') ? 'PENDING' : 'DEAD_LETTER', total: 0, items: []})));
  await app.api.init();
  assert.match(app.text(), /조회된 실패 알림이 없습니다/);
  assert.equal(app.fields.prev.disabled, true); assert.equal(app.fields.next.disabled, true);
  app.fields.filter.value = 'PENDING'; await app.fields.filter.onchange();
  assert.match(app.requests[1].url, /status=PENDING&page=0/);
});

test('only server DEAD_LETTER rows may retry; metadata uses text nodes without payload or error strings', async () => {
  const hostile = '<img src=x onerror=alert(1)>';
  const app = setup(async () => response(view({total: 5, items: [...statuses.map((s, i) => item(i + 1, s)),
    item(5, hostile, {type: hostile, title: 'PRIVATE_TITLE', body: 'PRIVATE_BODY', linkUrl: 'javascript:SECRET',
      eventKey: 'PRIVATE_EVENT', lastError: 'X-SSO-SECRET: sensitive-value'})]})));
  await app.api.init();
  assert.equal(app.buttons().length, 1);
  [1, 2, 3, 5].forEach(id => app.api.retry(id)); assert.equal(app.confirmations.length, 0);
  assert.ok(app.text().includes(hostile));
  assert.doesNotMatch(app.text(), /PRIVATE_|sensitive-value|javascript:SECRET/);
  app.api.retry(4); assert.equal(app.confirmations.length, 1);
  assert.match(app.confirmations[0].description, /접수는 전송 완료를 뜻하지 않습니다/);
});

test('confirmation cancellation sends no POST and can reopen after modal is dismissed', async () => {
  const app = setup(); await app.api.init();
  app.api.retry(44); app.api.retry(44); assert.equal(app.confirmations.length, 1);
  app.cancel(); assert.equal(app.requests.length, 1);
  app.api.retry(44); assert.equal(app.confirmations.length, 2);
  assert.equal(app.requests.length, 1);
});

test('confirmed retry sends CSRF exactly once, disables controls, then refreshes actual state', async () => {
  let finish, reads = 0;
  const app = setup(async (_url, options) => options.method === 'POST'
    ? new Promise(resolve => {finish = resolve;})
    : response(view({items: [item(44, ++reads === 1 ? 'DEAD_LETTER' : 'PENDING')]})));
  await app.api.init(); app.api.retry(44);
  const pending = app.confirmations[0].confirm();
  app.api.retry(44); await app.confirmations[0].confirm();
  assert.equal(app.requests.length, 2);
  assert.equal(app.fields.refresh.disabled, true); assert.equal(app.buttons()[0].disabled, true);
  assert.deepEqual(JSON.parse(JSON.stringify(app.requests[1].options)), {method: 'POST', headers: {'X-CSRF-TOKEN': 'csrf-token'}});
  finish(response('not a delivery receipt'));
  await pending;
  assert.equal(app.requests.length, 3); assert.equal(app.buttons().length, 0);
  assert.match(app.fields.message.textContent, /재시도를 요청했습니다/);
  assert.doesNotMatch(app.fields.message.textContent, /전송이 완료/);
  assert.equal(app.fields.refresh.disabled, false);
});

test('ambiguous POST failure neither repeats nor leaves stale retry controls; server errors stay private', async () => {
  const app = setup(async (_url, options) => options.method === 'POST'
    ? response(null, 500, {message: '<script>SECRET</script>'}) : response(view()));
  await app.api.init(); app.api.retry(44); await app.confirmations[0].confirm();
  app.api.retry(44);
  assert.equal(app.requests.length, 2); assert.equal(app.buttons().length, 0);
  assert.match(app.fields.message.textContent, /자동으로 반복하지 않습니다/);
  assert.doesNotMatch(app.text(), /SECRET|script/);
  await app.api.load(0); assert.equal(app.buttons().length, 1);
});

test('GET errors, forbidden and malformed envelope restore refresh without revealing remote messages', async () => {
  for (const failure of [response(null, 403), response(null, 500, {message: 'secret'}), response(view({status: 'unsupported'}))]) {
    const app = setup(async () => failure); await app.api.init();
    assert.equal(app.fields.refresh.disabled, false); assert.equal(app.buttons().length, 0);
    assert.match(app.fields.page.textContent, /조회 실패/); assert.doesNotMatch(app.text(), /secret/);
  }
});

test('late GET cannot replace current filter or enable actions from a stale page', async () => {
  let first;
  const app = setup(async url => url.includes('status=DEAD_LETTER') ? new Promise(resolve => {first = resolve;})
    : response(view({status: 'DELIVERED', items: [item(44, 'DELIVERED')]})));
  const pending = app.api.init(); await app.api.load(0, 'DELIVERED');
  first(response(view())); await pending;
  assert.equal(app.fields.filter.value, 'DELIVERED'); assert.equal(app.buttons().length, 0);
  app.api.retry(44); assert.equal(app.confirmations.length, 0);
});

test('changed snapshot and detached SPA page invalidate a pending confirmation', async () => {
  const app = setup(); await app.api.init(); app.api.retry(44);
  await app.api.load(0, 'DELIVERED'); await app.confirmations[0].confirm();
  assert.equal(app.requests.filter(r => r.options.method === 'POST').length, 0);
  const detached = setup(); await detached.api.init(); detached.api.retry(44); detached.detach();
  await detached.confirmations[0].confirm(); assert.equal(detached.requests.length, 1);
});

test('accepted retry with failed refresh reports unconfirmed state without claiming delivery', async () => {
  let reads = 0;
  const app = setup(async (_url, options) => options.method === 'POST' ? response('accepted')
    : ++reads === 1 ? response(view()) : response(null, 503));
  await app.api.init(); app.api.retry(44); await app.confirmations[0].confirm();
  assert.match(app.fields.message.textContent, /재조회에 실패/);
  assert.equal(app.buttons().length, 0);
});

test('late retry refresh cannot overwrite the message of a newer filter query', async () => {
  let finish, reads = 0;
  const app = setup(async (url, options) => options.method === 'POST' ? response('accepted')
    : ++reads === 2 ? new Promise(resolve => {finish = resolve;})
    : response(view({status: url.includes('status=DELIVERED') ? 'DELIVERED' : 'DEAD_LETTER'})));
  await app.api.init(); app.api.retry(44);
  const retrying = app.confirmations[0].confirm();
  // POST 응답 뒤 자동 재조회가 대기 상태에 도달하도록 한 이벤트 루프만 진행한다.
  await new Promise(setImmediate);
  assert.equal(typeof finish, 'function');
  await app.api.load(0, 'DELIVERED');
  const message = app.fields.message.textContent;
  finish(response(view())); await retrying;
  assert.equal(app.fields.filter.value, 'DELIVERED');
  assert.equal(app.fields.message.textContent, message);
});
