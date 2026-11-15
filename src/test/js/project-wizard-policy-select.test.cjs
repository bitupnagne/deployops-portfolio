const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/projects.html'), 'utf8');
const selectScript = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/select.js'), 'utf8');
const suggestions = html.slice(html.indexOf('  function wizFillSuggestions()'), html.indexOf('  function wizPickImage()'));
const hint = html.slice(html.indexOf('  function wizPolicyHint()'), html.indexOf('  function loadGitlabProjects()'));

function wizard(policies, { disabled = false, initial = '' } = {}) {
  const options = [{ value: '', textContent: '지정 안 함', disabled: false }, ...policies];
  let value = initial;
  const select = { disabled, options,
    get value() { return value; }, set value(v) { value = String(v); },
    get selectedOptions() { return options.filter(o => o.value === value); },
    get selectedIndex() { return options.findIndex(o => o.value === value); },
    set selectedIndex(index) { value = options[index]?.value || ''; },
    _cs: { txt: { textContent: '지정 안 함' }, wrap: { classList: { toggle() {} } } } };
  select._cs.sel = select;
  const hintElement = { textContent: '', classList: { add() {}, remove() {} } };
  const context = vm.createContext({ window: { addEventListener() {} },
    document: { readyState: 'loading', addEventListener() {}, querySelectorAll: () => [],
      getElementById: id => id === 'wiz-policy' ? select : id === 'wiz-policy-hint' ? hintElement :
        id === 'wiz-image-repo' ? { value: 'nexus.local/service' } : null },
    MutationObserver: class { observe() {} }, _wizFilled: false, _selGitlab: { path: 'qa/project' } });
  vm.runInContext(selectScript, context);
  vm.runInContext(suggestions + hint, context);
  return { context, select, hint: hintElement, fill() { context.wizFillSuggestions(); },
    label: () => select._cs.txt.textContent };
}
const policy = (value, disabled = false) => ({ value, textContent: `승인 정책 ${value}`, disabled });

test('sole disabled policy stays unselected and displays no-policy instead of blocking registration', () => {
  const s = wizard([policy('17', true)]);
  s.fill();
  assert.equal(s.select.value, '');
  assert.equal(s.label(), '지정 안 함');
  assert.match(s.hint.textContent, /지정하지 않으면/);
});

test('sole enabled policy recommendation updates native value and actual custom-select label together', () => {
  const s = wizard([policy('17')]);
  s.fill();
  assert.equal(s.select.value, '17');
  assert.equal(s.label(), '승인 정책 17');
  assert.match(s.hint.textContent, /이 정책/);
});

test('disabled policies are excluded from the available single-policy recommendation', () => {
  const s = wizard([policy('16', true), policy('17')]);
  s.fill();
  assert.equal(s.select.value, '17');
  assert.equal(s.label(), '승인 정책 17');
});

test('multiple enabled policies stay unselected and an existing explicit choice is preserved', () => {
  const unselected = wizard([policy('16'), policy('17')]);
  unselected.fill();
  assert.equal(unselected.select.value, '');
  const chosen = wizard([policy('16'), policy('17')], { initial: '16' });
  chosen.fill();
  assert.equal(chosen.select.value, '16');
  assert.equal(chosen.label(), '승인 정책 16');
});

test('role-disabled selector receives no automatic policy even when only one policy is available', () => {
  const s = wizard([policy('17')], { disabled: true });
  s.fill();
  assert.equal(s.select.value, '');
  assert.equal(s.label(), '지정 안 함');
  assert.match(s.hint.textContent, /최고 관리자/);
});

test('reopened wizard programmatic reset synchronizes label back to unselected value', () => {
  const s = wizard([policy('17')]);
  s.fill();
  assert.equal(s.label(), '승인 정책 17');
  s.select.value = '';
  s.context.wizPolicyHint();
  assert.equal(s.label(), '지정 안 함');
  assert.equal(s.select.value, '');
});
