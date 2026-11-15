const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const dir = path.resolve(__dirname, '../../main/resources/static/js');
const select = fs.readFileSync(path.join(dir, 'select.js'), 'utf8');
const app = fs.readFileSync(path.join(dir, 'app.js'), 'utf8');
const modalHandlers = app.slice(app.indexOf('/* ESC 키로 열린 모달 닫기 */'),
  app.indexOf('function showToast(', app.indexOf('/* ESC 키로 열린 모달 닫기 */')));

function screen(open = true) {
  const listeners = [], removed = [], focused = [];
  const draft = { name: 'QA 프로젝트', step: 3, policy: '17' };
  const overlay = { style: { display: 'flex' }, querySelector: () => ({ click() {
    overlay.style.display = 'none'; draft.name = ''; draft.step = 1;
  } }) };
  const document = {
    readyState: 'loading',
    addEventListener(type, listener) { if (type === 'keydown') listeners.push(listener); },
    querySelectorAll: selector => selector === '.modal-overlay' ? [overlay] : [],
  };
  const context = vm.createContext({ document, window: { addEventListener() {} },
    MutationObserver: class { observe() {} }, getComputedStyle: el => el.style,
    closeModal: id => removed.push(id), CSS: { escape: value => value } });
  // Preserve actual application listener registration order and select handlers.
  vm.runInContext(modalHandlers, context);
  vm.runInContext(select.replace('window.DeploySelect = { scan: scan, sync: sync };',
    'window.DeploySelect = { scan: scan, sync: sync, onKey: onKey, setOpen: function(api) { openOne = api; } };'), context);
  const attrs = { 'aria-expanded': String(open), 'aria-activedescendant': 'policy-17' };
  const list = { hidden: !open, parentNode: { removeChild(el) { el.parentNode = null; } } };
  const api = { list, btn: { setAttribute: (key, value) => { attrs[key] = value; },
    removeAttribute: key => { delete attrs[key]; }, focus: () => focused.push('combobox') },
    wrap: { classList: { remove() {} } }, sel: { value: draft.policy } };
  if (open) context.window.DeploySelect.setOpen(api);
  function key(value) {
    const event = { key: value, defaultPrevented: false, stopped: false,
      preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; } };
    context.window.DeploySelect.onKey(api, event);
    if (!event.stopped) listeners.forEach(listener => listener(event));
    return event;
  }
  return { key, api, attrs, draft, overlay, focused, removed };
}

test('Escape closes approval dropdown, preserves wizard step and draft, and restores combobox focus', () => {
  const s = screen();
  const event = s.key('Escape');
  assert.equal(s.api.list.hidden, true);
  assert.equal(s.api.list.parentNode, null);
  assert.equal(s.attrs['aria-expanded'], 'false');
  assert.equal(s.attrs['aria-activedescendant'], undefined);
  assert.deepEqual(s.focused, ['combobox']);
  assert.equal(s.overlay.style.display, 'flex');
  assert.deepEqual(s.draft, { name: 'QA 프로젝트', step: 3, policy: '17' });
  assert.equal(s.api.sel.value, '17');
  assert.deepEqual(s.removed, []);
  assert.equal(event.defaultPrevented, true);
});

test('second Escape after list closure retains existing wizard close behavior', () => {
  const s = screen();
  s.key('Escape');
  s.key('Escape');
  assert.equal(s.overlay.style.display, 'none');
  assert.equal(s.draft.name, '');
  assert.deepEqual(s.removed, ['modal-alert', 'modal-confirm']);
});

test('Escape with an initially closed combobox still reaches modal close handlers', () => {
  const s = screen(false);
  assert.equal(s.key('Escape').defaultPrevented, false);
  assert.equal(s.overlay.style.display, 'none');
  assert.deepEqual(s.focused, []);
});

test('Tab closes dropdown without consuming native focus navigation or dismissing wizard', () => {
  const s = screen();
  const event = s.key('Tab');
  assert.equal(s.api.list.hidden, true);
  assert.equal(event.defaultPrevented, false);
  assert.equal(event.stopped, false);
  assert.deepEqual(s.focused, []);
  assert.equal(s.overlay.style.display, 'flex');
  assert.deepEqual(s.draft, { name: 'QA 프로젝트', step: 3, policy: '17' });
});
