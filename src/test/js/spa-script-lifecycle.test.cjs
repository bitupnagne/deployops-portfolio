const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = name => fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js', name), 'utf8');
const app = read('app.js').split('/* 뒤로/앞으로')[0];
const backup = read('nexus-backup.js');
const flush = () => new Promise(setImmediate);
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }

// Deliberately model browser insertion: fragment scripts are inert until replaced;
// external files execute only when their simulated network load completes.
function fixture() {
  const events = new Map(), timers = new Map(), calls = [], inserted = [], pendingScripts = [];
  let timerId = 0, elements = {}, scripts = [], html = '';
  const window = {
    innerWidth: 1200,
    addEventListener(name, fn) { if (!events.has(name)) events.set(name, new Set()); events.get(name).add(fn); },
    removeEventListener(name, fn) { events.get(name)?.delete(fn); },
    dispatchEvent(e) { for (const fn of [...(events.get(e.type) || [])]) fn(e); },
    location: { assign() {} }
  };
  class Element {
    constructor(tag = 'div') { this.tag = tag; this.isConnected = true; this.children = []; this.dataset = {}; this.style = {}; this.textContent = ''; this.value = ''; this.checked = false; this.attributes = []; }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; this.writes = (this.writes || 0) + 1; }
    contains(node) { return this === node || this.children.some(n => n.contains(node)); }
    querySelectorAll(tag) { return this.children.flatMap(n => [...(n.tag === tag ? [n] : []), ...n.querySelectorAll(tag)]); }
    focus() { document.activeElement = this; }
    setAttribute(name, value) { this.attributes.push({ name, value }); if (name === 'type') this.type = value; }
    hasAttribute(name) { return this.attributes.some(a => a.name === name); }
    getAttribute(name) { return this.attributes.find(a => a.name === name)?.value; }
    replaceWith(script) {
      this.isConnected = false; inserted.push(script);
      if (script.hasAttribute('src')) pendingScripts.push(script);
      else execute(script, script.textContent);
    }
  }
  function mount(nextHtml) {
    for (const element of Object.values(elements)) element.isConnected = false;
    for (const script of scripts) script.isConnected = false;
    elements = {}; scripts = []; html = nextHtml;
    if (html.includes('nexus-ops-context')) {
      for (const id of ['nexus-ops-context', 'nxbackup-body', 'nxbackup-ready', 'nxbackup-error', 'nxbackup-external', 'nxbackup-reason', 'nxbackup-refresh', 'nxbackup-request']) elements[id] = new Element();
      elements['nexus-ops-context'].dataset = { userId: '20', role: 'OPERATOR' };
    }
    for (const match of html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)) {
      const script = new Element('script'); script.textContent = match[2];
      for (const attr of match[1].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) script.setAttribute(attr[1], attr[2] || '');
      scripts.push(script);
    }
  }
  function execute(script, source) {
    document.currentScript = script;
    try { vm.runInContext(source, context); } finally { document.currentScript = null; }
  }
  const content = { isConnected: true, scrollTop: 0, querySelectorAll: () => scripts,
    contains: node => Object.values(elements).includes(node),
    set innerHTML(value) { mount(value); }, get innerHTML() { return html; } };
  const document = { hidden: false, activeElement: null,
    getElementById: id => id === 'content' ? content : elements[id] || null,
    querySelectorAll: () => [], querySelector: () => null, createElement: tag => new Element(tag) };
  const historyCalls = [];
  const context = vm.createContext({ window, document, Event: class { constructor(type) { this.type = type; } },
    URLSearchParams, console: { error() {} }, location: { pathname: '/deploy/dashboard', search: '' },
    history: { pushState: (...args) => historyCalls.push(args) },
    setTimeout: () => 1, clearTimeout() {}, setInterval: fn => { timers.set(++timerId, fn); return timerId; }, clearInterval: id => timers.delete(id),
    fetch: (...args) => { calls.push(args); return api.fetch(...args); }, showToast() {} });
  vm.runInContext(app, context);
  vm.runInContext('showPageLoader = function() {};', context);
  const api = { context, content, calls, timers, inserted, pendingScripts, historyCalls, window,
    element: id => elements[id], mount, fetch: async url => ({ ok: true, text: async () => '', json: async () => ({ success: true, data: [] }) }),
    async loadExternal(source = '') { const script = pendingScripts.shift(); assert.ok(script); execute(script, source); script.onload(); await flush(); return script; },
    runBackup() { vm.runInContext(backup, context); },
    page: () => vm.runInContext('currentPage', context) };
  return api;
}
const backupPage = '<div id="nexus-ops-context"></div><script src="/js/nexus-backup.js" defer></script>';

test('SPA runs external source and dependent inline in document order, preserving attributes', async () => {
  const f = fixture(); f.context.order = [];
  f.fetch = async () => ({ ok: true, text: async () => '<script>order.push("before")</script><script src="/js/one.js" nonce="trusted" defer></script><script>order.push("after")</script>' });
  const nav = f.context.navigate('nexus-operations'); await flush();
  assert.deepEqual(Array.from(f.context.order), ['before']); assert.equal(f.historyCalls.length, 0);
  const script = await f.loadExternal('order.push("external")'); await nav;
  assert.deepEqual(Array.from(f.context.order), ['before', 'external', 'after']);
  assert.equal(script.getAttribute('src'), '/js/one.js'); assert.equal(script.getAttribute('nonce'), 'trusted'); assert.ok(script.hasAttribute('defer')); assert.equal(script.async, false);
  assert.equal(f.page(), 'nexus-operations'); assert.equal(f.historyCalls[0][2], '/deploy/nexus-operations');
});

test('inline-only navigation executes immediately and full reload backup initializes once per context', async () => {
  const f = fixture(); f.fetch = async () => ({ ok: true, text: async () => '<script>window.inlineRan = true</script>' });
  await f.context.navigate('projects'); assert.equal(f.window.inlineRan, true);
  f.mount(backupPage); f.fetch = async () => ({ ok: true, json: async () => ({ success: true, data: [] }) });
  f.runBackup(); f.runBackup(); await flush();
  assert.equal(f.calls.filter(c => c[0] === '/api/v1/nexus/backup-requests').length, 1); assert.equal(f.timers.size, 1);
  assert.equal(f.element('nxbackup-body').children[0].children[0].textContent, '백업 요청이 없습니다.');
});

test('normal SPA backup performs GET, leaves with zero timers, and reentry has one fresh poll', async () => {
  const f = fixture(); f.fetch = async url => ({ ok: true, text: async () => url.includes('nexus-operations') ? backupPage : '<p>dashboard</p>', json: async () => ({ success: true, data: [] }) });
  let nav = f.context.navigate('nexus-operations'); await flush(); await f.loadExternal(backup); await nav;
  const first = [...f.timers.keys()][0]; assert.equal(f.timers.size, 1);
  await f.context.navigate('dashboard'); assert.equal(f.timers.size, 0);
  nav = f.context.navigate('nexus-operations'); await flush(); await f.loadExternal(backup); await nav;
  assert.equal(f.timers.size, 1); assert.notEqual([...f.timers.keys()][0], first);
  assert.equal(f.calls.filter(c => c[0] === '/api/v1/nexus/backup-requests').length, 2);
  f.window.dispatchEvent({ type: 'pagehide' }); assert.equal(f.timers.size, 0);
});

test('inflight GET after leaving cannot write to detached backup DOM or overlap polls', async () => {
  const f = fixture(), pending = deferred(); f.mount(backupPage);
  f.fetch = url => url.endsWith('/readiness')
    ? Promise.resolve({ ok: true, json: async () => ({ success: true, data: { ready: true } }) })
    : pending.promise;
  f.runBackup(); const oldBody = f.element('nxbackup-body'), oldError = f.element('nxbackup-error');
  await flush(); // Allow the actual async load to enter its deferred GET.
  [...f.timers.values()][0](); await flush(); assert.equal(f.calls.length, 2);
  assert.equal(f.calls.filter(c => c[0] === '/api/v1/nexus/backup-requests').length, 1);
  assert.equal(f.calls.filter(c => c[0].endsWith('/readiness')).length, 1);
  f.window.dispatchEvent({ type: 'deploy:page-leave' }); f.mount('<p>left</p>');
  pending.resolve({ ok: true, json: async () => ({ success: true, data: [] }) }); await flush();
  assert.equal(oldBody.writes || 0, 0); assert.equal(oldError.textContent, ''); assert.equal(f.timers.size, 0);
});

test('late POST success after leave does not toast or refresh the new page', async () => {
  const f = fixture(), pending = deferred(); f.mount(backupPage);
  f.context.document.querySelector = () => ({ content: 'csrf' });
  let toasts = 0; f.context.showToast = () => toasts++;
  f.fetch = async (url, options) => options ? pending.promise : { ok: true, json: async () => ({ success: true, data: url.endsWith('/readiness') ? { ready: true } : [] }) };
  f.runBackup(); await flush(); f.element('nxbackup-reason').value = 'maintenance'; f.element('nxbackup-external').checked = true; f.element('nxbackup-request').onclick();
  f.window.dispatchEvent({ type: 'deploy:page-leave' }); f.mount('<p>left</p>');
  pending.resolve({ ok: true, json: async () => ({ success: true, message: 'accepted' }) }); await flush();
  assert.equal(toasts, 0); assert.equal(f.calls.length, 3);
  assert.equal(f.calls.filter(c => c[1]?.method === 'POST').length, 1);
  assert.equal(f.calls.filter(c => c[0].endsWith('/readiness')).length, 1);
});

test('competing navigation while external load waits keeps newer page and URL', async () => {
  const f = fixture(); f.context.staleInline = false;
  f.fetch = async url => ({ ok: true, text: async () => url.includes('nexus') ? '<script src="/js/slow.js"></script><script>staleInline = true</script>' : '<p>projects</p>' });
  const old = f.context.navigate('nexus-operations'); await flush(); await f.context.navigate('projects');
  await f.loadExternal(''); await old;
  assert.equal(f.context.staleInline, false); assert.equal(f.page(), 'projects'); assert.equal(f.historyCalls.length, 1); assert.equal(f.historyCalls[0][2], '/deploy/projects'); assert.equal(f.content.innerHTML, '<p>projects</p>');
});

test('obsolete Nexus external execution cannot initialize the newer Nexus context', async () => {
  const f = fixture(); f.fetch = async () => ({ ok: true, text: async () => backupPage, json: async () => ({ success: true, data: [] }) });
  const old = f.context.navigate('nexus-operations'); await flush();
  const oldContext = f.element('nexus-ops-context');
  const newer = f.context.navigate('nexus-operations'); await flush();
  const newContext = f.element('nexus-ops-context'); assert.notEqual(newContext, oldContext); assert.equal(f.pendingScripts.length, 2);
  await f.loadExternal(backup); await old;
  assert.equal(f.calls.filter(c => c[0] === '/api/v1/nexus/backup-requests').length, 0);
  assert.equal(f.timers.size, 0); assert.equal(newContext.nexusBackupCleanup, undefined); assert.equal(f.historyCalls.length, 0);
  await f.loadExternal(backup); await newer;
  assert.equal(f.calls.filter(c => c[0] === '/api/v1/nexus/backup-requests').length, 1);
  assert.equal(f.timers.size, 1); assert.equal(typeof newContext.nexusBackupCleanup, 'function'); assert.equal(f.historyCalls.length, 1);
});

test('late fragment fetch and late script error cannot replace a newer page', async () => {
  const f = fixture(), pending = deferred(); f.fetch = url => url.includes('slow') ? pending.promise : Promise.resolve({ ok: true, text: async () => '<p>projects</p>' });
  const old = f.context.navigate('slow'); await f.context.navigate('projects'); pending.resolve({ ok: true, text: async () => '<p>obsolete</p>' }); await old;
  assert.equal(f.content.innerHTML, '<p>projects</p>'); assert.equal(f.historyCalls.length, 1);
  f.fetch = async () => ({ ok: true, text: async () => '<script src="/js/fail.js"></script>' });
  const fail = f.context.navigate('nexus-operations'); await flush();
  f.fetch = async () => ({ ok: true, text: async () => '<p>dashboard</p>' }); await f.context.navigate('dashboard');
  f.pendingScripts.shift().onerror(); await fail; assert.equal(f.page(), 'dashboard'); assert.equal(f.content.innerHTML, '<p>dashboard</p>');
});

test('external load failure is visible, suppresses dependent scripts and cleans initialized page', async () => {
  const f = fixture(); f.mount(backupPage); f.runBackup(); await flush();
  f.fetch = async () => ({ ok: true, text: async () => '<script src="/js/missing.js"></script><script>window.bad = true</script>' });
  const nav = f.context.navigate('nexus-operations'); await flush(); f.pendingScripts.shift().onerror(); await nav;
  assert.match(f.content.innerHTML, /페이지를 불러올 수 없습니다/); assert.equal(f.window.bad, undefined); assert.equal(f.timers.size, 0); assert.equal(f.historyCalls.length, 0);
});
