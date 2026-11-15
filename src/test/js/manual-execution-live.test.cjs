const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const html = readFileSync(resolve(__dirname, '../../main/resources/templates/pages/request-detail.html'), 'utf8');
const manual = html.slice(html.indexOf('  function loadExecTargets()'), html.indexOf('  function completeManualDeploy('));
const statusHelper = html.slice(html.indexOf('  function execUpdateRequestStatus('), html.indexOf('  function execCloseSource('));
const monitorHelper = html.slice(html.indexOf('  function createExecutionMonitor('), html.indexOf('  function execRefreshLater('));
const summaryHelper = html.slice(html.indexOf('  function summarizeServerOutcomes('), html.indexOf('  loadServerOutcomes();'));
function element() {
  return { children: [], style: {}, dataset: {}, className: '', disabled: false,
    classList: { add() {}, remove() {} }, appendChild(child) { this.children.push(child); },
    set innerHTML(value) { this.children = []; }, querySelector(selector) {
      if (selector === '.manual-status') return this.children[2];
      if (selector === 'strong') return this.children[1].children[0];
    }, closest() { return this.parent; } };
}
function harness(accepted = true, env = 'PROD') {
  const list = element(), badge = element(), withdrawal = { hidden: false }, complete = element();
  badge.textContent = '승인 완료 · 배포 대기';
  const elements = { 'manual-server-list': list, 'request-status-badge': badge,
    'request-withdrawal-card': withdrawal, 'manual-complete-btn': complete, 'manual-steps': element(), 'normal-exec': element() };
  let targets = [{ id: 1, name: 'WEB1', status: 'PENDING' }, { id: 2, name: 'WEB2', status: 'PENDING' }];
  const sources = [], toasts = []; let summaries = 0;
  const context = vm.createContext({ document: { getElementById: id => elements[id], createElement: element,
      addEventListener() {}, removeEventListener() {} },
    window: { addEventListener() {}, removeEventListener() {} },
    setTimeout() {}, clearTimeout() {}, AbortController,
    EXEC_REQUEST_ID: 59, execFinalStatus: null, execEventSource: null, execMonitor: null,
    execCsrf: () => ({ header: 'csrf', token: 'token' }),
    execCloseSource: () => { if (context.execMonitor) context.execMonitor.stop(); if (context.execEventSource) context.execEventSource.close(); context.execEventSource = null; },
    execAppendLog() {}, execAppendStatus() {}, loadServerOutcomes() {}, refreshExecutionSummary() { summaries++; },
    showToast: (...args) => toasts.push(args), EventSource: class {
      constructor() { this.events = {}; sources.push(this); }
      addEventListener(name, callback) { this.events[name] = callback; } close() { this.closed = true; }
    }, fetch: async url => ({ json: async () => url.includes('/targets/')
      ? { success: true, data: { strategy: 'MANUAL', status: env + '_DEPLOYING', targets } }
      : typeof accepted === 'function' ? accepted(sources[0])
      : { success: accepted, message: '409 plan changed', data: { status: env + '_DEPLOYING' } } }) });
  vm.runInContext(statusHelper + monitorHelper + manual, context);
  context.renderManualSteps(targets);
  const button = list.children[0].children[3]; button.parent = list.children[0];
  return { context, list, badge, withdrawal, complete, button, sources, toasts, elements,
    setTargets(value) { targets = value; }, summaries: () => summaries };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
for (const sameRequest of [false, true]) test('delayed targets response is ignored after SPA reentry' + (sameRequest ? ' to same request' : ' to another request'), async () => {
  const h = harness(); let deliver;
  h.context.fetch = () => new Promise(resolve => { deliver = resolve; });
  const pending = h.context.loadExecTargets();
  h.context.EXEC_REQUEST_ID = sameRequest ? 59 : 60;
  h.elements['manual-steps'] = element(); h.elements['exec-panel'] = element();
  const newList = element(); h.elements['manual-server-list'] = newList;
  deliver({ json: async () => ({ success: true, data: { strategy: 'MANUAL', status: 'PROD_DEPLOYING', targets: [{ id: 99, name: 'old59' }] } }) });
  await pending;
  assert.equal(h.badge.textContent, '승인 완료 · 배포 대기'); assert.equal(h.withdrawal.hidden, false);
  assert.equal(newList.children.length, 0);
});
test('late manual acceptance and old SSE cannot mutate new page or close its new log source', async () => {
  const h = harness(); let deliver;
  h.context.fetch = () => new Promise(resolve => { deliver = resolve; });
  h.context.executeServerStep(1, h.button);
  const oldSource = h.sources[0], newSource = { close() { this.closed = true; } };
  h.context.EXEC_REQUEST_ID = 60; h.elements['exec-panel'] = element(); h.elements['manual-steps'] = element();
  h.context.execEventSource = newSource;
  oldSource.events.status({ data: 'PROD_FAILED' }); oldSource.events.done(); oldSource.onerror();
  deliver({ json: async () => ({ success: true, data: { status: 'PROD_DEPLOYING' } }) }); await settle();
  assert.equal(h.badge.textContent, '승인 완료 · 배포 대기'); assert.equal(h.withdrawal.hidden, false);
  assert.equal(h.toasts.length, 0); assert.equal(h.summaries(), 0);
  assert.equal(oldSource.closed, true); assert.equal(newSource.closed, undefined);
});
for (const env of ['PROD', 'TEST']) test('manual accepted ' + env + ' start updates header and withdrawal', async () => {
  const h = harness(true, env); h.context.executeServerStep(1, h.button);
  assert.equal(h.withdrawal.hidden, false); await settle();
  assert.equal(h.badge.textContent, env === 'PROD' ? '운영 배포 중' : 'TEST 배포 중');
  assert.equal(h.withdrawal.hidden, true); assert.equal(h.summaries(), 1);
});
test('rejected manual execution preserves pending header and withdrawal', async () => {
  const h = harness(false); h.context.executeServerStep(1, h.button); await settle();
  assert.equal(h.badge.textContent, '승인 완료 · 배포 대기'); assert.equal(h.withdrawal.hidden, false);
});
test('failed server done reloads persisted failure and renders retry without failing overall request', async () => {
  const h = harness(); h.context.executeServerStep(1, h.button); await settle();
  h.setTargets([{ id: 1, name: 'WEB1', status: 'FAILED' }, { id: 2, name: 'WEB2', status: 'PENDING' }]);
  h.sources[0].events.status({ data: 'SERVER_FAILED' }); h.sources[0].events.done(); await settle();
  assert.equal(h.list.children[0].children[3].textContent, '다시 실행');
  assert.equal(h.list.children[0].children[3].disabled, false);
  assert.equal(h.complete.disabled, true); assert.equal(h.badge.textContent, '운영 배포 중');
});
test('successful server done reloads all outcomes and enables completion only for all succeeded', async () => {
  const h = harness(); h.context.executeServerStep(1, h.button); await settle();
  h.setTargets([{ id: 1, name: 'WEB1', status: 'SUCCESS' }, { id: 2, name: 'WEB2', status: 'SUCCESS' }]);
  h.sources[0].events.status({ data: 'SERVER_DONE' }); h.sources[0].events.done(); await settle();
  assert.equal(h.list.children[0].children[3].textContent, '완료'); assert.equal(h.complete.disabled, false);
});
test('missing server status is not reported as deployment failure', async () => {
  const h = harness(); h.context.executeServerStep(1, h.button); await settle();
  h.sources[0].events.done(); await settle(); assert.equal(h.toasts.length, 0);
});
test('late manual acceptance cannot replace an overall terminal SSE status', async () => {
  const h = harness(source => {
    source.events.status({ data: 'PROD_FAILED' });
    return { success: true, data: { status: 'PROD_DEPLOYING' } };
  });
  h.context.executeServerStep(1, h.button); await settle();
  assert.equal(h.badge.textContent, '운영 배포 실패');
  h.context.loadExecTargets(); await settle();
  assert.equal(h.badge.textContent, '운영 배포 실패');
});
test('summary refresh replaces inert persisted fragments and ignores a response after SPA reentry', async () => {
  const script = html.slice(html.indexOf('  function refreshExecutionSummary('), html.indexOf('  function toggleRequestExecutionFlow('));
  let resolveResponse, panel = {}, replaced = [];
  const c = vm.createContext({ EXEC_REQUEST_ID: 59,
    document: { getElementById: id => id === 'exec-panel' ? panel : { replaceWith(value) { replaced.push(value); } } },
    fetch: () => new Promise(resolve => { resolveResponse = resolve; }),
    DOMParser: class { parseFromString() { return { getElementById: id => ({ id }) }; } } });
  vm.runInContext(script, c);
  let promise = c.refreshExecutionSummary(); resolveResponse({ ok: true, text: async () => 'persisted SSR' }); await promise;
  assert.deepEqual(replaced.map(value => value.id), ['request-progress', 'request-status-history']);
  replaced = []; promise = c.refreshExecutionSummary(); panel = {};
  resolveResponse({ ok: true, text: async () => 'stale SSR' }); await promise;
  assert.equal(replaced.length, 0);
});
test('latest per-server outcomes separate retry attempts and retain unrecorded targets', () => {
  const c = vm.createContext({}); vm.runInContext(summaryHelper, c);
  const targets = [{ serverId: 1 }, { serverId: 2 }, { serverId: 3 }];
  const attempts = [{ attemptId: 12, serverId: 2, success: true }, { attemptId: 10, serverId: 1, success: false }, { attemptId: 11, serverId: 1, success: true }];
  const summary = c.summarizeServerOutcomes(targets, attempts);
  assert.equal(summary.targets.length, 3); assert.equal(summary.succeeded, 2);
  assert.equal(summary.failed, 0); assert.equal(summary.unrecorded, 1);
  assert.equal(attempts.length, 3); assert.equal(summary.targets[0].latest.attemptId, 11);
});
for (const strategy of ['SAFE', 'WAVE', 'MANUAL']) test(strategy + ' outcomes do not infer later servers were skipped from an earlier failure', async () => {
  const elements = { 'srv-flow-wrap': { style: {} }, 'srv-flow': {}, 'srv-flow-note': {}, 'srv-attempts': {} };
  const rows = [{ attemptId: 1, serverId: 1, serverName: 'WEB1', success: false },
    { attemptId: 2, serverId: 1, serverName: 'WEB1', success: true },
    { attemptId: 3, serverId: 2, serverName: 'WEB2', success: true }];
  const script = html.slice(html.indexOf('  function loadServerOutcomes()'), html.indexOf('  // ===== 운영 실행'))
    .replace('const rid = /*[[${request.id}]]*/ 0;', 'const rid = 59;');
  const c = vm.createContext({ EXEC_REQUEST_ID: 59, document: { getElementById: id => elements[id] }, fetch: async () => ({ json: async () => ({
    success: true, data: { strategy, servers: rows, targets: [{ serverId: 1, serverName: 'WEB1' }, { serverId: 2, serverName: 'WEB2' }] }
  }) }) });
  vm.runInContext(script, c); await settle();
  assert.match(elements['srv-flow-note'].textContent, /대상 2대 · 명령 시도 3회 · 최신 결과 성공 2대/);
  assert.doesNotMatch(elements['srv-flow-note'].textContent, /중단|나가지|경계/);
  assert.equal((elements['srv-flow'].innerHTML.match(/class="srv-node ok"/g) || []).length, 2);
  assert.equal((elements['srv-attempts'].innerHTML.match(/<p>/g) || []).length, 3);
  assert.match(elements['srv-attempts'].innerHTML, /WEB1 · 실패/);
});
for (const sameRequest of [false, true]) test('delayed outcomes cannot overwrite attempts after SPA reentry' + (sameRequest ? ' to same request' : ' to another request'), async () => {
  const ids = ['srv-flow-wrap', 'srv-flow', 'srv-flow-note', 'srv-attempts'];
  const elements = Object.fromEntries(ids.map(id => [id, { style: {}, innerHTML: 'original' }]));
  const script = html.slice(html.indexOf('  function loadServerOutcomes()'), html.indexOf('  // ===== 운영 실행'))
    .replace('const rid = /*[[${request.id}]]*/ 0;', 'const rid = 59;');
  let deliver;
  const c = vm.createContext({ EXEC_REQUEST_ID: 59, document: { getElementById: id => elements[id] },
    fetch: () => new Promise(resolve => { deliver = resolve; }) });
  vm.runInContext(script, c);
  c.EXEC_REQUEST_ID = sameRequest ? 59 : 60;
  ids.forEach(id => { elements[id] = { style: {}, innerHTML: 'new page' }; });
  deliver({ json: async () => ({ success: true, data: { targets: [{ serverId: 1, serverName: 'old59' }],
    servers: [{ attemptId: 1, serverId: 1, serverName: 'old59', success: false }] } }) });
  await settle();
  ids.forEach(id => assert.equal(elements[id].innerHTML, 'new page'));
  assert.equal(elements['srv-flow-note'].textContent, undefined);
  assert.equal(elements['srv-flow-wrap'].style.display, undefined);
});
