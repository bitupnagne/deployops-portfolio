const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/request-create.html'), 'utf8');
function section(start, end) { return html.slice(html.indexOf('  function ' + start), html.indexOf(end, html.indexOf('  function ' + start))); }
const code = section('imageLoadContext()', "  projectSelect.addEventListener")
  + section('loadImageTags(', '  function refreshImageTags()')
  + section('applySelectedImageTag()', '  function selectedImageContext()')
  + section('resolveImageDigest()', '  function resetImageTagOptions(')
  + section('explainNoImages(', '  function escHtml(');
const settle = () => new Promise(resolve => setImmediate(resolve));
function screen() {
  const pending = [], hint = {style: {}, textContent: ''}, why = {style: {}, innerHTML: ''};
  const select = {rows: [], appendChild(item) { this.rows.push(item); }};
  Object.defineProperty(select, 'innerHTML', {set() { this.rows = []; }});
  const state = {projectSelect: {value: '2'}, envSelect: {value: 'TEST'},
    imageLoadGeneration: 0, digestLoadGeneration: 0, requireTestFirst: false,
    imageTagSelect: select, imageTagInput: {value: ''}, imageDigestInput: {value: ''},
    URLSearchParams, currentMode: () => 'IMAGE_ONLY', formatImageDate: () => '',
    selectedImageContext: () => '', renderPromotionManifest() {}, showToast() {}, escHtml: value => String(value || ''),
    resetImageTagOptions(message) { hint.textContent = message; },
    document: {getElementById: id => id === 'image-why' ? why : hint,
      createElement: () => ({dataset: {}})},
    fetch(url, options) { return new Promise((resolve, reject) => pending.push({url, options, resolve, reject})); }};
  vm.createContext(state); vm.runInContext(code, state);
  return {state, pending, hint, why, select};
}
const respond = (request, data) => request.resolve({json: async () => ({success: true, data})});
test('tag, digest and image-status GETs include selected environment on request-only endpoints', async () => {
  const h = screen(), {state, pending} = h;
  state.loadImageTags('2');
  assert.equal(pending[0].url, '/api/v1/nexus/request-projects/2/docker-tags?environment=TEST');
  assert.equal(pending[0].options.cache, 'no-store');
  respond(pending[0], [{tag:'v1',createdAt:null}]); await settle();
  assert.equal(h.select.rows[0].value, 'v1');
  state.imageTagInput.value = 'v1';
  state.resolveImageDigest();
  assert.equal(pending[1].url, '/api/v1/nexus/request-projects/2/docker-manifest?tag=v1&environment=TEST');
  respond(pending[1], {digest:'sha256:expected'}); await settle();
  assert.equal(state.imageDigestInput.value, 'sha256:expected');
  state.explainNoImages('2', state.imageLoadContext());
  assert.equal(pending[2].url, '/api/v1/nexus/request-projects/2/image-status?environment=TEST');
  respond(pending[2], {reason:'NO_IMAGES',title:'no images'}); await settle();
  assert.match(h.why.innerHTML, /no images/);
});
test('old TEST tags cannot overwrite a newly selected PROD context', async () => {
  const {state, pending, select} = screen();
  state.loadImageTags('2');
  state.envSelect.value = 'PROD';
  state.loadImageTags('2');
  assert.match(pending[1].url, /environment=PROD$/);
  respond(pending[1], [{tag:'prod-current'}]); await settle();
  respond(pending[0], [{tag:'test-stale'}]); await settle();
  assert.deepEqual(select.rows.map(row=>row.value), ['prod-current']);
});
test('stale digest and diagnostic results cannot cross environment or project selections', async () => {
  for (const change of [s=>{s.envSelect.value='PROD';}, s=>{s.projectSelect.value='1';}]) {
    const {state, pending, why} = screen();
    state.imageTagInput.value = 'v1';
    state.resolveImageDigest();
    state.explainNoImages('2', state.imageLoadContext());
    change(state);
    respond(pending[0], {digest:'sha256:wrong'});
    respond(pending[1], {reason:'NO_IMAGES',title:'stale'}); await settle();
    assert.equal(state.imageDigestInput.value, '');
    assert.equal(why.innerHTML, '');
  }
});
test('invalid environment and mismatched project never send registry metadata requests', () => {
  for (const env of ['', 'DEV', 'null']) {
    const {state, pending} = screen();
    state.envSelect.value = env;
    state.loadImageTags('2');
    state.imageTagInput.value = 'v1'; state.resolveImageDigest();
    state.explainNoImages('2', state.imageLoadContext());
    assert.equal(pending.length, 0);
  }
  const {state,pending} = screen();
  state.loadImageTags('99');
  assert.equal(pending.length, 0);
});
test('changing tag clears the old digest immediately and failure never restores it', async () => {
  const {state, pending} = screen();
  state.imageTagInput.value = 'old'; state.imageDigestInput.value = 'sha256:old';
  state.imageTagInput.value = 'new'; state.resolveImageDigest();
  assert.equal(state.imageDigestInput.value, '');
  pending[0].resolve({json:async()=>({success:false,message:'권한 회수'})});
  await settle();
  assert.equal(state.imageDigestInput.value, '');
  state.imageDigestInput.value = 'sha256:manual-old'; state.imageTagInput.value = '';
  state.resolveImageDigest();
  assert.equal(state.imageDigestInput.value, '');
  assert.equal(pending.length, 1);
});
test('PROD promotion digest is preserved when direct digest lookup is disallowed', () => {
  const {state,pending} = screen();
  state.envSelect.value = 'PROD'; state.requireTestFirst = true;
  state.imageTagInput.value = 'confirmed'; state.imageDigestInput.value = 'sha256:confirmed';
  state.resolveImageDigest();
  assert.equal(state.imageDigestInput.value, 'sha256:confirmed');
  assert.equal(pending.length, 0);
});
test('clearing image selection clears tag/digest and invalidates an outstanding lookup', async () => {
  const {state,pending,select} = screen();
  state.imageTagInput.value='old'; state.resolveImageDigest();
  state.imageDigestInput.value='sha256:old';
  select.selectedOptions=[{value:'',dataset:{}}];
  state.applySelectedImageTag();
  assert.equal(state.imageTagInput.value,'');
  assert.equal(state.imageDigestInput.value,'');
  respond(pending[0],{digest:'sha256:late'}); await settle();
  assert.equal(state.imageDigestInput.value,'');
});
test('manual tag editing invalidates previous digest without triggering a mutation', () => {
  const {state} = screen();
  state.imageTagInput.value='edited'; state.imageDigestInput.value='sha256:old';
  let handler;
  state.imageTagInput.addEventListener=(event, callback)=>{assert.equal(event,'input');handler=callback;};
  const listener=html.slice(html.indexOf("  imageTagInput.addEventListener('input'"),
    html.indexOf('  });',html.indexOf("  imageTagInput.addEventListener('input'"))+5);
  vm.runInContext(listener,state); handler();
  assert.equal(state.imageDigestInput.value,'');
  assert.equal(state.digestLoadGeneration,1);
});
