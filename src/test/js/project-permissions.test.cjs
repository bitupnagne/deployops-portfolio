const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/project-permissions.js'), 'utf8');
const grant = (permission, environment) => ({permission, environment});
const projectTemplate = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const rollbackScript = projectTemplate.slice(projectTemplate.indexOf('  function requestRollback('),
  projectTemplate.indexOf('  function downloadDeliveryGuide('));
function rollbackScreen(dataset) {
  const prompts = [], calls = [], notices = [];
  const context = vm.createContext({document: {getElementById: () => dataset ? {dataset} : null,
    querySelector: () => ({content: 'csrf'})}, window: {prompt: (...args) => {prompts.push(args); return 'QA rollback';}},
    showToast: (...args) => notices.push(args), fetch: (...args) => {calls.push(args); return Promise.resolve({json: async () => ({success: true, data: {status: 'TEST_REQUESTED'}})});}});
  vm.runInContext(rollbackScript, context);
  return {invoke: environment => context.requestRollback('15', environment), prompts, calls, notices};
}
test('rollback denied, missing and malformed permission never prompt or POST', () => {
  for (const dataset of [undefined, {}, {canRollbackTest: 'false', canRollbackProd: 'false'}, {canRollbackTest: 'TRUE'}]) {
    for (const environment of ['TEST', 'PROD', 'UNKNOWN']) {
      const screen = rollbackScreen(dataset); screen.invoke(environment);
      assert.equal(screen.prompts.length, 0); assert.equal(screen.calls.length, 0);
    }
  }
});
test('TEST rollback grant permits TEST once while PROD remains denied', () => {
  const screen = rollbackScreen({canRollbackTest: 'true', canRollbackProd: 'false'});
  screen.invoke('PROD'); assert.equal(screen.calls.length, 0);
  screen.invoke('TEST'); assert.equal(screen.prompts.length, 1); assert.equal(screen.calls.length, 1);
  assert.equal(screen.calls[0][0], '/api/v1/deploy-requests/rollback');
  assert.deepEqual(JSON.parse(screen.calls[0][1].body), {historyId: 15, environment: 'TEST', reason: 'QA rollback'});
});
const testGrant = grant('DEPLOY_REQUEST', 'TEST');
const allGrant = grant('TEST_EXECUTE', 'ALL');

class Element {
  constructor(tag = 'div') { this.tag = tag; this.children = []; this.value = ''; this.disabled = false; this.dataset = {}; this.isConnected = true; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  add(child) { this.append(child); }
  focus() { this.focused = true; }
  querySelectorAll() { return this.children.flatMap(child => child.tag === 'input' ? [child] : child.querySelectorAll ? child.querySelectorAll() : []); }
}
function view(overrides = {}) {
  return {projectId: 10, projectName: '프로젝트', user: {id: 2, name: '<img onerror=x>'}, revision: 'a'.repeat(64),
    editable: true, canGrant: true, editRestriction: '', availableGrants: [testGrant], rawPermissions: [], effectivePermissions: [], roleNotes: [], ...overrides};
}
function setup(fetchImpl) {
  const fields = Object.fromEntries(['user', 'editor', 'reason', 'save', 'reload', 'notes', 'grants', 'effective', 'status'].map(name => [name, new Element()]));
  const root = new Element(); root.dataset.projectId = '10'; root.querySelector = selector => fields[selector.match(/"([^"]+)"/)[1]];
  const requests = [], confirmations = [];
  const context = {document: {getElementById: () => root, createElement: tag => new Element(tag), createTextNode: text => ({textContent: text}),
    querySelector: selector => ({content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-token'})},
    Option: function(text, value) { this.text = text; this.value = value; },
    fetch: async (url, options) => { requests.push({url, options}); return fetchImpl(url, options); },
    openConfirm: (title, description, callback) => confirmations.push({title, description, callback})};
  vm.createContext(context); vm.runInContext(source, context);
  return {api: context.ProjectPermissions, root, fields, requests, confirmations,
    async select(id = '2') { fields.user.value = id; await context.ProjectPermissions.selectUser(); },
    inputs() { return fields.grants.querySelectorAll(); }};
}
const response = (data, status = 200, message = '') => ({ok: status < 400, status, json: async () => ({success: status < 400, data, message})});

test('loads actual project users endpoint and writes safe option text', async () => {
  const app = setup(async () => response({users: [{id: 2, name: '<img onerror=x>', loginId: 'dev', roleLabel: '개발자', active: true}]}));
  await app.api.open(); await app.api.open();
  assert.equal(app.requests.length, 1); assert.equal(app.requests[0].url, '/api/v1/projects/10/permissions');
  assert.match(app.fields.user.children[1].text, /<img onerror=x>/);
});

test('runtime config and file log grants show separate Korean labels in confirmation', async () => {
  const grants = [grant('RUNTIME_CONFIG_MANAGE', 'TEST'), grant('RUNTIME_LOG_READ', 'PROD')];
  const app = setup(async () => response(view({availableGrants: grants})));
  await app.select(); app.inputs().forEach(input => { input.checked = true; });
  app.fields.reason.value = '런타임 담당자'; app.api.save();
  assert.match(app.confirmations[0].description, /런타임 설정 관리 · TEST/);
  assert.match(app.confirmations[0].description, /런타임 파일 로그 조회 · PROD/);
});

test('preserves legacy ALL in confirmed PUT, sends revision and CSRF, refreshes revision', async () => {
  let finish;
  const app = setup(async (_url, options) => options ? new Promise(resolve => { finish = resolve; }) : response(view({rawPermissions: [{grant: allGrant, active: true}]})));
  await app.select(); app.inputs()[0].checked = true; app.fields.reason.value = '담당 업무'; app.api.save();
  assert.equal(app.confirmations.length, 1); assert.match(app.confirmations[0].description, /부여: 배포 요청/);
  const saving = app.confirmations[0].callback();
  assert.ok(app.inputs().every(input => input.disabled)); assert.equal(app.fields.reason.disabled, true);
  const put = app.requests[1]; assert.equal(put.url, '/api/v1/projects/10/permissions/2'); assert.equal(put.options.method, 'PUT');
  assert.equal(put.options.headers['X-CSRF-TOKEN'], 'csrf-token');
  assert.deepEqual(JSON.parse(put.options.body), {grants: [testGrant, allGrant], reason: '담당 업무', expectedRevision: 'a'.repeat(64)});
  finish(response(view({revision: 'b'.repeat(64), rawPermissions: [{grant: testGrant, active: true}, {grant: allGrant, active: true}]})));
  await saving; assert.equal(app.fields.reason.value, ''); assert.equal(app.fields.user.disabled, false);
  app.inputs()[0].checked = false; app.fields.reason.value = '회수'; app.api.save();
  finish = null; const next = app.confirmations[1].callback();
  assert.equal(JSON.parse(app.requests[2].options.body).expectedRevision, 'b'.repeat(64));
  finish(response(view())); await next;
});

test('409 preserves edits and reason without retrying a stale revision', async () => {
  const app = setup(async (_url, options) => options ? response(null, 409, 'conflict') : response(view()));
  await app.select(); app.inputs()[0].checked = true; app.fields.reason.value = '사유'; app.api.save();
  await app.confirmations[0].callback();
  assert.equal(app.inputs()[0].checked, true); assert.equal(app.fields.reason.value, '사유');
  assert.equal(app.inputs()[0].disabled, false); assert.match(app.fields.status.textContent, /현재 입력은 유지/);
  assert.equal(app.requests.filter(call => call.options).length, 1);
});

test('late response for prior user cannot replace current user or grant them privileges', async () => {
  let first;
  const app = setup(async url => url.endsWith('/2') ? new Promise(resolve => { first = resolve; }) : response(view({user: {id: 3, name: '현재'}})));
  const pending = app.select('2'); await app.select('3'); first(response(view())); await pending;
  assert.match(app.fields.status.textContent, /현재/);
  app.inputs()[0].checked = true; app.fields.reason.value = '사유'; app.api.save();
  assert.match(app.confirmations[0].description, /^현재/);
});

test('self view is read only and direct save invocation cannot send mutation', async () => {
  const app = setup(async () => response(view({editable: false, canGrant: false, editRestriction: '자기 변경 금지'})));
  await app.select(); app.inputs()[0].checked = true; app.fields.reason.value = '사유'; app.api.save();
  assert.equal(app.fields.save.disabled, true); assert.equal(app.inputs()[0].disabled, true);
  assert.equal(app.confirmations.length, 0); assert.equal(app.requests.length, 1);
});

test('changing selected user after confirmation prevents the captured stale mutation', async () => {
  const app = setup(async url => response(view({user: {id: Number(url.split('/').pop()), name: '사용자'}})));
  await app.select(); app.inputs()[0].checked = true; app.fields.reason.value = '사유'; app.api.save();
  await app.select('3'); await app.confirmations[0].callback();
  assert.equal(app.requests.filter(call => call.options).length, 0);
});

test('request environment selector reflects TEST-only grant and clears forbidden prior selection', () => {
  const template = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/request-create.html'), 'utf8');
  const functionSource = template.slice(template.indexOf('  function syncRequestEnvironmentPermissions()'), template.indexOf('  function syncModeState()'));
  const projectSelect = {selectedIndex: 0, options: [{dataset: {permittedEnvironments: 'TEST'}}]};
  const envSelect = {value: 'PROD', options: [{value: ''}, {value: 'TEST'}, {value: 'PROD'}]};
  const context = {projectSelect, envSelect}; vm.createContext(context); vm.runInContext(functionSource, context);
  context.syncRequestEnvironmentPermissions();
  assert.equal(envSelect.value, 'TEST'); assert.equal(envSelect.options[2].disabled, true);
  projectSelect.options[0].dataset.permittedEnvironments = 'TEST,PROD'; context.syncRequestEnvironmentPermissions();
  assert.equal(envSelect.options[2].disabled, false);
  projectSelect.options[0].dataset.permittedEnvironments = ''; context.syncRequestEnvironmentPermissions();
  assert.equal(envSelect.value, ''); assert.equal(envSelect.options[1].disabled, true);
});
