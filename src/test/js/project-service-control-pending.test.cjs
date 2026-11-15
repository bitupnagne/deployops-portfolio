const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const source = html.slice(html.indexOf('  var serviceStatusLoaded ='), html.indexOf('  /* ── AI 로그 경로 탐색기'));
function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
function setup() {
  let buttons = [{ disabled: false }];
  const progress = { hidden: true, textContent: '', style: { display: 'none' } };
  const box = { value: '', set innerHTML(value) {
    this.value = value;
    buttons = Array.from(value.matchAll(/data-svc-control/g), () => ({ disabled: false }));
  }, get innerHTML() { return this.value; } };
  const elements = { 'project-data': { dataset: { id: '4' } }, 'svc-list': box, 'svc-control-progress': progress };
  const calls = [], confirms = [], toasts = [];
  const context = vm.createContext({ Promise, document: {
    getElementById: id => elements[id], querySelector: () => ({ content: 'csrf' }),
    querySelectorAll: () => buttons
  }, escHtml: value => String(value).replaceAll('<', '&lt;'),
  fetch(url, options) { const d = deferred(); calls.push({ url, options, ...d }); return d.promise; },
  openConfirm(title, message, yes) { confirms.push(yes); }, showToast(...args) { toasts.push(args); } });
  vm.runInContext(source, context);
  context.serviceStatusRows = [{ serverId: 1, serverName: 'was1', exists: true, running: false },
    { serverId: 2, serverName: 'was2', exists: true, running: true }];
  return { context, calls, confirms, toasts, progress, box, buttons: () => buttons,
    reply(call, data) { call.resolve({ json: async () => data }); } };
}
test('pending POST prevents duplicate actions and identifies target until final GET finishes', async () => {
  const s = setup();
  s.context.svcControl(1, 'start', 'was1');
  const complete = s.confirms[0]();
  await tick();
  assert.match(s.progress.textContent, /was1: 기동 확인 중/);
  assert.equal(s.progress.hidden, false);
  assert.equal(s.progress.style.display, 'block');
  assert.ok(s.buttons().every(b => b.disabled));
  s.context.svcControl(2, 'stop', 'was2');
  s.confirms[0](); // Repeated confirmation callback also refuses a second POST.
  await tick();
  assert.equal(s.confirms.length, 1);
  assert.equal(s.calls.length, 1);
  s.reply(s.calls[0], { success: true });
  await tick();
  assert.equal(s.calls[1].options, undefined);
  assert.ok(s.context.serviceControlPending);
  s.reply(s.calls[1], { success: true, data: s.context.serviceStatusRows });
  await complete;
  assert.equal(s.context.serviceControlPending, null);
  assert.equal(s.progress.hidden, true);
  assert.equal(s.progress.style.display, 'none');
  assert.ok(s.buttons().every(b => !b.disabled));
});
test('read-only manual refresh rerender keeps all service action buttons disabled while POST waits', async () => {
  const s = setup();
  s.context.svcControl(1, 'start', '<was1>');
  const complete = s.confirms[0]();
  await tick();
  const refresh = s.context.loadServiceStatus(true);
  s.reply(s.calls[1], { success: true, data: s.context.serviceStatusRows });
  await refresh;
  assert.equal(s.buttons().length, 3);
  assert.ok(s.buttons().every(b => b.disabled));
  assert.match(s.progress.textContent, /<was1>/); // textContent, never injected into markup.
  s.reply(s.calls[0], { success: true });
  await tick();
  s.reply(s.calls[2], { success: true, data: s.context.serviceStatusRows });
  await complete;
  assert.ok(s.buttons().every(b => !b.disabled));
});
test('POST network rejection and final GET rejection release pending state and permit another action', async () => {
  const s = setup();
  s.context.svcControl(1, 'stop', 'was1');
  const complete = s.confirms[0]();
  await tick();
  assert.match(s.progress.textContent, /정지 중/);
  s.calls[0].reject(new Error('offline'));
  await tick();
  assert.ok(s.context.serviceControlPending);
  s.calls[1].reject(new Error('offline'));
  await complete;
  assert.equal(s.context.serviceControlPending, null);
  assert.equal(s.progress.hidden, true);
  assert.equal(s.toasts[0][1], 'error');
  s.context.svcControl(2, 'restart', 'was2');
  assert.equal(s.confirms.length, 2);
});
test('API failure still refreshes status and existing unsupported/status-unknown guards stay closed', async () => {
  const s = setup();
  s.context.serviceStatusRows[1].controlSupported = false;
  s.context.svcControl(2, 'start', 'was2');
  assert.equal(s.confirms.length, 0);
  s.context.serviceStatusRows[1].controlSupported = true;
  s.context.serviceStatusRows[1].statusKnown = false;
  s.context.svcControl(2, 'start', 'was2');
  assert.equal(s.confirms.length, 0);
  s.context.svcControl(1, 'start', 'was1');
  const complete = s.confirms[0]();
  await tick();
  s.reply(s.calls[0], { success: false, message: 'denied' });
  await tick();
  s.reply(s.calls[1], { success: false, message: 'unavailable' });
  await complete;
  assert.equal(s.context.serviceControlPending, null);
  assert.ok(s.toasts.some(t => t[0] === 'denied' && t[1] === 'error'));
  assert.match(html, /id="svc-control-progress"[^>]*role="status"[^>]*aria-live="polite"/);
});
