const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/request-detail.html'), 'utf8');
const script = html.slice(html.indexOf('  function execCsrf('), html.indexOf('  // MANUAL(수동 단계) 배포'));

function harness(response) {
  const badge = { textContent: 'TEST 요청 접수', className: 'status-badge lg test-requested' };
  const withdrawal = { hidden: false };
  const button = { disabled: false, classList: { add() {}, remove() {} } };
  const log = { innerHTML: '', children: [], appendChild(child) { this.children.push(child); } };
  const elements = { 'request-status-badge': badge, 'request-withdrawal-card': withdrawal,
    'btn-start-deploy': button, 'deploy-log': log };
  const sources = [];
  const toasts = [];
  const confirmations = [];
  const context = vm.createContext({
    document: {
      getElementById: id => elements[id],
      querySelector: () => ({ content: 'csrf' }),
      createElement: () => ({ style: {}, textContent: '' }),
      addEventListener() {}, removeEventListener() {}
    },
    window: { addEventListener() {}, removeEventListener() {} }, AbortController,
    EXEC_REQUEST_ID: 48, EXEC_ENV: 'TEST', EXEC_IS_ROLLBACK: false, EXEC_HAS_BUILD_SOURCE: true,
    execEventSource: null, execFinalStatus: null,
    EventSource: class {
      constructor() { this.events = {}; sources.push(this); }
      addEventListener(name, callback) { this.events[name] = callback; }
      close() { this.closed = true; }
    },
    openConfirm: (title, message, callback) => { confirmations.push({ title, message }); callback(); },
    fetch: async () => ({ json: async () => typeof response === 'function' ? response(sources[0]) : response }),
    showToast: (message, kind) => toasts.push({ message, kind }),
    setTimeout() {}, clearTimeout() {}, navigate() {}
  });
  vm.runInContext(script, context);
  return { context, badge, withdrawal, button, log, sources, toasts, confirmations };
}

const settle = () => new Promise(resolve => setImmediate(resolve));

test('execution confirmation names the actual TEST/PROD action and rollback using natural Korean', async () => {
  for (const [env, build, rollback, title, message] of [
    ['TEST', true, false, 'TEST 실행', 'TEST 빌드 + 배포 작업을 시작하시겠습니까?'],
    ['TEST', false, false, 'TEST 실행', 'TEST 이미지 배포 작업을 시작하시겠습니까?'],
    ['PROD', false, false, 'PROD 실행', 'PROD 배포 작업을 시작하시겠습니까?'],
    ['TEST', false, true, 'TEST 롤백 실행', 'TEST 롤백 배포 작업을 시작하시겠습니까?'],
    ['PROD', false, true, 'PROD 롤백 실행', 'PROD 롤백 배포 작업을 시작하시겠습니까?']
  ]) {
    const h = harness({ success: true, data: { status: env === 'TEST' ? 'TEST_DEPLOYING' : 'PROD_DEPLOYING' } });
    h.context.EXEC_ENV = env; h.context.EXEC_HAS_BUILD_SOURCE = build; h.context.EXEC_IS_ROLLBACK = rollback;
    h.context.startRequestDeploy();
    assert.deepEqual(h.confirmations, [{ title, message }]);
    await settle();
  }
});

test('accepted execution immediately displays the returned running status and hides withdrawal', async () => {
  const h = harness({ success: true, data: { status: 'TEST_BUILDING' } });
  h.context.startRequestDeploy();
  // Controls remain while acceptance is still unknown.
  assert.equal(h.withdrawal.hidden, false);
  await settle();
  assert.equal(h.badge.textContent, 'TEST 빌드 중');
  assert.equal(h.badge.className, 'status-badge lg test-building');
  assert.equal(h.withdrawal.hidden, true);
});

test('rejected start preserves pending status and withdrawal controls', async () => {
  const h = harness({ success: false, message: '409 CONFLICT: 배포 설정이 변경됐습니다.' });
  h.context.startRequestDeploy();
  await settle();
  assert.equal(h.badge.textContent, 'TEST 요청 접수');
  assert.equal(h.withdrawal.hidden, false);
  assert.equal(h.button.disabled, false);
  assert.equal(h.sources[0].closed, true);
  assert.match(h.toasts[0].message, /409 CONFLICT/);
});

test('terminal SSE updates request status and cannot be overwritten by a late acceptance response', async () => {
  const h = harness(source => {
    source.events.status({ data: 'TEST_DEPLOYED' });
    return { success: true, data: { status: 'TEST_BUILDING' } };
  });
  h.context.startRequestDeploy();
  await settle();
  assert.equal(h.badge.textContent, 'TEST 배포 완료');
  assert.equal(h.context.execFinalStatus, 'TEST_DEPLOYED');
  assert.equal(h.withdrawal.hidden, true);
});

test('detail-page polling uses the authorized read-only API and reflects a missed terminal result', async () => {
  const h = harness(), calls = [];
  h.context.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url === '/api/v1/deploy-requests/48') {
      return { ok: true, status: 200, json: async () => ({ success: true, data: { status: 'TEST_FAILED' } }) };
    }
    return { ok: false };
  };
  h.context.execBindSource();
  await h.context.execMonitor.query();
  assert.equal(h.badge.textContent, 'TEST 실패');
  assert.equal(h.context.execFinalStatus, 'TEST_FAILED');
  assert.equal(h.sources[0].closed, true);
  assert.equal(calls[0].url, '/api/v1/deploy-requests/48');
  assert.equal(calls[0].options.cache, 'no-store');
  assert.ok(calls.every(call => !call.options || call.options.method !== 'POST'));
});

test('per-server events do not change overall request status or claim request failure', () => {
  const h = harness();
  h.context.execUpdateRequestStatus('PROD_DEPLOYING');
  h.context.execBindSource();
  h.sources[0].events.status({ data: 'SERVER_DONE' });
  assert.equal(h.badge.textContent, '운영 배포 중');
  assert.equal(h.context.execFinalStatus, null);
  assert.equal(h.log.children.at(-1).textContent, '서버 배포 완료 · 요청 완료 처리 대기');
  h.sources[0].events.status({ data: 'SERVER_FAILED' });
  assert.equal(h.badge.textContent, '운영 배포 중');
  assert.equal(h.context.execFinalStatus, null);
  assert.equal(h.log.children.at(-1).textContent, '서버 배포 실패');
});

test('known request failures and rollback result update the header, unknown events are ignored', () => {
  const h = harness();
  h.context.execBindSource();
  for (const [status, label] of [['TEST_FAILED', 'TEST 실패'], ['PROD_FAILED', '운영 배포 실패'], ['ROLLED_BACK', '롤백됨']]) {
    h.context.execBindSource();
    h.sources.at(-1).events.status({ data: status });
    assert.equal(h.badge.textContent, label);
    assert.equal(h.context.execFinalStatus, status);
  }
  h.sources[0].events.status({ data: 'UNKNOWN' });
  assert.equal(h.badge.textContent, '롤백됨');
  assert.equal(h.context.execFinalStatus, 'ROLLED_BACK');
});
