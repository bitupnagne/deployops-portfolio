const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');
const layout = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/layout/main.html'), 'utf8');
const begin = app.indexOf('const commonModalFocus =');
const modalSource = app.slice(begin, app.indexOf('function showBlockers(', begin));
const escapeBegin = app.indexOf('/* ESC 키로 열린 모달 닫기 */');
const existingEscape = app.slice(escapeBegin, app.indexOf('function showToast(', escapeBegin));

function screen() {
  const nodes = {}, listeners = [], focused = [];
  const document = {
    activeElement: null,
    getElementById: id => nodes[id],
    querySelectorAll: () => [],
    addEventListener(type, fn, capture = false) { listeners.push({ type, fn, capture }); },
    removeEventListener(type, fn, capture = false) {
      const index = listeners.findIndex(x => x.type === type && x.fn === fn && x.capture === capture);
      if (index >= 0) listeners.splice(index, 1);
    }
  };
  function dispatch(type, event) {
    for (const listener of [...listeners].sort((a, b) => Number(b.capture) - Number(a.capture))) {
      if (event.immediateStopped) break;
      if (listener.type === type) listener.fn(event);
    }
  }
  function node(id, parent = null) {
    const classes = new Set(), attrs = {};
    const n = { id, parent, isConnected: true, disabled: false, tabIndex: 0, hidden: false,
      style: { visibility: 'visible' }, textContent: '', children: [], onclick: null,
      classList: { add: v => classes.add(v), remove: v => classes.delete(v), contains: v => classes.has(v) },
      matches: selector => selector === ':disabled' && n.disabled,
      getAttribute: key => attrs[key], setAttribute(key, value) { attrs[key] = value; },
      contains(el) { return el === n || n.children.some(child => child.contains(el)); },
      getClientRects() {
        return n.hidden || !n.isConnected || (parent && parent.id.startsWith('modal-')
          && !parent.classList.contains('open')) ? [] : [{}];
      },
      focus() { document.activeElement = n; focused.push(id); dispatch('focusin', { target: n }); },
      click() { if (n.onclick) n.onclick(); },
      querySelector(selector) { return selector === '[role="dialog"]' ? n.box : null; },
      querySelectorAll(selector) { return n.children.filter(child => child.button &&
        (selector === 'button' || (selector === 'button[id]' && child.publicId))); }
    };
    if (parent) parent.children.push(n);
    nodes[id] = n;
    return n;
  }
  const opener = node('background-refresh');
  opener.clicks = 0; opener.onclick = () => opener.clicks++;
  for (const kind of ['confirm', 'alert']) {
    const modal = node('modal-' + kind);
    modal.box = node(kind + '-dialog', modal); modal.box.tabIndex = -1;
    const html = layout.slice(layout.indexOf('id="modal-' + kind + '"'),
      layout.indexOf('<!--', layout.indexOf('id="modal-' + kind + '"') + 1));
    assert.match(html, /role="dialog" aria-modal="true"/);
    for (const name of kind === 'confirm' ? ['close', 'no', 'yes'] : ['close', 'confirm']) {
      const id = name === 'close' ? kind + '-close' : 'modal-' + kind + '-' + name;
      const button = node(id, modal); button.button = true; button.publicId = name !== 'close';
      if (name === 'close') button.onclick = () => context.closeModal(modal.id);
    }
    node('modal-' + kind + '-title'); node('modal-' + kind + '-content');
  }
  const context = vm.createContext({ document, getComputedStyle: n => n.style });
  vm.runInContext(modalSource, context);
  vm.runInContext(existingEscape, context);
  document.activeElement = opener;
  function key(value, shiftKey = false) {
    const e = { key: value, shiftKey, defaultPrevented: false, immediateStopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopImmediatePropagation() { this.immediateStopped = true; } };
    dispatch('keydown', e);
    return e;
  }
  return { context, nodes, document, focused, opener, key,
    active: () => document.activeElement.id,
    dynamicListeners: () => listeners.filter(x => x.capture).length };
}

test('confirm starts on No and eight backward/forward Tabs cannot reach the background Refresh', () => {
  const s = screen(); let yes = 0;
  s.context.openConfirm('확인', '내용', () => yes++);
  assert.equal(s.active(), 'modal-confirm-no');
  for (let i = 0; i < 8; i++) {
    assert.equal(s.key('Tab', true).defaultPrevented, true);
    assert.match(s.active(), /^(confirm-close|modal-confirm-(no|yes))$/);
  }
  for (let i = 0; i < 8; i++) s.key('Tab');
  assert.notEqual(s.active(), 'background-refresh');
  assert.equal(s.opener.clicks, 0); assert.equal(yes, 0);
  assert.equal(s.nodes['modal-confirm'].classList.contains('open'), true);
});

test('Tab wraps both directions and skips hidden, disabled, and negative-tabindex buttons', () => {
  const s = screen(); s.context.openConfirm('확인', '내용');
  s.nodes['modal-confirm-yes'].focus(); s.key('Tab'); assert.equal(s.active(), 'confirm-close');
  s.key('Tab', true); assert.equal(s.active(), 'modal-confirm-yes');
  s.nodes['modal-confirm-yes'].disabled = true;
  s.nodes['confirm-close'].hidden = true;
  s.key('Tab'); assert.equal(s.active(), 'modal-confirm-no');
  s.nodes['confirm-close'].hidden = false; s.nodes['confirm-close'].tabIndex = -1;
  s.key('Tab', true); assert.equal(s.active(), 'modal-confirm-no');
});

test('alert starts on Okay; Escape cancels without confirming and restores invoking element', () => {
  const s = screen(); let called = 0;
  s.context.openAlert('알림', '내용', () => called++);
  assert.equal(s.active(), 'modal-alert-confirm');
  assert.equal(s.key('Escape').defaultPrevented, true);
  assert.equal(called, 0); assert.equal(s.active(), 'background-refresh');
  assert.equal(s.dynamicListeners(), 0);
});

test('confirm Escape never calls Yes/No and blocks older global Escape handlers', () => {
  const s = screen(); let yes = 0, no = 0;
  s.context.openConfirm('확인', '내용', () => yes++, () => no++);
  const e = s.key('Escape');
  assert.equal(e.immediateStopped, true);
  assert.equal(yes, 0); assert.equal(no, 0);
  assert.equal(s.active(), 'background-refresh'); assert.equal(s.dynamicListeners(), 0);
});

test('No callback is once; saved handlers cannot confirm after programmatic close or reopen', () => {
  const s = screen(); let yes = 0, no = 0;
  s.context.openConfirm('old', '내용', () => yes++, () => no++);
  const oldYes = s.nodes['modal-confirm-yes'].onclick;
  const oldNo = s.nodes['modal-confirm-no'].onclick;
  oldNo(); oldNo(); oldYes();
  assert.equal(no, 1); assert.equal(yes, 0);
  s.context.openConfirm('new', '내용', () => yes++);
  oldYes(); assert.equal(yes, 0);
  const freshYes = s.nodes['modal-confirm-yes'].onclick;
  s.context.closeModal('modal-confirm'); freshYes();
  assert.equal(yes, 0); assert.equal(s.dynamicListeners(), 0);
});

test('Yes closes before nested Alert callback, keeps only active listeners, and calls each callback once', () => {
  const s = screen(); let yes = 0, okay = 0;
  s.context.openConfirm('확인', '내용', () => {
    yes++; s.context.openAlert('결과', '내용', () => okay++);
  });
  const oldYes = s.nodes['modal-confirm-yes'].onclick;
  oldYes(); oldYes();
  assert.equal(yes, 1); assert.equal(s.active(), 'modal-alert-confirm');
  assert.equal(s.nodes['modal-confirm'].classList.contains('open'), false);
  assert.equal(s.dynamicListeners(), 2);
  const oldOkay = s.nodes['modal-alert-confirm'].onclick;
  oldOkay(); oldOkay();
  assert.equal(okay, 1); assert.equal(s.dynamicListeners(), 0);
  assert.equal(s.active(), 'background-refresh');
});

test('X and backdrop/programmatic close clean listeners and never invoke business callbacks', () => {
  for (const via of ['X', 'backdrop', 'programmatic']) {
    const s = screen(); let yes = 0;
    s.context.openConfirm('확인', '내용', () => yes++);
    if (via === 'X') s.nodes['confirm-close'].click();
    else s.context.closeModal('modal-confirm');
    assert.equal(yes, 0); assert.equal(s.dynamicListeners(), 0);
    assert.equal(s.active(), 'background-refresh');
  }
});

test('reopening same dialog replaces listeners and retains original usable invoking element', () => {
  const s = screen(); s.context.openConfirm('old', '내용');
  s.context.openConfirm('new', '내용');
  assert.equal(s.dynamicListeners(), 2);
  assert.equal(s.active(), 'modal-confirm-no');
  s.context.closeModal('modal-confirm');
  assert.equal(s.active(), 'background-refresh'); assert.equal(s.dynamicListeners(), 0);
});

test('focus entering background is returned to the common dialog without triggering background action', () => {
  const s = screen(); s.context.openConfirm('확인', '내용');
  s.opener.focus();
  assert.equal(s.active(), 'modal-confirm-no'); assert.equal(s.opener.clicks, 0);
});

test('removed, disabled, or hidden invoking elements are not focused on close', () => {
  for (const change of [n => { n.isConnected = false; }, n => { n.disabled = true; }, n => { n.hidden = true; }]) {
    const s = screen(); s.context.openAlert('알림', '내용'); change(s.opener);
    const count = s.focused.length; s.context.closeModal('modal-alert');
    assert.equal(s.focused.length, count); assert.equal(s.dynamicListeners(), 0);
  }
});

test('no usable buttons retains focus on dialog and Tab cannot leak; unrelated modal close stays unchanged', () => {
  const s = screen(); s.context.openConfirm('확인', '내용');
  for (const id of ['confirm-close', 'modal-confirm-no', 'modal-confirm-yes']) s.nodes[id].disabled = true;
  s.key('Tab'); assert.equal(s.active(), 'confirm-dialog');
  assert.equal(s.key('Tab', true).defaultPrevented, true);
  s.context.closeModal('modal-tree');
  assert.equal(s.nodes['modal-confirm'].classList.contains('open'), true);
  s.key('Escape'); assert.equal(s.dynamicListeners(), 0);
});
