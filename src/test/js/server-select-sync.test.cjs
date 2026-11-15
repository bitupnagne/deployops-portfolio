const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function executePageFunctions(file, startMarker, endMarker, functions, syncIds) {
  const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages', file), 'utf8');
  const start = html.indexOf(startMarker);
  const end = html.indexOf(endMarker, start);
  assert.notEqual(start, -1, `${file} function block starts`);
  assert.notEqual(end, -1, `${file} function block ends`);
  const elements = new Map();
  const synced = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, { id, value: '', checked: false, style: {}, textContent: '' });
    return elements.get(id);
  };
  const context = vm.createContext({
    document: { getElementById: element },
    DeployAgent: { toggleFields: () => {} },
    window: { DeploySelect: { sync: select => synced.push([select.id, select.value]) } }
  });
  vm.runInContext(html.slice(start, end), context);
  functions(context, element);
  assert.deepEqual(synced, syncIds);
}

test('server inventory editor synchronizes environment, type, auth, and group labels', () => {
  executePageFunctions(
    'servers.html',
    '  function openServerModalFromBtn(btn)',
    '  function closeServerModal()',
    context => context.openServerModal(
      '12', 'uiqa-devweb1', '198.18.36.20', 'TEST', 'WEB', '2022', 'deploy',
      'SSH_KEY', '/var/www/app', '20', '4', 'false', ''
    ),
    [
      ['server-env', 'TEST'],
      ['server-type', 'WEB'],
      ['server-control', 'AGENT'],
      ['server-group', '4']
    ]
  );
});

test('server-group editor synchronizes server type and authentication labels', () => {
  executePageFunctions(
    'server-groups.html',
    '  function fillServerModal(d)',
    '  function closeServerModal()',
    context => context.fillServerModal({
      id: '12', groupId: '4', groupName: 'TEST-WEB Group', name: 'uiqa-devweb1',
      ip: '198.18.36.20', type: 'WEB', ssh: '2022', account: 'deploy',
      auth: 'SSH_KEY', path: '/var/www/app', order: '20'
    }),
    [
      ['sm-type', 'WEB'],
      ['sm-control', 'AGENT']
    ]
  );
});
