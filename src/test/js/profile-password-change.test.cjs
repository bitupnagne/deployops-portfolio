const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/profile.html'), 'utf8');
const script = html.slice(html.indexOf('  function getPageCsrf()'), html.indexOf('  function openGitlabModal()'));
const autoOpen = html.match(/<script th:if="[^\"]*forcePasswordChange[^\"]*">([\s\S]*?)<\/script>/)[1];

function page({ forced = true, url = 'http://localhost:9090/deploy/profile?pwchange=1&tab=account#password', response, failure } = {}) {
  const elements = new Map([
    ['pw-current', { value: 'Old!Password9' }],
    ['pw-new', { value: 'New!Password10' }],
    ['pw-confirm', { value: 'New!Password10' }],
    ['password-modal', { style: { display: 'flex' } }],
  ]);
  if (forced) elements.set('force-password-change-notice', { remove() { elements.delete('force-password-change-notice'); } });
  const requests = [], toasts = [], replacements = [], timers = [];
  const originalState = { pageId: 'profile', id: null, params: { pwchange: '1', tab: 'account' }, marker: 7 };
  const history = { state: originalState, replaceState(state, title, target) { replacements.push({ state, title, target }); this.state = state; } };
  const context = vm.createContext({
    URL, window: { location: { href: url } }, history,
    document: { getElementById: id => elements.get(id) || null, querySelector: () => ({ content: 'csrf' }) },
    showToast: (message, kind) => toasts.push({ message, kind }), setTimeout: fn => timers.push(fn),
    fetch: async (target, options) => {
      requests.push({ target, options });
      if (failure) throw new Error(failure);
      return response || { ok: true, redirected: false, headers: { get: () => 'application/json' }, json: async () => ({ success: true }) };
    },
  });
  vm.runInContext(script, context);
  return { context, elements, requests, toasts, replacements, timers, originalState };
}
const settled = () => new Promise(resolve => setImmediate(resolve));

test('forced change success removes warning and query without losing other URL or SPA state', async () => {
  const screen = page();
  screen.context.changePassword(); await settled();
  assert.equal(screen.elements.has('force-password-change-notice'), false);
  assert.equal(screen.elements.get('password-modal').style.display, 'none');
  for (const id of ['pw-current', 'pw-new', 'pw-confirm']) assert.equal(screen.elements.get(id).value, '');
  assert.equal(screen.replacements.length, 1);
  assert.equal(screen.replacements[0].target, '/deploy/profile?tab=account#password');
  assert.equal(screen.replacements[0].state.pageId, 'profile');
  assert.equal(screen.replacements[0].state.marker, 7);
  assert.equal(screen.replacements[0].state.params.tab, 'account');
  assert.equal('pwchange' in screen.replacements[0].state.params, false);
  assert.equal(screen.originalState.params.pwchange, '1', 'original history state must not be mutated');
  assert.equal(screen.requests[0].target, '/api/v1/profile/password');
  assert.equal(screen.requests[0].options.credentials, 'same-origin');
  assert.equal(screen.toasts.at(-1).kind, 'success');
  screen.context.openPasswordModal();
  assert.equal(screen.elements.get('password-modal').style.display, 'flex');
  for (const id of ['pw-current', 'pw-new', 'pw-confirm']) assert.equal(screen.elements.get(id).value, '');
});

test('normal profile password success retains existing URL/history behavior', async () => {
  const screen = page({ forced: false, url: 'http://localhost:9090/deploy/profile?tab=account#password' });
  screen.context.changePassword(); await settled();
  assert.equal(screen.elements.get('password-modal').style.display, 'none');
  assert.equal(screen.replacements.length, 0);
  assert.equal(screen.toasts.at(-1).kind, 'success');
  for (const id of ['pw-current', 'pw-new', 'pw-confirm']) assert.equal(screen.elements.get(id).value, '');
});

for (const [name, options] of [
  ['business rejection', { response: { ok: true, redirected: false, headers: { get: () => 'application/json' }, json: async () => ({ success: false, message: '현재 비밀번호가 올바르지 않습니다.' }) } }],
  ['HTTP rejection', { response: { ok: false, status: 403, redirected: false, headers: { get: () => 'application/json' }, json: async () => ({ message: '거부되었습니다.' }) } }],
  ['expired login session', { response: { ok: true, redirected: true, headers: { get: () => 'text/html' } } }],
  ['network failure', { failure: 'connection closed' }],
]) {
  test(`${name} keeps forced warning, URL, open modal and entered values`, async () => {
    const screen = page(options);
    screen.context.changePassword(); await settled();
    assert.equal(screen.elements.has('force-password-change-notice'), true);
    assert.equal(screen.elements.get('password-modal').style.display, 'flex');
    assert.equal(screen.elements.get('pw-new').value, 'New!Password10');
    assert.equal(screen.elements.get('pw-current').value, 'Old!Password9');
    assert.equal(screen.elements.get('pw-confirm').value, 'New!Password10');
    assert.equal(screen.replacements.length, 0);
    assert.equal(screen.toasts.at(-1).kind, 'error');
  });
}

test('client validation failure never submits or clears forced-change state', () => {
  const screen = page(); screen.elements.get('pw-confirm').value = 'Mismatch!9';
  screen.context.changePassword();
  assert.equal(screen.requests.length, 0);
  assert.equal(screen.elements.has('force-password-change-notice'), true);
  assert.equal(screen.elements.get('password-modal').style.display, 'flex');
  assert.equal(screen.replacements.length, 0);
});

test('queued initial auto-open cannot reopen the modal after successful change', async () => {
  const screen = page(); vm.runInContext(autoOpen, screen.context);
  screen.context.changePassword(); await settled();
  screen.timers[0]();
  assert.equal(screen.elements.get('password-modal').style.display, 'none');
  for (const id of ['pw-current', 'pw-new', 'pw-confirm']) assert.equal(screen.elements.get(id).value, '');
});
