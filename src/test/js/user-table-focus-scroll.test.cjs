const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function table({left = 284.667, width = 602, scroll = 421.333, controlLeft = 882, controlWidth = 62.823, border = 0} = {}) {
  const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/users.html'), 'utf8');
  const start = html.indexOf('  (function keepUserTableFocusVisible()');
  const end = html.indexOf('  })();', start) + '  })();'.length;
  assert.ok(start >= 0 && end > start);
  const frames = [];
  let listener;
  const wrap = {clientLeft: border, clientWidth: width, scrollWidth: 1100, scrollLeft: scroll,
    scrollTop: 37, getBoundingClientRect: () => ({left}),
    addEventListener(type, fn) { assert.equal(type, 'focusin'); listener = fn; },
    contains(node) { return node === control; }};
  const control = {closest: () => control,
    getBoundingClientRect: () => ({left: controlLeft - (wrap.scrollLeft - scroll),
      right: controlLeft + controlWidth - (wrap.scrollLeft - scroll)})};
  const document = {querySelector(selector) { assert.equal(selector, '.user-list-table-wrap'); return wrap; },
    activeElement: control};
  vm.runInNewContext(html.slice(start, end), {document, requestAnimationFrame: fn => frames.push(fn)});
  return {wrap, control, document, focus: () => listener({target: control}),
    flush: () => frames.splice(0).forEach(fn => fn()),
    assertVisible() {
      const bounds = control.getBoundingClientRect();
      assert.ok(bounds.left >= left + border + 6 - 0.001);
      assert.ok(bounds.right <= left + border + width - 6 + 0.001);
      assert.equal(wrap.scrollTop, 37, 'only the table horizontal offset changes');
    }};
}

test('931px observed right-clipped action and its focus outline fit after focus', () => {
  const ui = table(); ui.focus(); ui.flush(); ui.assertVisible();
  assert.ok(ui.wrap.scrollLeft > 421.333);
});

test('Shift-Tab to a left-clipped action scrolls only the nearest needed distance', () => {
  const ui = table({scroll: 300, controlLeft: 270, controlWidth: 80, border: 1});
  ui.focus(); ui.flush(); ui.assertVisible();
  assert.ok(ui.wrap.scrollLeft < 300);
});

test('already visible actions do not move the region', () => {
  const ui = table({controlLeft: 400}); ui.focus(); ui.flush(); ui.assertVisible();
  assert.equal(ui.wrap.scrollLeft, 421.333);
});

test('rapid Tab changes do not scroll for a stale focus target', () => {
  const ui = table(); ui.focus(); ui.document.activeElement = {}; ui.flush();
  assert.equal(ui.wrap.scrollLeft, 421.333);
});

test('narrow and wide regions preserve the focused action geometry', () => {
  for (const width of [439, 951]) {
    const ui = table({left: 0, width, scroll: 0, controlLeft: 930, controlWidth: 64});
    ui.focus(); ui.flush(); ui.assertVisible();
    assert.ok(ui.wrap.scrollLeft <= 1100 - width);
  }
});
