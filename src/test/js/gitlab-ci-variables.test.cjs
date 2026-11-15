const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/gitlab-ci-variables.js'), 'utf8');

class Element {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.dataset = {}; this.value = ''; this.disabled = false;
    this.textContent = ''; this.attributes = {}; this.style = {}; this.isConnected = true;
  }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(name, value) { this.attributes[name] = value; }
  closest(selector) {
    if (selector.includes('ci-vars') && this.dataset.ciVars) return this;
    if (selector.includes('variable-save') && this.dataset.variableSave) return this;
    if (selector.includes('variable-delete') && this.dataset.variableDelete) return this;
    return null;
  }
  contains(node) { return this === node || this.children.some(child => child.contains && child.contains(node)); }
  querySelector(selector) {
    const match = selector.match(/data-(?:variable-input|variable-reason)="([^"]+)"/);
    if (!match) return null;
    const attr = selector.includes('variable-input') ? 'variableInput' : 'variableReason';
    const find = node => {
      if (node.dataset && node.dataset[attr] === match[1]) return node;
      for (const child of node.children || []) { const found = find(child); if (found) return found; }
      return null;
    };
    return find(this);
  }
  focus() { this.focused = true; }
}

function setup() {
  const root = new Element('section');
  const project = new Element('div'); project.dataset.id = '17';
  const list = new Element('div'); const status = new Element('p'); const reload = new Element('button'); reload.dataset.ciVars = 'reload';
  root.append(reload, status, list);
  root.querySelector = selector => selector.includes('"list"') ? list : selector.includes('"status"') ? status
    : selector.includes('variable-input') || selector.includes('variable-reason') ? Element.prototype.querySelector.call(root, selector) : reload;
  root.contains = node => [root, reload, ...list.children].includes(node) || list.contains(node);
  const requests = []; const confirms = []; const listeners = {};
  const toasts = [];
  let mutationError = null;
  let readError = null;
  let metadata = {canManage: true, variables: [{key: 'NEXUS_REGISTRY_PASSWORD', configured: false, masked: true, protected: true, variableType: 'env_var', environmentScope: '*'}]};
  const context = vm.createContext({
    document: {getElementById: id => id === 'gitlab-ci-variables' ? root : id === 'project-data' ? project : null,
      createElement: tag => new Element(tag), addEventListener: (name, handler) => { listeners[name] = handler; }},
    window: {},
    csrfFetch: async (url, options) => {
      requests.push({url, options});
      if (options && ['PUT', 'DELETE'].includes(options.method)) {
        if (mutationError) return {ok: false, status: mutationError.status, json: async () => ({success: false, message: mutationError.message})};
        return {ok: true, status: 200, json: async () => ({success: true, data: null})};
      }
      if (readError) return {ok: false, status: 502, json: async () => ({success: false, message: '조회 오류'})};
      return {ok: true, status: 200, json: async () => ({success: true, data: metadata})};
    },
    openConfirm: (_title, _description, callback) => confirms.push(callback),
    showToast: (message, kind) => toasts.push({message, kind})
  });
  context.window = context;
  vm.runInContext(source, context);
  return {root, list, status, reload, requests, confirms, listeners, context, toasts,
    set mutationError(value) { mutationError = value; },
    set readError(value) { readError = value; },
    set metadata(value) { metadata = value; }};
}

test('loads allowlisted variable metadata without exposing a secret value', async () => {
  const app = setup();
  await app.context.GitlabCiVariables.load();
  assert.equal(app.requests[0].url, '/api/v1/gitlab/deploy-projects/17/ci-variables');
  assert.match(app.status.textContent, /실제 값은 GitLab에서 읽어오거나 화면에 표시하지 않습니다/);
  assert.equal(app.list.children[0].children[0].textContent, 'Nexus 컨테이너 저장소 비밀번호');
  assert.equal(app.list.children[0].querySelector('[data-variable-input="NEXUS_REGISTRY_PASSWORD"]').type, 'password');
  assert.equal(app.list.children[0].children.some(node => String(node.textContent).includes('fixture-secret')), false);
});

test('required build markers use the server effective-tool union without hiding other variable controls', async () => {
  const app = setup();
  app.metadata={canManage:false,requiredVariables:['MAVEN_BUILD_IMAGE','CI_BUILD_IMAGE'],variables:[
    {key:'MAVEN_BUILD_IMAGE',configured:true},{key:'CI_BUILD_IMAGE',configured:false},
    {key:'RUNTIME_BASE_IMAGE',configured:false}
  ]};
  await app.context.GitlabCiVariables.load();
  assert.equal(app.list.children.length,3);
  assert.equal(app.list.children[0].children[0].children[0].textContent,' · 현재 빌드에 필요');
  assert.equal(app.list.children[1].children[0].children[0].textContent,' · 현재 빌드에 필요');
  assert.equal(app.list.children[2].children[0].children.length,0);
  assert.equal(app.requests.length,1);
});

test('requires a reason, confirms secret replacement, sends CSRF-aware request and clears the field', async () => {
  const app = setup();
  await app.context.GitlabCiVariables.load();
  const card = app.list.children[0];
  const input = card.querySelector('[data-variable-input="NEXUS_REGISTRY_PASSWORD"]');
  const reason = card.querySelector('[data-variable-reason="NEXUS_REGISTRY_PASSWORD"]');
  input.value = 'fixture-secret'; reason.value = '회전 시험';
  const actions = card.children.find(node => node.children.some(child => child.dataset.variableSave));
  const button = actions.children.find(node => node.dataset.variableSave);
  app.listeners.click({target: button});
  assert.equal(app.confirms.length, 1, app.status.textContent + ' / ' + JSON.stringify(button.dataset));
  await app.confirms[0]();
  const put = app.requests.find(request => request.options && request.options.method === 'PUT');
  assert.equal(put.url, '/api/v1/gitlab/deploy-projects/17/ci-variables/NEXUS_REGISTRY_PASSWORD');
  assert.deepEqual(JSON.parse(put.options.body), {value: 'fixture-secret', reason: '회전 시험'});
  assert.equal(input.value, '');
});

test('does not render write controls without the explicit project permission', async () => {
  const app = setup();
  app.metadata = {canManage: false, variables: [{key: 'CI_BUILD_IMAGE', configured: true, masked: false, protected: true, variableType: 'env_var', environmentScope: '*'}]};
  await app.context.GitlabCiVariables.load();
  assert.match(app.status.textContent, /조회만 가능합니다/);
  assert.equal(app.list.children[0].querySelector('[data-variable-input="CI_BUILD_IMAGE"]'), null);
});

test('unprotected GitLab metadata is displayed as unprotected', async () => {
  const app = setup();
  app.metadata = {canManage: false, variables: [{key: 'CI_BUILD_IMAGE', configured: true, masked: false, protected: false, variableType: 'env_var', environmentScope: '*'}]};
  await app.context.GitlabCiVariables.load();
  const metadataText = app.list.children[0].children[1].textContent;
  assert.match(metadataText, /보호되지 않은 변수/);
  assert.doesNotMatch(metadataText, /· 보호 변수/);
});

test('protected GitLab metadata remains displayed as protected', async () => {
  const app = setup();
  await app.context.GitlabCiVariables.load();
  assert.match(app.list.children[0].children[1].textContent, /· 보호 변수/);
  assert.doesNotMatch(app.list.children[0].children[1].textContent, /보호되지 않은/);
});

test('unknown save outcome clears input, refreshes metadata once and never retries or claims success', async () => {
  const app = setup();
  await app.context.GitlabCiVariables.load();
  const card = app.list.children[0];
  const input = card.querySelector('[data-variable-input="NEXUS_REGISTRY_PASSWORD"]');
  const reason = card.querySelector('[data-variable-reason="NEXUS_REGISTRY_PASSWORD"]');
  input.value = 'fixture-secret'; reason.value = '회전 시험';
  app.mutationError = {status: 504, message: '변경 결과를 확인할 수 없습니다. 이미 적용되었을 수 있습니다.'};
  app.metadata = {canManage: true, variables: [{key: 'NEXUS_REGISTRY_PASSWORD', configured: true, masked: true, protected: true, variableType: 'env_var', environmentScope: '*'}]};
  const button = card.children.flatMap(node => node.children).find(node => node.dataset.variableSave);
  app.listeners.click({target: button});
  await app.confirms[0]();
  assert.equal(input.value, '');
  assert.equal(app.requests.filter(request => request.options.method === 'PUT').length, 1);
  assert.equal(app.requests.filter(request => !request.options.method).length, 2);
  assert.equal(app.toasts.length, 0);
  assert.match(app.status.textContent, /이미 적용되었을 수/);
  assert.match(app.status.textContent, /값의 적용 여부는 이 화면에서 확인할 수 없습니다/);
  assert.match(app.list.children[0].children[1].textContent, /설정됨/);
});

test('unknown delete outcome refreshes existence metadata without repeating delete', async () => {
  const app = setup();
  app.metadata = {canManage: true, variables: [{key: 'CI_BUILD_IMAGE', configured: true, masked: false, protected: true, variableType: 'env_var', environmentScope: '*'}]};
  await app.context.GitlabCiVariables.load();
  const card = app.list.children[0];
  card.querySelector('[data-variable-reason="CI_BUILD_IMAGE"]').value = '정리';
  app.mutationError = {status: 504, message: '변경 결과를 확인할 수 없습니다.'};
  app.metadata = {canManage: true, variables: [{key: 'CI_BUILD_IMAGE', configured: false, masked: false, protected: true, variableType: 'env_var', environmentScope: '*'}]};
  const button = card.children.flatMap(node => node.children).find(node => node.dataset.variableDelete);
  app.listeners.click({target: button});
  await app.confirms[0]();
  assert.equal(app.requests.filter(request => request.options.method === 'DELETE').length, 1);
  assert.equal(app.requests.filter(request => !request.options.method).length, 2);
  assert.match(app.list.children[0].children[1].textContent, /미설정/);
  assert.equal(app.toasts.length, 0);
});

test('failed reconciliation keeps unknown warning and asks to reload after connection recovers', async () => {
  const app = setup();
  await app.context.GitlabCiVariables.load();
  const card = app.list.children[0];
  card.querySelector('[data-variable-input="NEXUS_REGISTRY_PASSWORD"]').value = 'fixture-secret';
  card.querySelector('[data-variable-reason="NEXUS_REGISTRY_PASSWORD"]').value = '교체';
  app.mutationError = {status: 504, message: '변경 결과를 확인할 수 없습니다.'};
  app.readError = true;
  const button = card.children.flatMap(node => node.children).find(node => node.dataset.variableSave);
  app.listeners.click({target: button});
  await app.confirms[0]();
  assert.match(app.status.textContent, /변경 결과를 확인할 수 없습니다/);
  assert.match(app.status.textContent, /상태 재조회도 실패/);
  assert.equal(app.requests.filter(request => request.options.method === 'PUT').length, 1);
  assert.equal(app.toasts.length, 0);
});
