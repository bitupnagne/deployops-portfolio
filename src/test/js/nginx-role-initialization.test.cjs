const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync('src/main/resources/templates/pages/nginx-policies.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

test('APPROVER page initializes and retains approval actions without the operator creation form', async () => {
  const requests = [];
  const confirmations = [];
  const context = vm.createContext({
    document: {
      getElementById: () => null,
      querySelector: selector => ({content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'qa-token'}),
    },
    fetch: (url, options) => {
      requests.push({url, options});
      return Promise.resolve({ok: true, url, headers: {get: () => 'application/json'}, json: () => Promise.resolve({success: true, data: []})});
    },
    window: {location: {origin: 'http://qa.invalid'}}, URL,
    openConfirm: (title, message, action) => confirmations.push(action),
    showToast: () => {}, navigate: () => {},
  });
  assert.doesNotThrow(() => vm.runInContext(script, context));
  assert.equal(requests[0].url, '/api/v1/nginx-changes/certificates/expiring');
  context.approveNginx(4);
  assert.equal(confirmations.length, 1);
  confirmations[0]();
  assert.equal(requests[1].url, '/api/v1/nginx-changes/4/approve');
  assert.equal(requests[1].options.method, 'POST');
  assert.equal(requests[1].options.headers['X-CSRF-TOKEN'], 'qa-token');
  await new Promise(resolve => setImmediate(resolve));
});
