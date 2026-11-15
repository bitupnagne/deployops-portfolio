const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/request-detail.html'), 'utf8');
const source = html.slice(html.indexOf('  function processApproval('), html.indexOf('  function requestRecommendedRollback('));
function harness(reply) {
  const calls = [], navigation = [], messages = [];
  let confirm;
  const step = { value: '2' };
  const scope = { document: {
    getElementById: id => id === 'approval-expected-step' ? step : { value: '검토 완료' },
    querySelector: () => ({ content: 'csrf' })
  }, fetch: async (url, options) => { calls.push({ url, options }); return reply(); },
  openConfirm: (_title, _text, callback) => { confirm = callback; },
  navigate: (...args) => navigation.push(args), showToast: (...args) => messages.push(args) };
  vm.createContext(scope); vm.runInContext(source, scope);
  return { scope, calls, navigation, messages, step, confirm: () => confirm() };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
test('approval binds the displayed stage before confirmation and sends one POST', async () => {
  const h = harness(() => ({ status: 200, json: async () => ({ success: true }) }));
  h.scope.processApproval(7, true); h.step.value = '3'; h.confirm(); await flush();
  assert.equal(h.calls.length, 1);
  assert.equal(JSON.parse(h.calls[0].options.body).expectedStepOrder, 2);
  assert.equal(h.calls[0].options.method, 'POST');
  assert.deepEqual(h.navigation, [['request-detail', 7]]);
});
test('stale reject reloads detail without replaying to the next stage', async () => {
  const h = harness(() => ({ status: 409, json: async () => ({ success: false, message: '단계 변경' }) }));
  h.scope.processApproval(7, false); h.confirm(); await flush();
  assert.match(h.calls[0].url, /\/reject$/);
  assert.equal(h.calls.length, 1);
  assert.deepEqual(h.navigation, [['request-detail', 7]]);
});
test('unknown outcome never retries a decision and asks to inspect history', async () => {
  const h = harness(() => { throw new Error('connection lost'); });
  h.scope.processApproval(7, true); h.confirm(); await flush();
  assert.equal(h.calls.length, 1); assert.equal(h.navigation.length, 0);
  assert.match(h.messages[0][0], /승인 이력과 단계/);
});
