const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function page(customSelect = true) {
  const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/teams.html'), 'utf8');
  const start = html.indexOf('  function openTeamModalFromBtn(btn)');
  const end = html.indexOf('  function closeTeamModal()', start);
  assert.ok(start >= 0 && end > start);
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, { id, value: '', textContent: '', style: {} });
    return elements.get(id);
  };
  const members = element('team-members');
  members.options = [{ value: '2', selected: false }, { value: '3', selected: false }];
  const leader = element('team-leader');
  leader.options = [{ value: '', textContent: '미지정' }, { value: '3', textContent: 'V35 QA 승인자 (qa-approver-v35)' }];
  let label = '미지정';
  const synced = [];
  const window = customSelect ? { DeploySelect: { sync(select) {
    assert.equal(select, leader);
    assert.notEqual(element('team-modal').style.display, 'flex', 'label synchronizes before the modal becomes visible');
    label = select.options.find(option => option.value === select.value).textContent;
    synced.push(select.value);
  } } } : {};
  const context = vm.createContext({ document: { getElementById: element }, window });
  vm.runInContext(html.slice(start, end), context);
  return { context, element, members, synced, label: () => label };
}

test('editing a saved team immediately displays its selected leader and retains selected members', () => {
  const ui = page();
  ui.context.openTeamModalFromBtn({ dataset: { id: '2', name: '운영팀', desc: '설명', leaderid: '3', memberids: '2,3' } });
  assert.equal(ui.element('team-leader').value, '3');
  assert.equal(ui.label(), 'V35 QA 승인자 (qa-approver-v35)');
  assert.deepEqual(ui.synced, ['3']);
  assert.ok(ui.members.options.every(option => option.selected));
  assert.equal(ui.element('team-modal').style.display, 'flex');
});

test('opening add after editing resets the visible leader label and selected members', () => {
  const ui = page();
  ui.context.openTeamModal('2', '운영팀', '', '3', ['2', '3']);
  ui.element('team-modal').style.display = 'none';
  ui.context.openTeamModal(null, '', '', null, []);
  assert.equal(ui.label(), '미지정');
  assert.equal(ui.element('team-leader').value, '');
  assert.deepEqual(ui.synced, ['3', '']);
  assert.ok(ui.members.options.every(option => !option.selected));
});

test('native select initialization still works when the custom select module is unavailable', () => {
  const ui = page(false);
  ui.context.openTeamModal('2', '운영팀', '', '3', ['2']);
  assert.equal(ui.element('team-leader').value, '3');
  assert.equal(ui.element('team-modal').style.display, 'flex');
  assert.deepEqual(ui.members.options.map(option => option.selected), [true, false]);
});
