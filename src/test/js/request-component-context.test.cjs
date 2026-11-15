const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/request-create.html'), 'utf8');
const source = html.slice(html.indexOf('  function releaseComponentContext()'), html.indexOf('  function renderPromotionManifest()'));
const settle = () => new Promise(resolve => setImmediate(resolve));
function screen() {
  const pending = [];
  const fields = {replaceChildren() {}};
  const hint = {textContent: ''};
  const scope = {value: 'WAS_ONLY'};
  const state = {
    releaseComponentGeneration: 0, releaseComponents: [], releaseComponentsReady: false,
    projectSelect: {value: '1', selectedOptions: [{dataset: {imageRepository: 'repo'}}]},
    envSelect: {value: 'TEST'}, URLSearchParams,
    currentMode: () => state.mode || 'IMAGE_ONLY',
    document: {getElementById: id => ({
      'req-scope': scope, 'release-manifest-fields': fields, 'release-manifest-hint': hint
    })[id]},
    renderPromotionManifest() { hint.textContent = '승격 원본 선택'; },
    fetch(url, options) {
      return new Promise((resolve, reject) => pending.push({url, options, resolve, reject}));
    }
  };
  vm.createContext(state);
  vm.runInContext(source, state);
  return {state, pending, scope, hint};
}
const respond = (request, data = []) => request.resolve({ok: true, json: async () => ({success: true, data})});
test('request component GET includes the selected environment and scope, without runtime-view API', async () => {
  const {state, pending, scope} = screen();
  state.refreshReleaseComponents();
  assert.equal(pending[0].url, '/api/v1/projects/1/request-components?environment=TEST&scope=WAS_ONLY');
  assert.equal(pending[0].options.cache, 'no-store');
  respond(pending[0], [{id: 11, active: true, role: 'WAS', artifactType: 'CONTAINER', imageRepository: 'repo'}]);
  await settle();
  assert.equal(state.releaseComponentsReady, true);
  assert.equal(state.releaseComponents[0].id, 11);
  scope.value = 'WEB_ONLY';
  state.refreshReleaseComponents();
  assert.match(pending[1].url, /scope=WEB_ONLY$/);
  assert.equal(state.releaseComponentsReady, false);
});
test('out-of-order project results and failures cannot overwrite the new selection', async () => {
  for (const failure of [false, true]) {
    const {state, pending, hint} = screen();
    state.refreshReleaseComponents();
    state.projectSelect.value = '2';
    state.refreshReleaseComponents();
    respond(pending[1]);
    await settle();
    const currentHint = hint.textContent;
    if (failure) pending[0].reject(new Error('old forbidden response'));
    else respond(pending[0], [{id: 99, active: true, role: 'WAS', artifactType: 'CONTAINER'}]);
    await settle();
    assert.equal(state.releaseComponents.length, 0);
    assert.equal(state.releaseComponentsReady, true);
    assert.equal(hint.textContent, currentHint);
  }
});
test('fresh project, environment and scope are checked even before change handlers run', async () => {
  for (const change of [
    state => { state.projectSelect.value = '2'; },
    state => { state.envSelect.value = 'PROD'; },
    (state, scope) => { scope.value = 'WEB_ONLY'; }
  ]) {
    const {state, pending, scope} = screen();
    state.refreshReleaseComponents();
    change(state, scope);
    respond(pending[0], [{id: 11, active: true, role: 'WAS', artifactType: 'CONTAINER'}]);
    await settle();
    assert.equal(state.releaseComponentsReady, false);
    assert.equal(state.releaseComponents.length, 0);
  }
});
test('switch to promotion preserves its manifest when stale TEST response arrives', async () => {
  const {state, pending, hint} = screen();
  state.refreshReleaseComponents();
  state.envSelect.value = 'PROD';
  state.refreshReleaseComponents();
  respond(pending[0]);
  await settle();
  assert.equal(hint.textContent, '승격 원본 선택');
  assert.equal(state.releaseComponentsReady, true);
  assert.equal(pending.length, 1);
});
test('HTTP authorization failure cannot mark metadata ready even with a misleading success body', async () => {
  const {state, pending, hint} = screen();
  state.refreshReleaseComponents();
  pending[0].resolve({ok: false, json: async () => ({success: true, data: [], message: '권한 없음'})});
  await settle();
  assert.equal(state.releaseComponentsReady, false);
  assert.match(hint.textContent, /권한 없음/);
});
test('missing or unsupported scope/environment never sends a permissive metadata request', () => {
  for (const [env, range] of [['', 'ALL'], ['DEV', 'ALL'], ['TEST', ''], ['TEST', 'INVALID']]) {
    const {state, pending, scope} = screen();
    state.envSelect.value = env;
    scope.value = range;
    state.refreshReleaseComponents();
    assert.equal(pending.length, 0);
    assert.equal(state.releaseComponentsReady, false);
  }
});
test('source build metadata waits for CI without manual per-component digest fields', async () => {
  const {state,pending,hint}=screen();
  state.mode='BUILD_AND_DEPLOY';
  state.refreshReleaseComponents();
  respond(pending[0],[
    {id:11,code:'api',active:true,role:'WAS',artifactType:'CONTAINER',imageRepository:'repo'},
    {id:12,code:'web',active:true,role:'WEB',artifactType:'STATIC_BUNDLE',imageRepository:'web-repo'}
  ]);
  await settle();
  assert.equal(state.releaseComponentsReady,true);
  assert.match(hint.textContent,/CI가.*다이제스트/);
  const collect=html.slice(html.indexOf('  function collectReleaseComponents()'),html.indexOf('  function imageLoadContext()'));
  vm.runInContext(collect,state);
  assert.equal(JSON.stringify(state.collectReleaseComponents()),'[]');
});
test('mode changed before old response cannot restore manual image inputs',async()=>{
  const {state,pending}=screen();
  state.refreshReleaseComponents();
  state.mode='BUILD_AND_DEPLOY';
  respond(pending[0],[{id:11,code:'api',active:true,role:'WAS',artifactType:'CONTAINER'}]);
  await settle();
  assert.equal(state.releaseComponentsReady,false);
  assert.equal(state.releaseComponents.length,0);
});
