const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/deploy-loading.js'), 'utf8');
class Element {
  constructor(tag = 'DIV') { this.tagName = tag; this.attrs = new Map(); this.style = { position: '' }; this.children = []; this.isConnected = true; this.nodeType = 1; this.scrollLeft = 29; this.scrollTop = 71; this.textContent = ''; }
  getAttribute(key) { return this.attrs.has(key) ? this.attrs.get(key) : null; }
  hasAttribute(key) { return this.attrs.has(key); }
  setAttribute(key, value) { this.attrs.set(key, value); }
  removeAttribute(key) { this.attrs.delete(key); }
  appendChild(child) { child.parentElement = this; this.children.push(child); }
  remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(node => node !== this); this.isConnected = false; }
  closest() { return null; }
  matches() { return true; }
  querySelectorAll() { return []; }
  querySelector() { return this.html?.includes('data-deploy-loading') ? {} : null; }
  set innerHTML(value) { this.html = value; this.textContent = ''; this.children = []; }
  get innerHTML() { return this.html || ''; }
}
function fixture(nodes = []) {
  const events = new Map(), timers = new Map(); let changed, timerId = 0;
  const document = { documentElement: {}, createElement: () => new Element(), querySelectorAll: () => nodes };
  const window = { addEventListener: (type, fn) => events.set(type, fn) };
  vm.runInNewContext(source, { document, window, queueMicrotask,
    setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
    getComputedStyle: node => ({ position: node.style.position || 'static' }),
    MutationObserver: class { constructor(fn) { changed = fn; } observe() {} } });
  return { api: window.DeployLoading, advance: () => { Array.from(timers.values()).forEach(fn => fn()); timers.clear(); }, changed: target => changed([{ target }]), fire: type => events.get(type)?.() };
}
const flush = () => new Promise(setImmediate);
test('loading overlay retains old rows and scroll while stale completion cannot release the latest request', () => {
  const b = fixture(), host = new Element(), row = new Element(); host.appendChild(row);
  host.setAttribute('aria-busy', 'false');
  const first = b.api.start(host), second = b.api.start(host); first();
  assert.equal(host.getAttribute('aria-busy'), 'true'); assert.equal(host.children[0], row);
  assert.equal(row.getAttribute('inert'), ''); assert.equal(host.scrollLeft, 29); assert.equal(host.scrollTop, 71);
  second(); assert.equal(host.getAttribute('aria-busy'), 'false'); assert.equal(row.hasAttribute('inert'), false); assert.equal(host.style.position, '');
});
test('pagehide clears active overlays and preserves preexisting inert and positioned surfaces', () => {
  const b = fixture(), host = new Element(), row = new Element(); row.setAttribute('inert', ''); host.appendChild(row); host.style.position = 'sticky';
  b.api.start(host); b.fire('pagehide');
  assert.equal(host.children.length, 1); assert.equal(host.hasAttribute('aria-busy'), false); assert.equal(row.hasAttribute('inert'), true); assert.equal(host.style.position, 'sticky');
});
test('loading status becomes aria-hidden skeleton and error restores prior busy without changing error text', async () => {
  const status = new Element('P'); status.textContent = '사용자 목록을 불러오는 중입니다.'; status.setAttribute('aria-busy', 'false');
  const b = fixture([status]); await flush();
  assert.match(status.innerHTML, /aria-hidden="true"/); assert.equal(status.getAttribute('aria-busy'), 'true');
  status.html = ''; status.textContent = '조회 중 오류가 발생했습니다.'; b.changed(status); await flush();
  assert.equal(status.getAttribute('aria-busy'), 'false'); assert.equal(status.textContent, '조회 중 오류가 발생했습니다.');
});
test('empty state, save completion and contextual recovery warning remain visible', async () => {
  const values = ['조건에 맞는 기록이 없습니다.', '설정을 저장했습니다.', '원문은 불러왔으며 연결할 서버를 확인해 주세요.'];
  const nodes = values.map(text => { const node = new Element('P'); node.textContent = text; return node; });
  fixture(nodes); await flush();
  assert.deepEqual(nodes.map(node => node.textContent), values); assert.equal(nodes.some(node => node.hasAttribute('aria-busy')), false);
});
test('simple successful retrieval notice is hidden while application-owned busy cleanup is respected', async () => {
  const status = new Element('P'); status.textContent = '파일 로그를 불러왔습니다.'; fixture([status]); await flush(); assert.equal(status.textContent, '');
  const loading = new Element('P'); loading.textContent = '조회 중…'; const b = fixture([loading]); await flush();
  loading.html = ''; loading.textContent = '표시할 로그가 없습니다.'; loading.removeAttribute('aria-busy'); b.changed(loading); await flush();
  assert.equal(loading.hasAttribute('aria-busy'), false); assert.equal(loading.textContent, '표시할 로그가 없습니다.');
});
test('tall surfaces produce bounded visible skeleton rows', () => {
  const b = fixture(), host = new Element(); host.clientHeight = 100000; b.api.start(host);
  assert.equal((host.children[0].innerHTML.match(/class="deploy-skeleton-bar"/g) || []).length, 40);
});
test('fast queries cancel visual loading before it paints and slow queries reveal a skeleton after the display threshold', () => {
  const b = fixture(), host = new Element(); let stop = b.api.start(host), mask = host.children[0];
  assert.equal(mask.style.opacity, '0'); stop(); b.advance(); assert.equal(mask.style.opacity, '0');
  stop = b.api.start(host); mask = host.children[0]; b.advance(); assert.equal(mask.style.opacity, '1'); stop();
});

function navigationFixture() {
  const app = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/app.js'), 'utf8');
  const requests = [], history = [], toast = [], events = [];
  const table = new Element(), freshTable = new Element(), filter = new Element('INPUT');
  table.childNodes = ['old']; freshTable.childNodes = ['new'];
  table.classList = { add() {} }; table.replaceChildren = (...children) => { table.childNodes = children; };
  const content = new Element(); content.scrollTop = 410; content.children = [filter, table];
  content.querySelectorAll = selector => selector === '.table-wrap' ? [table] : [];
  table.getClientRects = () => [{}];
  const next = { querySelectorAll: selector => selector === '.table-wrap' ? [freshTable] : [] };
  const classes = active => { const values = new Set(active ? ['active'] : []); return { contains: key => values.has(key), add: key => values.add(key), remove: key => values.delete(key), toggle: (key, on) => on ? values.add(key) : values.delete(key) }; };
  const oldNav = Object.assign(new Element('A'), { classList: classes(true), dataset: { page: 'audit' } });
  const newNav = Object.assign(new Element('A'), { classList: classes(false), dataset: { page: 'users' } });
  const breadcrumb = { innerHTML: '감사 로그' };
  const document = { querySelectorAll: selector => selector === '.nav-item' ? [oldNav, newNav] : [], querySelector: () => newNav,
    getElementById: id => id === 'content' ? content : id === 'breadcrumb' ? breadcrumb : null };
  const location = { pathname: '/deploy/audit', search: '?actor=before', href: 'http://local/deploy/audit?actor=before' };
  const window = { location, scrollY: 500, scrollTo() {}, addEventListener() {}, dispatchEvent: event => events.push(event.type), innerWidth: 1400 };
  const context = vm.createContext({ window, location, document, AbortController, URLSearchParams,
    history: { pushState: (state, title, url) => history.push(url) },
    Event: class { constructor(type) { this.type = type; } }, console: { error() {} },
    DOMParser: class { parseFromString() { return next; } },
    fetch: (url, options) => new Promise(resolve => requests.push({ url, options, resolve })),
    showToast: message => toast.push(message) });
  document.querySelector = () => null;
  vm.runInContext(app.slice(0, app.indexOf('/* 뒤로/앞으로')), context);
  context.showToast = message => toast.push(message);
  return { context, table, filter, content, requests, history, toast, events, oldNav, newNav, breadcrumb,
    respond(index, ok = true, html = 'rows') { requests[index].resolve({ ok, status: ok ? 200 : 503, text: async () => html }); } };
}
test('same-page query swaps result rows only and cancels obsolete transport without moving filter or scroll', async () => {
  const b = navigationFixture(); const first = b.context.navigate('audit', null, { actor: 'old', page: '1' });
  const second = b.context.navigate('audit', null, { actor: 'latest', page: '2' });
  assert.equal(b.requests[0].options.signal.aborted, true);
  b.respond(1); await second; b.respond(0); await first;
  assert.equal(b.table.childNodes[0], 'new'); assert.equal(b.content.children[0], b.filter); assert.equal(b.content.scrollTop, 410);
  assert.deepEqual(b.history, ['/deploy/audit?actor=latest&page=2']); assert.deepEqual(b.events, []);
});

test('selected navigation exposes one aria-current page and clears the prior route', () => {
  const b = navigationFixture();
  b.context.syncNavigationCurrent('users');
  assert.equal(b.newNav.getAttribute('aria-current'), 'page');
  assert.equal(b.newNav.classList.contains('active'), true);
  assert.equal(b.oldNav.hasAttribute('aria-current'), false);
  assert.equal(b.oldNav.classList.contains('active'), false);
});

test('history restores filter values in the same native node without dispatching a new query', () => {
  const b = navigationFixture();
  Object.assign(b.filter, { id: 'filter-actor', type: 'text', value: 'currently-typed' });
  const selector = '.filter-bar input[id],.filter-bar select[id],.filter-bar textarea[id]';
  b.content.querySelectorAll = value => value === selector ? [b.filter] : [];
  b.context.restoreQueryFilters(b.content, { getElementById: id => id === 'filter-actor'
    ? { tagName: 'INPUT', value: 'earlier-query' } : null });
  assert.equal(b.content.children[0], b.filter);
  assert.equal(b.filter.value, 'earlier-query');
  assert.equal(b.requests.length, 0);
});

test('an unknown selection parameter cannot use the result-only partial query path', async () => {
  const b = navigationFixture();
  const pending = b.context.navigate('audit', null, { actor: 'same', selectedItem: 'different-context' });
  b.respond(0); await pending;
  assert.equal(b.content.innerHTML, 'rows');
  assert.deepEqual(b.events, ['deploy:page-leave']);
  assert.equal(b.toast.length, 0);
});

test('Back from another rendered page cannot mix audit rows into the prior page shell', async () => {
  const b = navigationFixture();
  const users = b.context.navigate('users');
  b.respond(0, true, '<h1>Users</h1>'); await users;
  assert.equal(vm.runInContext('currentPage', b.context), 'users');
  // A real popstate updates location before the asynchronous page renderer runs.
  b.context.location.pathname = '/deploy/audit';
  b.context.location.search = '?actor=earlier';
  const back = b.context.navigate('audit', null, { actor: 'earlier' }, { fromPopState: true });
  b.respond(1, true, '<h1>Audit</h1>'); await back;
  assert.equal(b.content.innerHTML, '<h1>Audit</h1>');
  assert.equal(vm.runInContext('currentPage', b.context), 'audit');
  assert.deepEqual(b.events, ['deploy:page-leave', 'deploy:page-leave']);
  assert.equal(b.toast.length, 0);
});
test('current navigation transport failure preserves old result and emits an error instead of reloading the document', async () => {
  const b = navigationFixture(), pending = b.context.navigate('users');
  b.respond(0, false); await pending;
  assert.equal(b.table.childNodes[0], 'old'); assert.equal(b.toast.length, 1); assert.deepEqual(b.history, []); assert.deepEqual(b.events, []);
  assert.equal(b.oldNav.classList.contains('active'), true); assert.equal(b.newNav.classList.contains('active'), false); assert.equal(b.breadcrumb.innerHTML, '감사 로그');
});
