const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/server-groups.html'), 'utf8');
const script = html.slice(html.indexOf('  function openGroupModal(d)'), html.indexOf('  function deleteGroup('));

test('group modal preserves zero from a reopened persisted dataset and sends zero in update', async () => {
  const elements = {};
  const element = id => elements[id] ||= { value: '', style: {}, dispatchEvent() {} };
  const requests = [];
  const context = vm.createContext({
    document: { getElementById: element }, Event: class {},
    sgHeaders: {}, showToast() {}, setTimeout() {}, navigate() {},
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { json: async () => ({ success: true }) };
    }
  });
  vm.runInContext(script, context);
  context.openGroupModal();
  assert.equal(element('gm-interval').value, 30);
  context.openGroupModalFrom({ closest: () => ({ dataset: {
    id: '13', name: 'QA', env: 'PROD', interval: '0', desc: ''
  } }) });
  assert.equal(element('gm-interval').value, '0');
  context.submitGroup();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/v1/server-groups/13');
  assert.equal(requests[0].options.method, 'PUT');
  assert.equal(JSON.parse(requests[0].options.body).rollingIntervalSec, 0);
});
