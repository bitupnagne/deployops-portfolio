const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/integration.html'), 'utf8');
const script = html.slice(html.indexOf('  <script>') + '  <script>'.length, html.lastIndexOf('  </script>'));
const ids = ['nexus-url', 'nexus-user', 'nexus-pass', 'nexus-registry', 'nexus-registry-api-url'];
const flush = () => new Promise(resolve => setImmediate(resolve));
const success = { success: true, message: 'API·Registry 정상 · 실제 CI push는 별도 확인' };

async function page({ deferSettings = false } = {}) {
  const elements = new Map(), requests = [], settingsRequests = [], toasts = [];
  const make = id => {
    const listeners = new Map();
    return { id, value: '', checked: false, textContent: '', style: {}, className: '', isConnected: true,
      addEventListener(type, callback) {
        if (!listeners.has(type)) listeners.set(type, []);
        listeners.get(type).push(callback);
      },
      dispatch(type) { for (const callback of listeners.get(type) || []) callback({ target: this }); } };
  };
  const element = id => {
    if (id === 'nexus-cleanup-body') return null;
    if (!elements.has(id)) elements.set(id, make(id));
    return elements.get(id);
  };
  const context = vm.createContext({
    window: { addEventListener() {} },
    document: { getElementById: element,
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf' }) },
    showToast(message, type) { toasts.push({ message, type }); },
    fetch: (url, options) => {
      if (!options && url === '/api/v1/settings/integration' && deferSettings) {
        let resolve;
        const pending = new Promise(yes => { resolve = yes; });
        settingsRequests.push({ complete(data) { resolve({ json: async () => ({ success: true, data }) }); } });
        return pending;
      }
      if (!options) return Promise.resolve({ json: async () => ({ success: true, data: {} }) });
      let resolve, reject;
      const pending = new Promise((yes, no) => { resolve = yes; reject = no; });
      requests.push({ url, body: options.body ? JSON.parse(options.body) : null,
        complete(body = success) { resolve({ json: async () => body }); }, fail() { reject(new Error('network')); } });
      return pending;
    }
  });
  vm.runInContext(script, context);
  await flush();
  return { context, element, requests, settingsRequests, toasts,
    async reenter() {
      for (const el of elements.values()) el.isConnected = false;
      elements.clear();
      vm.runInContext(script, context);
      await flush();
    },
    edit(id, value, event = 'input') { element(id).value = value; element(id).dispatch(event); },
    replace(id) { const old = element(id); old.isConnected = false; elements.set(id, make(id)); return old; },
    async pass(type = 'nexus') {
      context.testConnection(type); requests.at(-1).complete(); await flush();
    } };
}

test('Nexus starts untested and successful test sends exactly the existing five fields', async () => {
  const s = await page();
  assert.match(html, /id="nexus-status"[^>]*>미검사/);
  assert.match(html, /Nexus 검사 결과는 이 페이지의 현재 입력 기준/);
  assert.equal(s.element('nexus-status').textContent, '미검사');
  ids.forEach((id, i) => { s.element(id).value = ` value${i} `; });
  await s.pass();
  assert.deepEqual(s.requests.at(-1).body,
    { url: 'value0', user: 'value1', pass: 'value2', registry: 'value3', registryApiUrl: 'value4' });
  assert.equal(s.element('nexus-status').textContent, '정상');
  assert.match(s.element('nexus-result').textContent, /실제 CI push는 별도 확인/);
});

test('Runner Maven endpoint loads and saves separately from the management API test target', async () => {
  const s = await page({deferSettings:true});
  s.settingsRequests.at(-1).complete({'nexus.url':'http://management:8081',
    'nexus.maven.base-url':'http://runner:8081'});
  await flush();
  assert.equal(s.element('nexus-maven-base-url').value,'http://runner:8081');
  await s.pass();
  assert.equal(s.requests.at(-1).body.url,'http://management:8081');
  assert.equal(s.requests.at(-1).body.mavenBaseUrl,undefined);
  s.edit('nexus-maven-base-url','http://new-runner:8081');
  assert.equal(s.element('nexus-status').textContent,'미검사');
  const saving=s.context.saveSection('nexus');
  assert.equal(s.requests.at(-1).body['nexus.maven.base-url'],'http://new-runner:8081');
  assert.equal(s.requests.at(-1).body['nexus.url'],'http://management:8081');
  s.requests.at(-1).complete(); await saving;
});

for (const id of ids) for (const event of ['input', 'change']) {
  test(`${id} ${event} invalidates a successful Nexus test even after restoring input`, async () => {
    const s = await page();
    await s.pass();
    s.edit(id, 'edited', event);
    assert.equal(s.element('nexus-status').textContent, '미검사');
    assert.equal(s.element('nexus-result').textContent, '');
    s.edit(id, '', event);
    assert.equal(s.element('nexus-status').textContent, '미검사');
  });
}

for (const outcome of ['success', 'failure', 'network']) {
  test(`in-flight Nexus ${outcome} cannot publish after edit and restore`, async () => {
    const s = await page();
    s.context.testConnection('nexus');
    const pending = s.requests.at(-1);
    s.edit('nexus-url', 'edited'); s.edit('nexus-url', '');
    if (outcome === 'network') pending.fail();
    else pending.complete(outcome === 'success' ? success : { success: false, message: 'old error' });
    await flush();
    assert.equal(s.element('nexus-status').textContent, '미검사');
    assert.equal(s.element('nexus-result').textContent, '');
  });
}

test('snapshot rejects direct value mutation without an input event', async () => {
  const s = await page();
  s.context.testConnection('nexus');
  s.element('nexus-registry-api-url').value = 'different';
  s.requests.at(-1).complete(); await flush();
  assert.notEqual(s.element('nexus-status').textContent, '정상');
  assert.doesNotMatch(s.element('nexus-result').textContent, /API·Registry 정상/);
});

test('out-of-order same-input requests accept only the newest result', async () => {
  const s = await page();
  s.context.testConnection('nexus'); const old = s.requests.at(-1);
  s.context.testConnection('nexus'); const current = s.requests.at(-1);
  current.complete(); await flush();
  old.complete({ success: false, message: 'older failure' }); await flush();
  assert.equal(s.element('nexus-status').textContent, '정상');
  assert.doesNotMatch(s.element('nexus-result').textContent, /older failure/);
});

for (const outcome of ['success', 'failure', 'network']) {
  test(`old page ${outcome} does not mutate detached or replacement Nexus DOM`, async () => {
    const s = await page();
    s.context.testConnection('nexus'); const pending = s.requests.at(-1);
    const old = s.replace('nexus-result'); s.replace('nexus-status');
    const previous = old.textContent;
    if (outcome === 'network') pending.fail();
    else pending.complete(outcome === 'success' ? success : { success: false });
    await flush();
    assert.equal(old.textContent, previous);
    assert.equal(s.element('nexus-result').textContent, '');
    assert.equal(s.element('nexus-status').textContent, '');
  });
}

test('replacement of a single Nexus field suppresses a late same-input response', async () => {
  const s = await page();
  s.context.testConnection('nexus');
  s.replace('nexus-user');
  s.requests.at(-1).complete(); await flush();
  assert.notEqual(s.element('nexus-status').textContent, '정상');
});

test('save invalidates success and tests begun before or during save cannot restore it', async () => {
  const s = await page();
  await s.pass();
  s.context.testConnection('nexus'); const before = s.requests.at(-1);
  const saving = s.context.saveSection('nexus'); const save = s.requests.at(-1);
  assert.equal(s.element('nexus-status').textContent, '미검사');
  s.context.testConnection('nexus'); const during = s.requests.at(-1);
  save.complete(); await saving;
  const saved = s.element('nexus-result').textContent;
  assert.match(saved, /저장되었습니다.*다시 실행/);
  assert.equal(s.element('nexus-status').textContent, '미검사');
  before.complete(); during.complete(); await flush();
  assert.equal(s.element('nexus-result').textContent, saved);
  assert.equal(s.element('nexus-status').textContent, '미검사');
});

test('failed save retains untested badge without claiming connection failure', async () => {
  const s = await page(); await s.pass();
  const saving = s.context.saveSection('nexus');
  const rejected = assert.rejects(saving, /저장 실패/);
  s.requests.at(-1).complete({ success: false }); await rejected;
  assert.equal(s.element('nexus-status').textContent, '미검사');
  assert.equal(s.element('nexus-result').textContent, '');
});

test('Nexus secret deletion invalidates before and after its null-payload mutation', async () => {
  const s = await page(); await s.pass();
  const deleting = s.context.clearSecret('nexus.pass', 'nexus-pass'); const deletion = s.requests.at(-1);
  assert.deepEqual(deletion.body, { 'nexus.pass': null });
  assert.equal(s.element('nexus-status').textContent, '미검사');
  s.context.testConnection('nexus'); const during = s.requests.at(-1);
  deletion.complete(); await deleting;
  during.complete(); await flush();
  assert.equal(s.element('nexus-status').textContent, '미검사');
  assert.equal(s.element('nexus-result').textContent, '');
  assert.match(s.element('nexus-pass-configured').textContent, /미등록/);
});

test('current failure and network failure remain visible for the current Nexus input', async () => {
  const s = await page();
  s.context.testConnection('nexus'); s.requests.at(-1).complete({ success: false, message: 'current error' });
  await flush(); assert.equal(s.element('nexus-status').textContent, '오류');
  assert.match(s.element('nexus-result').textContent, /current error/);
  s.context.testConnection('nexus'); s.requests.at(-1).fail(); await flush();
  assert.equal(s.element('nexus-status').textContent, '오류');
  assert.equal(s.element('nexus-result').textContent, '요청 실패');
});

test('other integration payloads and badges remain unaffected by Nexus changes', async () => {
  const s = await page();
  s.element('gitlab-url').value = 'https://gitlab.example'; s.element('gitlab-token').value = 'fixture-token';
  await s.pass('gitlab');
  assert.deepEqual(s.requests.at(-1).body, { url: 'https://gitlab.example', token: 'fixture-token' });
  const gitlabResult = s.element('gitlab-result').textContent;
  await s.pass();
  s.edit('nexus-url', 'edited');
  assert.equal(s.element('gitlab-status').textContent, '정상');
  assert.equal(s.element('gitlab-result').textContent, gitlabResult);
  await s.pass();
  for (const id of ['gitlab-url', 'office-base-url', 'deploy-public-url', 'deploy-ssh-key-path',
    'nexus-cleanup-tag', 'nexus-cleanup-reason', 'deploy-image-staging-dir']) s.edit(id, 'other edit');
  assert.equal(s.element('nexus-status').textContent, '정상');
  const saving = s.context.saveSection('gitlab'); s.requests.at(-1).complete(); await saving;
  assert.equal(s.element('nexus-status').textContent, '정상');
});

test('old settings response cannot hydrate or invalidate a successful reentered Nexus page', async () => {
  const s = await page({ deferSettings: true });
  const oldSettings = s.settingsRequests.at(-1);
  await s.reenter();
  s.settingsRequests.at(-1).complete({ 'nexus.url': 'https://current.example',
    'nexus.user': 'current-user', 'nexus.registry': 'current-registry',
    'nexus.registry.api-url': 'current-internal', configuredSecrets: { 'nexus.pass': true } });
  await flush();
  await s.pass();
  const result = s.element('nexus-result').textContent;
  oldSettings.complete({ 'nexus.url': 'https://old.example', 'nexus.user': 'old-user',
    'nexus.registry': 'old-registry', 'nexus.registry.api-url': 'old-internal', configuredSecrets: {} });
  await flush();
  assert.equal(s.element('nexus-status').textContent, '정상');
  assert.equal(s.element('nexus-result').textContent, result);
  assert.equal(s.element('nexus-url').value, 'https://current.example');
  assert.equal(s.element('nexus-user').value, 'current-user');
  assert.equal(s.element('nexus-registry').value, 'current-registry');
  assert.equal(s.element('nexus-registry-api-url').value, 'current-internal');
  assert.match(s.element('nexus-pass-configured').textContent, /등록됨/);
});

for (const action of ['save', 'delete']) {
  test(`old Nexus ${action} completion cannot clear new secret input or invalidate new page`, async () => {
    const s = await page();
    s.element('nexus-pass').value = 'old-replacement-fixture';
    const completed = action === 'save' ? s.context.saveSection('nexus')
      : s.context.clearSecret('nexus.pass', 'nexus-pass');
    const oldRequest = s.requests.at(-1);
    await s.reenter();
    s.edit('nexus-pass', 'new-page-fixture');
    await s.pass();
    const result = s.element('nexus-result').textContent;
    const configured = s.element('nexus-pass-configured').textContent;
    oldRequest.complete(); await completed;
    assert.equal(s.element('nexus-pass').value, 'new-page-fixture');
    assert.equal(s.element('nexus-pass-configured').textContent, configured);
    assert.equal(s.element('nexus-status').textContent, '정상');
    assert.equal(s.element('nexus-result').textContent, result);
  });
}

for (const action of ['save', 'delete']) {
  test(`old Nexus ${action} rejection cannot toast on a reentered page`, async () => {
    const s = await page();
    const completed = action === 'save' ? s.context.saveSection('nexus')
      : s.context.clearSecret('nexus.pass', 'nexus-pass');
    const rejected = action === 'save' ? assert.rejects(completed, /network/) : completed;
    const oldRequest = s.requests.at(-1);
    await s.reenter();
    oldRequest.fail(); await rejected;
    assert.deepEqual(s.toasts, []);
    assert.equal(s.element('nexus-status').textContent, '미검사');
    assert.equal(s.element('nexus-result').textContent, '');
  });
}

test('current Nexus save/delete rejection retains failure toasts', async () => {
  const s = await page();
  const saving = s.context.saveSection('nexus');
  const rejected = assert.rejects(saving, /network/);
  s.requests.at(-1).fail(); await rejected;
  const deleting = s.context.clearSecret('nexus.pass', 'nexus-pass');
  s.requests.at(-1).fail(); await deleting;
  assert.deepEqual(s.toasts, [
    { message: '저장에 실패했습니다.', type: 'error' },
    { message: '비밀값 삭제에 실패했습니다.', type: 'error' }
  ]);
});

test('non-Nexus save/delete rejection keeps its existing toast behavior after reentry', async () => {
  const s = await page();
  const saving = s.context.saveSection('gitlab');
  const rejected = assert.rejects(saving, /network/);
  const saveRequest = s.requests.at(-1);
  const deleting = s.context.clearSecret('gitlab.token', 'gitlab-token');
  const deleteRequest = s.requests.at(-1);
  await s.reenter();
  saveRequest.fail(); await rejected;
  deleteRequest.fail(); await deleting;
  assert.deepEqual(s.toasts, [
    { message: '저장에 실패했습니다.', type: 'error' },
    { message: '비밀값 삭제에 실패했습니다.', type: 'error' }
  ]);
});
