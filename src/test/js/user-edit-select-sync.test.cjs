const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function page(customSelect = true) {
  const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/users.html'), 'utf8');
  const start = html.indexOf('  function openUserModalFromBtn(btn)');
  const end = html.indexOf('  function closeUserModal()', start);
  assert.ok(start >= 0 && end > start);
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {id, value:'', textContent:'', style:{}, dataset:{}});
    return elements.get(id);
  };
  const options = {
    'user-role': [{value:'DEVELOPER', label:'DEVELOPER'}, {value:'APPROVER', label:'APPROVER'}],
    'user-active': [{value:'true', label:'활성'}, {value:'false', label:'비활성'}],
    'user-team': [{value:'', label:'미소속'}, {value:'71', label:'QA 팀'}],
  };
  const labels = {'user-role':'DEVELOPER', 'user-active':'활성', 'user-team':'미소속'};
  const synced = [];
  const window = customSelect ? {DeploySelect:{sync(select) {
    assert.ok(Object.hasOwn(options, select.id), 'only the three user form selects are synchronized');
    assert.notEqual(element('user-modal').style.display, 'flex', 'label is ready before showing the dialog');
    labels[select.id] = options[select.id].find(option => option.value === select.value).label;
    synced.push(select.id);
  }}} : {};
  const context = vm.createContext({document:{getElementById:element}, window});
  vm.runInContext(html.slice(start, end), context);
  return {context, element, labels, synced};
}

test('editing an inactive approver displays saved status, role and team immediately', () => {
  const ui = page();
  ui.context.openUserModalFromBtn({dataset:{id:'81', name:'QA 사용자', loginid:'qa-new', role:'APPROVER', active:'false', gitlabusername:'', teamid:'71'}});
  assert.equal(ui.element('user-active').value, 'false');
  assert.equal(ui.labels['user-active'], '비활성');
  assert.equal(ui.labels['user-role'], 'APPROVER');
  assert.equal(ui.labels['user-team'], 'QA 팀');
  assert.deepEqual(ui.synced, ['user-role', 'user-active', 'user-team']);
  assert.equal(ui.element('user-loginid').disabled, true);
  assert.equal(ui.element('user-modal').style.display, 'flex');
});

test('opening add after editing resets all three displayed labels and initial-password requirement', () => {
  const ui = page();
  ui.context.openUserModal('81', 'QA', 'qa-new', 'APPROVER', false, '', '71');
  ui.element('user-modal').style.display = 'none';
  ui.element('user-password').value = 'old-unsaved-value';
  ui.context.openUserModal();
  assert.deepEqual(ui.labels, {'user-role':'DEVELOPER', 'user-active':'활성', 'user-team':'미소속'});
  assert.equal(ui.element('user-password').value, '');
  assert.equal(ui.element('user-password').required, true);
  assert.equal(ui.element('user-loginid').disabled, false);
});

test('switching between saved users preserves the new native values before label synchronization', () => {
  const ui = page();
  ui.context.openUserModal('81', 'QA', 'qa-new', 'APPROVER', 'false', '', '71');
  ui.element('user-modal').style.display = 'none';
  ui.context.openUserModal('82', 'QA2', 'qa-new2', 'DEVELOPER', 'true', '', '');
  assert.deepEqual(ui.labels, {'user-role':'DEVELOPER', 'user-active':'활성', 'user-team':'미소속'});
  assert.equal(ui.element('user-edit-id').value, '82');
});

test('native form behavior remains available when the custom-select module is absent', () => {
  const ui = page(false);
  ui.context.openUserModal('81', 'QA', 'qa-new', 'APPROVER', false, '', '71');
  assert.equal(ui.element('user-role').value, 'APPROVER');
  assert.equal(ui.element('user-active').value, 'false');
  assert.equal(ui.element('user-team').value, '71');
  assert.equal(ui.element('user-modal').style.display, 'flex');
  assert.deepEqual(ui.synced, []);
});
