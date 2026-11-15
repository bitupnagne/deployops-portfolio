const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/project-runtime.js'), 'utf8');
class Element {
  constructor() { this.value = ''; this.checked = false; this.disabled = false; this.hidden = false; this.children = []; this.options = []; this.files = []; this.dataset = {}; this.isConnected = true; }
  replaceChildren(...items) { this.children = items; this.options = items; }
  append(...items) { this.children.push(...items); }
  add(item) { this.options.push(item); }
  set innerHTML(_) { throw new Error('Unescaped HTML rendering'); }
}
const option = (text, value, environment) => ({textContent: text, text, value: String(value), dataset: {environment}, disabled: false});
const data = (changes = {}) => ({canManage: true, canReadLogs: true, revisions: [{id: 11, componentId: 0, keyNames: ['DB_PASSWORD'], createdAt: '2026-10-08'}, {id: 12, componentId: 7, keyNames: ['<img onerror=x>']}], bindings: [], ...changes});
const response = (data, status = 200, message = '') => ({ok: status < 400, json: async () => ({success: status < 400, data, message})});
function setup(handler = async () => response(data())) {
  const names = ['environment', 'component', 'reload', 'editor', 'read-logs', 'content', 'file', 'status', 'metadata', 'log-text', 'log-status', 'revision', 'mode', 'persistent-logs', 'stop-grace', 'tmp', 'binding-reason', 'revision-reason', 'server', 'server-source', 'lines'];
  function page(id = '3') {
    const fields = Object.fromEntries(names.map(n => [n, new Element()]));
    fields.environment.value = 'TEST'; fields.component.value = '0'; fields.tmp.value = '0'; fields.lines.value = '200';
    fields.mode.options = ['INHERIT', 'MANAGED_REVISION', 'NONE'].map(x => option(x, x));
    fields['server-source'].options = [option('test-was', 1, 'TEST'), option('prod-was', 2, 'PROD'), option('test-duplicate', 1, 'TEST')];
    const root = new Element(); root.dataset.projectId = id; root.querySelector = selector => fields[selector.match(/"([^"]+)"/)[1]];
    return {root, fields};
  }
  let current = page(); const requests = [];
  const context = {TextEncoder, TextDecoder, document: {getElementById: () => current.root, createElement: () => new Element(),
    querySelector: s => ({content: s.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf'})},
    Option: function (text, value) { Object.assign(this, option(text, value)); },
    fetch: async (url, options) => { requests.push({url, options}); return handler(url, options); }};
  vm.createContext(context); vm.runInContext(source, context);
  return {api: context.ProjectRuntime, requests, get fields() { return current.fields; }, get root() { return current.root; },
    replacePage(id) { current.root.isConnected = false; current = page(id); return current; }};
}
test('metadata is safe text, revision scope exact, mapped servers environment filtered and deduplicated', async () => {
  const app = setup(); await app.api.open();
  assert.equal(app.requests[0].url, '/api/v1/projects/3/runtime/TEST');
  assert.deepEqual(app.fields.revision.options.map(o => o.value), ['', '11']);
  assert.deepEqual(app.fields.server.options.map(o => o.value), ['', '1']);
  assert.equal(app.fields.mode.options[0].disabled, true);
  app.fields.component.value = '7'; app.api.changeComponent();
  assert.deepEqual(app.fields.revision.options.map(o => o.value), ['', '12']);
  assert.match(app.fields.metadata.children[2].textContent, /<img onerror=x>/);
});
test('view permission cannot write or read log bodies and booleans fail closed', async () => {
  const app = setup(async () => response(data({canManage: 'true', canReadLogs: false})));
  await app.api.open(); app.fields.content.value = 'PASSWORD=secret'; app.fields['revision-reason'].value = '사유';
  await app.api.saveRevision(); await app.api.saveBinding(); await app.api.readLogs();
  assert.equal(app.requests.length, 1); assert.equal(app.fields.editor.hidden, true); assert.equal(app.fields.editor.disabled, true);
});
test('failed reload clears earlier authorization and log body, with generic safe error', async () => {
  let fail = false; const app = setup(async () => fail ? response(null, 403, 'PRIVATE=backend-error') : response(data()));
  await app.api.open(); app.fields['log-text'].textContent = 'old-log'; fail = true; await app.api.open();
  app.fields.content.value = 'KEY=value'; app.fields['revision-reason'].value = '사유'; await app.api.saveRevision();
  assert.equal(app.requests.length, 2); assert.equal(app.fields.editor.hidden, true); assert.equal(app.fields['read-logs'].disabled, true);
  assert.equal(app.fields['log-text'].textContent, ''); assert.doesNotMatch(app.fields.status.textContent, /backend-error/);
});
test('revision POST sends scoped body/CSRF once, clears secret and reloads metadata', async () => {
  let finish;
  const app = setup(async (_url, options) => options ? new Promise(resolve => { finish = resolve; }) : response(data()));
  await app.api.open(); app.fields.component.value = '7'; app.api.changeComponent();
  app.fields.content.value = 'DB_PASSWORD=local-fixture'; app.fields.file.value = 'fixture.env'; app.fields['revision-reason'].value = '신규 리비전';
  const saving = app.api.saveRevision(); await app.api.saveRevision();
  assert.equal(app.requests.length, 2); assert.equal(app.fields.environment.disabled, true);
  const req = app.requests[1]; assert.equal(req.url, '/api/v1/projects/3/runtime/TEST/revisions');
  assert.equal(req.options.headers['X-CSRF-TOKEN'], 'csrf');
  assert.deepEqual(JSON.parse(req.options.body), {componentId: 7, content: 'DB_PASSWORD=local-fixture', reason: '신규 리비전'});
  finish(response({id: 13})); await saving;
  assert.equal(app.fields.content.value, ''); assert.equal(app.fields.file.value, ''); assert.equal(app.requests.length, 3);
  assert.equal(app.fields.environment.disabled, false); assert.match(app.fields.status.textContent, /승인 내용/);
});
test('failed revision save never echoes raw backend submitted secret or auto retries', async () => {
  const app = setup(async (_url, options) => options ? response(null, 400, 'SECRET=unsafe-server-excerpt') : response(data()));
  await app.api.open(); app.fields.content.value = 'SECRET=fixture'; app.fields['revision-reason'].value = '사유';
  await app.api.saveRevision();
  assert.equal(app.fields.content.value, 'SECRET=fixture'); assert.doesNotMatch(app.fields.status.textContent, /unsafe-server-excerpt/);
  assert.equal(app.requests.length, 2); assert.equal(app.fields.editor.disabled, false);
});
test('UTF8 byte limit and reason bounds prevent revision network writes', async () => {
  const app = setup(); await app.api.open(); app.fields.content.value = 'KEY=' + '가'.repeat(23000); app.fields['revision-reason'].value = '사유';
  await app.api.saveRevision(); app.fields.content.value = 'KEY=value'; app.fields['revision-reason'].value = 'x'.repeat(401); await app.api.saveRevision();
  assert.equal(app.requests.length, 1);
});
test('binding rejects default inheritance, wrong component revision and invalid tmp range', async () => {
  const app = setup(); await app.api.open(); app.fields['binding-reason'].value = '사유'; app.fields.mode.value = 'INHERIT'; await app.api.saveBinding();
  app.fields.mode.value = 'MANAGED_REVISION'; app.fields.revision.value = '12'; await app.api.saveBinding();
  app.fields.revision.value = '11'; app.fields.tmp.value = '15'; await app.api.saveBinding();
  app.fields.tmp.value = '16.5'; await app.api.saveBinding(); assert.equal(app.requests.length, 1);
});
test('NONE explicitly clears env revision but preserves independent log/tmp declaration', async () => {
  const app = setup(); await app.api.open(); app.fields.mode.value = 'NONE'; app.fields.revision.value = '11';
  app.fields['persistent-logs'].checked = true; app.fields.tmp.value = '32'; app.fields['binding-reason'].value = '환경변수 해제';
  await app.api.saveBinding(); const body = JSON.parse(app.requests[1].options.body);
  assert.deepEqual(body, {componentId: 0, mode: 'NONE', revisionId: null, persistentLogs: true, tmpMiB: 32, reason: '환경변수 해제', stopGraceSeconds: 30});
});
test('component inheritance clears local storage fields and sends explicit inheritance', async () => {
  const app = setup(); await app.api.open(); app.fields.component.value = '7'; app.api.changeComponent();
  app.fields['persistent-logs'].checked = true; app.fields.tmp.value = '64'; app.fields.mode.value = 'INHERIT'; app.api.changeMode();
  assert.equal(app.fields.tmp.value, '0'); assert.equal(app.fields['persistent-logs'].checked, false);
  assert.equal(app.fields.tmp.disabled, true); app.fields['binding-reason'].value = '기본 설정 상속'; await app.api.saveBinding();
  assert.deepEqual(JSON.parse(app.requests[1].options.body), {componentId: 7, mode: 'INHERIT', revisionId: null, persistentLogs: false, tmpMiB: 0, reason: '기본 설정 상속', stopGraceSeconds: null});
});
test('environment change clears previous secrets/log bodies and chooses environment endpoint', async () => {
  const app = setup(); await app.api.open(); app.fields.content.value = 'KEY=private'; app.fields['log-text'].textContent = 'old-log';
  app.fields.environment.value = 'PROD'; await app.api.changeEnvironment();
  assert.equal(app.fields.content.value, ''); assert.equal(app.fields['log-text'].textContent, '');
  assert.equal(app.requests[1].url, '/api/v1/projects/3/runtime/PROD'); assert.deepEqual(app.fields.server.options.map(o => o.value), ['', '2']);
});
for (const nextId of ['4', '3']) test('late metadata response discarded after SPA replacement project ' + nextId, async () => {
  let finish; const app = setup(() => new Promise(resolve => { finish = resolve; }));
  const loading = app.api.open(); const next = app.replacePage(nextId); finish(response(data())); await loading;
  assert.equal(next.fields.metadata.children.length, 0); assert.equal(next.fields.editor.disabled, false);
});
test('file upload bounds and late read cannot place old secret into new SPA page', async () => {
  const app = setup(); await app.api.open(); app.fields.file.files = [{size: 65537, arrayBuffer: () => { throw new Error('must not read oversized file'); }}];
  await app.api.uploadFile(); assert.equal(app.fields.content.value, '');
  let finish; app.fields.file.files = [{size: 20, arrayBuffer: () => new Promise(resolve => { finish = resolve; })}];
  const reading = app.api.uploadFile(); const next = app.replacePage('4'); finish(new TextEncoder().encode('KEY=old-secret').buffer); await reading;
  assert.equal(next.fields.content.value, '');
});
test('UTF8 file import preserves bytes and refuses invalid encoding without replacement', async () => {
  const app = setup(); await app.api.open();
  app.fields.file.files = [{size: 12, arrayBuffer: async () => new TextEncoder().encode('KEY=한글\n').buffer}];
  await app.api.uploadFile(); assert.equal(app.fields.content.value, 'KEY=한글\n');
  app.fields.file.files = [{size: 2, arrayBuffer: async () => new Uint8Array([0xff, 0xfe]).buffer}];
  await app.api.uploadFile(); assert.equal(app.fields.content.value, ''); assert.match(app.fields.status.textContent, /UTF-8/);
});
test('read-only metadata describes inherited storage without enabling editor', async () => {
  const app = setup(async () => response(data({canManage: false, bindings: [{componentId: 0, mode: 'MANAGED_REVISION', revisionId: 11, persistentLogs: true, tmpMiB: 64}]})));
  await app.api.open(); app.fields.component.value = '7'; app.api.changeComponent();
  assert.match(app.fields.metadata.children[1].textContent, /유지.*64MiB.*#11/); assert.equal(app.fields.editor.hidden, true);
});
test('file logs use exact scoped URL and textContent with truncation notice', async () => {
  const app = setup(async url => response(url.includes('/logs?') ? {text: '<script>unsafe()</script>', truncated: true} : data()));
  await app.api.open(); app.fields.component.value = '7'; app.api.changeComponent(); app.fields.server.value = '1'; await app.api.readLogs();
  assert.equal(app.requests[1].url, '/api/v1/projects/3/runtime/TEST/logs?serverId=1&componentId=7&lines=200');
  assert.equal(app.fields['log-text'].textContent, '<script>unsafe()</script>'); assert.match(app.fields['log-status'].textContent, /일부/);
});
test('invalid target/line count blocks log fetch; stale log response cannot overwrite new SPA', async () => {
  let finish; const app = setup(async url => url.includes('/logs?') ? new Promise(resolve => { finish = resolve; }) : response(data()));
  await app.api.open(); app.fields.server.value = '2'; await app.api.readLogs(); app.fields.server.value = '1'; app.fields.lines.value = '1001'; await app.api.readLogs();
  assert.equal(app.requests.length, 1); app.fields.lines.value = '200'; const reading = app.api.readLogs(); const next = app.replacePage('3');
  finish(response({text: 'old-private-log', truncated: false})); await reading; assert.equal(next.fields['log-text'].textContent, undefined);
});


test('stop grace rejects fractional/out-of-range UI values and submits environment scoped integer', async () => {
  const app=setup(); await app.api.open(); app.fields.mode.value='NONE';app.fields['binding-reason'].value='유예 시간 변경';
  for(const value of ['0','301','1.5','']) {app.fields['stop-grace'].value=value;await app.api.saveBinding();assert.equal(app.requests.length,1);}
  app.fields['stop-grace'].value='90';await app.api.saveBinding();
  assert.equal(JSON.parse(app.requests[1].options.body).stopGraceSeconds,90);
  assert.equal(app.requests[1].url,'/api/v1/projects/3/runtime/TEST/binding');
});
