const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/project-build-files.js'), 'utf8');
const BASE = 'a'.repeat(40), CONTENT = 'b'.repeat(64);
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); };
function file(kind, extra = {}) {
  return {success: true, data: {exists: true, content: kind === 'ci' ? 'job: original' : 'FROM runtime:original', ref: 'main',
    baseCommitSha: BASE, contentSha256: CONTENT, filePath: kind === 'ci' ? '.gitlab-ci.yml' : 'Dockerfile', ...extra}};
}
function profile(extra = {}) {
  return {success: true, data: {sourceType: 'MAVEN_BOOT', selectedSourceType: 'MAVEN_BOOT', selectionRequired: false,
    baseCommitSha: BASE, evidence: ['pom.xml'], warnings: [], generatedDockerfile: 'FROM runtime:generated', generatedCi: 'job: generated', ...extra}};
}
function setup(capability = 'true', handler) {
  let page = {isConnected: true, dataset: {id: '3', canReviewMergeRequests: capability}};
  const fields = {}, requests = [], confirmations = [], notices = [], mrLoads = [];
  function element(id) {
    if (!fields[id]) {
      const listeners = {};
      const gutter = {textContent: '', scrollTop: 0};
      fields[id] = {value: '', textContent: '', innerHTML: '', className: '', readOnly: true, disabled: false, hidden: true,
        dataset: {}, selectionStart: 0, selectionEnd: 0, scrollTop: 0,
        addEventListener(name, fn) { (listeners[name] ||= []).push(fn); },
        dispatchEvent(event) { for (const fn of listeners[event.type] || []) fn(event); },
        setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
        closest: () => ({querySelector: () => gutter}), gutter, focus() {}, scrollIntoView() {}};
    }
    return fields[id];
  }
  element('build-files-ref').value = 'main'; element('proj-branch').value = 'main';
  const context = {window: {DeploySelect: {sync() {}}}, URLSearchParams,
    document: {getElementById: id => id === 'project-data' ? page : element(id), querySelector: () => ({content: 'csrf'})},
    Event: class {constructor(type) {this.type = type;}},
    fetch: async (url, options) => {
      requests.push({url, options});
      const answer = handler ? await handler(url, options) : options?.method === 'POST' ? {success: true, data: {mergeRequest: {iid: 9}}}
        : url.includes('/build-profile?') ? profile() : file(url.includes('/ci-file?') ? 'ci' : 'dockerfile');
      return {ok: answer.httpOk !== false, json: async () => answer};
    },
    showToast: message => notices.push(message), renderCiSuggestions() {}, mergeRequestsLoaded: true,
    openConfirm: (title, body, callback) => confirmations.push({title, body, callback}), loadMergeRequests: () => mrLoads.push(true)};
  vm.createContext(context); vm.runInContext(source, context);
  return {api: context.ProjectBuildFiles, fields, element, requests, confirmations, notices, mrLoads,
    setCapability(value) {page.dataset.canReviewMergeRequests = value;},
    replacePage() {page.isConnected = false; page = {isConnected: true, dataset: {id: '3', canReviewMergeRequests: 'true'}};},
    change(id, value) {element(id).value = value; element(id).dispatchEvent({type: id === 'build-files-ref' ? 'input' : 'change'});}};
}
async function loaded(capability, handler) { const screen = setup(capability, handler); screen.api.open(); await settle(); return screen; }

test('VIEW-only users see both repository files read-only and cannot submit or replace drafts', async () => {
  const screen = await loaded('false');
  assert.equal(screen.fields['ci-editor'].value, 'job: original');
  assert.equal(screen.fields['dockerfile-editor'].value, 'FROM runtime:original');
  assert.equal(screen.fields['ci-editor'].readOnly, true); assert.equal(screen.fields['dockerfile-editor'].readOnly, true);
  screen.api.submit('ci'); await screen.api.preview('ci'); screen.api.submit('dockerfile');
  assert.equal(screen.requests.length, 2); assert.equal(screen.confirmations.length, 0);
  assert.ok(screen.notices.every(message => message.includes('MR 검토 권한')));
});

test('missing or malformed capability fails closed before repository mutation', async () => {
  for (const capability of [undefined, 'TRUE', '1', false]) {
    const screen = await loaded(capability === undefined ? null : capability);
    screen.fields['ci-editor'].value = 'changed'; screen.api.submit('ci'); await screen.api.preview('ci');
    assert.equal(screen.requests.length, 2); assert.equal(screen.confirmations.length, 0);
  }
});

test('CI MR submits the edited content with pinned commit, original hash and CSRF without approving or merging', async () => {
  const screen = await loaded(); screen.fields['ci-editor'].value = 'job: changed'; screen.api.submit('ci');
  assert.equal(screen.confirmations.length, 1); await screen.confirmations[0].callback();
  const req = screen.requests[2]; assert.equal(req.options.method, 'POST'); assert.equal(req.options.headers.csrf, 'csrf');
  assert.deepEqual(JSON.parse(req.options.body), {content: 'job: changed', targetBranch: 'main', baseCommitSha: BASE, originalSha256: CONTENT});
  assert.equal(screen.fields['ci-editor'].readOnly, true); assert.equal(screen.mrLoads.length, 1);
  assert.equal(screen.fields['dockerfile-editor'].value, 'FROM runtime:original');
  assert.ok(screen.requests.every(row => !/approve|merge-requests\/\d+\/merge/.test(row.url)));
});

test('Dockerfile MR preserves active component ID and actual stored path returned by the server', async () => {
  const screen = setup('true', async (url, options) => options?.method === 'POST' ? {success: true, data: {mergeRequest: {iid: 11}}}
    : file(url.includes('/ci-file?') ? 'ci' : 'dockerfile', url.includes('/dockerfile?') ? {filePath: 'api/Dockerfile'} : {}));
  screen.change('build-files-component', '17'); screen.api.open(); await settle();
  screen.fields['dockerfile-editor'].value = 'FROM runtime:patched'; screen.api.submit('dockerfile'); await screen.confirmations[0].callback();
  assert.match(screen.requests[0].url, /componentId=17/); assert.doesNotMatch(screen.requests[1].url, /componentId/);
  assert.deepEqual(JSON.parse(screen.requests[2].options.body), {content: 'FROM runtime:patched', targetBranch: 'main', baseCommitSha: BASE,
    originalSha256: CONTENT, filePath: 'api/Dockerfile', componentId: 17});
});

test('revoked capability, changed MR target or same-project SPA replacement invalidate a pending confirmation', async () => {
  for (const invalidate of [screen => screen.setCapability('false'), screen => screen.replacePage(), screen => {screen.element('proj-mr-target-branch').value = 'release';}]) {
    const screen = await loaded(); screen.fields['ci-editor'].value = 'changed'; screen.api.submit('ci'); invalidate(screen);
    await screen.confirmations[0].callback(); assert.equal(screen.requests.length, 2);
  }
});

test('editing after MR confirmation opened preserves the newer content and makes no POST', async () => {
  const screen = await loaded(); screen.fields['ci-editor'].value = 'first'; screen.api.submit('ci');
  screen.fields['ci-editor'].value = 'newer'; await screen.confirmations[0].callback();
  assert.equal(screen.requests.length, 2); assert.equal(screen.fields['ci-editor'].value, 'newer');
});

test('load failure cannot masquerade as missing or become an editable create operation', async () => {
  const screen = await loaded('true', async () => ({success: false, message: 'GitLab access denied'}));
  assert.match(screen.fields['ci-file-state'].textContent, /조회 실패.*미확인/);
  assert.doesNotMatch(screen.fields['ci-file-state'].textContent, /파일이 없습니다/);
  assert.equal(screen.fields['ci-editor'].readOnly, true); assert.equal(screen.fields['ci-save'].disabled, true);
  screen.fields['ci-editor'].value = 'must not submit'; screen.api.submit('ci'); assert.equal(screen.requests.length, 2);
});

test('a truly missing file stays empty until explicit editing or draft adoption, including viewer-only guidance', async () => {
  const handler = async url => file(url.includes('/ci-file?') ? 'ci' : 'dockerfile', {exists: false, content: '', contentSha256: null, template: 'not auto filled'});
  const screen = await loaded('true', handler);
  assert.equal(screen.fields['ci-editor'].value, ''); assert.match(screen.fields['ci-file-state'].textContent, /조회 성공.*파일이 없습니다/);
  screen.fields['ci-editor'].value = 'job: new'; screen.api.submit('ci'); await screen.confirmations[0].callback();
  assert.equal(JSON.parse(screen.requests[2].options.body).originalSha256, null);
  const viewer = await loaded('false', handler); assert.match(viewer.fields['ci-file-state'].textContent, /조회만/);
  assert.doesNotMatch(viewer.fields['ci-file-state'].textContent, /직접 작성/);
});

test('late file responses cannot cross component/ref or page boundaries', async () => {
  for (const invalidate of [screen => screen.change('build-files-component', '8'), screen => screen.change('build-files-ref', 'release'), screen => screen.replacePage()]) {
    const finish = []; const screen = setup('true', () => new Promise(resolve => finish.push(resolve)));
    screen.api.open(); invalidate(screen); finish[0](file('dockerfile')); finish[1](file('ci')); await settle();
    assert.equal(screen.fields['ci-editor'].value, ''); assert.equal(screen.fields['dockerfile-editor'].value, '');
  }
});

test('a file changed during loading is not overwritten by the asynchronous response', async () => {
  const finish = []; const screen = setup('true', () => new Promise(resolve => finish.push(resolve)));
  screen.api.open(); screen.fields['ci-editor'].value = 'newer local content';
  finish[0](file('dockerfile')); finish[1](file('ci')); await settle();
  assert.equal(screen.fields['ci-editor'].value, 'newer local content'); assert.equal(screen.fields['ci-editor'].readOnly, true);
  assert.match(screen.fields['ci-file-state'].textContent, /현재 내용은 유지/);
});

test('detecting existing files or an unknown type never overwrites either editor or silently chooses a type', async () => {
  const screen = await loaded('true', async url => url.includes('/build-profile?') ? profile({sourceType: 'UNKNOWN', selectedSourceType: null,
    selectionRequired: true, existingCi: 'wrong existing CI', generatedCi: 'wrong generated CI'}) : file(url.includes('/ci-file?') ? 'ci' : 'dockerfile'));
  screen.fields['ci-editor'].value = 'job: local'; screen.fields['dockerfile-editor'].value = 'FROM local:work';
  await screen.api.detect();
  assert.equal(screen.fields['ci-editor'].value, 'job: local'); assert.equal(screen.fields['dockerfile-editor'].value, 'FROM local:work');
  assert.equal(screen.element('build-source-type').value, ''); assert.match(screen.fields['build-detect-status'].textContent, /유형 확인 필요/);
  const count = screen.requests.length; await screen.api.preview('ci'); assert.equal(screen.requests.length, count);
});

test('explicit type preview and diff are isolated from both source editors until confirmed adoption', async () => {
  const screen = await loaded(); screen.change('build-source-type', 'MAVEN_BOOT');
  await screen.api.preview('dockerfile');
  assert.match(screen.requests[2].url, /sourceType=MAVEN_BOOT/);
  assert.equal(screen.fields['dockerfile-editor'].value, 'FROM runtime:original');
  assert.equal(screen.fields['dockerfile-preview-code'].value, 'FROM runtime:generated');
  assert.match(screen.fields['dockerfile-diff'].textContent, /- FROM runtime:original/);
  screen.api.applyPreview('dockerfile'); assert.equal(screen.fields['dockerfile-editor'].value, 'FROM runtime:original');
  screen.confirmations[0].callback(); assert.equal(screen.fields['dockerfile-editor'].value, 'FROM runtime:generated');
  assert.equal(screen.fields['ci-editor'].value, 'job: original'); assert.equal(screen.requests.length, 3);
});

test('source choice, content, and permission changes invalidate generated draft adoption', async () => {
  for (const invalidate of [screen => screen.change('build-source-type', 'JAVA_PROC'), screen => screen.setCapability('false'), screen => {screen.fields['ci-editor'].value = 'newer';}]) {
    const screen = await loaded(); screen.change('build-source-type', 'MAVEN_BOOT'); await screen.api.preview('ci');
    screen.api.applyPreview('ci'); invalidate(screen); screen.confirmations[0].callback();
    assert.notEqual(screen.fields['ci-editor'].value, 'job: generated'); assert.equal(screen.requests.length, 3);
  }
});

test('profile at a newer commit cannot be applied to an older file snapshot', async () => {
  const screen = await loaded('true', async url => url.includes('/build-profile?') ? profile({baseCommitSha: 'c'.repeat(40)}) : file(url.includes('/ci-file?') ? 'ci' : 'dockerfile'));
  screen.change('build-source-type', 'MAVEN_BOOT'); await screen.api.preview('ci');
  assert.equal(screen.fields['ci-preview'].hidden, true); assert.equal(screen.fields['ci-editor'].value, 'job: original');
  assert.ok(screen.notices.some(message => message.includes('commit')));
});

test('reload asks before discarding a draft and aborts if that draft changes in the confirmation', async () => {
  const screen = await loaded(); screen.fields['ci-editor'].value = 'first draft'; screen.api.open(true);
  assert.equal(screen.requests.length, 2); screen.fields['ci-editor'].value = 'newer draft'; screen.confirmations[0].callback();
  assert.equal(screen.requests.length, 2); assert.equal(screen.fields['ci-editor'].value, 'newer draft');
});

test('MR rejection keeps edited content and base snapshot for correction without changing the source', async () => {
  const screen = await loaded('true', async (url, options) => options?.method === 'POST' ? {success: false, message: 'STALE_BASE'} : file(url.includes('/ci-file?') ? 'ci' : 'dockerfile'));
  screen.fields['ci-editor'].value = 'job: work'; screen.api.submit('ci'); await screen.confirmations[0].callback();
  assert.equal(screen.fields['ci-editor'].value, 'job: work'); assert.equal(screen.fields['ci-editor'].readOnly, false);
  assert.match(screen.fields['ci-file-state'].textContent, /MR 생성 실패.*STALE_BASE/); assert.equal(screen.mrLoads.length, 0);
});

test('Tab inserts two spaces at the caret and Shift+Tab removes indentation even at the line start', () => {
  const {api} = setup();
  assert.deepEqual(JSON.parse(JSON.stringify(api.indent('abc', 1, 1, false))), {value: 'a  bc', start: 3, end: 3});
  assert.deepEqual(JSON.parse(JSON.stringify(api.indent('  abc', 0, 0, true))), {value: 'abc', start: 0, end: 0});
  assert.deepEqual(JSON.parse(JSON.stringify(api.indent('\tabc', 2, 2, true))), {value: 'abc', start: 1, end: 1});
  assert.equal(api.indent('\n  abc', 0, 0, true).value, '\n  abc');
});

test('multi-line indent and outdent preserve unselected lines and exclude a trailing next-line selection', () => {
  const {api} = setup();
  assert.equal(api.indent('a\nb\nc', 0, 4, false).value, '  a\n  b\nc');
  assert.equal(api.indent('  a\n  b\nc', 0, 8, true).value, 'a\nb\nc');
  assert.equal(api.indent('  a\n b', 0, 6, true).value, 'a\nb');
});

test('Tab indentation updates editor content and visible line numbers; Escape then Tab exits the keyboard trap', async () => {
  const screen = await loaded(); const editor = screen.fields['ci-editor'];
  editor.value = 'one\ntwo'; editor.selectionStart = editor.selectionEnd = 4;
  let prevented = 0; const tab = {key: 'Tab', preventDefault() {prevented++;}};
  screen.api.keydown(tab, editor);
  assert.equal(editor.value, 'one\n  two'); assert.equal(editor.gutter.textContent, '1\n2'); assert.equal(prevented, 1);
  screen.api.keydown({key: 'Escape'}, editor); screen.api.keydown(tab, editor);
  assert.equal(prevented, 1, 'Native focus navigation is left intact for the next Tab');
  editor.readOnly = true; screen.api.keydown(tab, editor); assert.equal(prevented, 1);
});

test('raw repository markup is text in the editor and diff, never assigned as HTML', async () => {
  const payload = '<script>alert(1)</script>\n<img src=x onerror=alert(2)>';
  const screen = await loaded('true', async url => file(url.includes('/ci-file?') ? 'ci' : 'dockerfile', {content: payload}));
  assert.equal(screen.fields['ci-editor'].value, payload); screen.fields['ci-editor'].value += '\n# change'; screen.api.compare('ci');
  assert.match(screen.fields['ci-changes-code'].textContent, /<script>/); assert.equal(screen.fields['ci-changes-code'].innerHTML, '');
  assert.doesNotMatch(source, /\.innerHTML\s*=/);
});

test('file code area remains local and retains existing CI hooks and explicit source choices', () => {
  const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
  assert.match(html, /id="ci-editor"/); assert.match(html, /id="ci-save"/); assert.match(html, /id="ci-file-panel"/);
  assert.match(html, /@\{\/js\/project-build-files\.js\}/); assert.doesNotMatch(html, /https?:\/\/[^"']*(?:monaco|codemirror|ace)[^"']*\.js/);
  assert.match(html, /Esc를 누른 뒤 Tab/); assert.match(html, /value="JAVA_PROC"/); assert.match(html, /value="WAR_TOMCAT"/);
  assert.match(html, /for="build-files-ref">조회 브랜치<\/label>/); assert.doesNotMatch(source, /브랜치 또는 태그/);
  assert.match(html, /\.artifactType\.name\(\) != 'PROXY_RELOAD'/);
});
