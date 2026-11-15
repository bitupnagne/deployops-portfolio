const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const pickerScript = readFileSync(path.resolve(__dirname, '../../main/resources/static/js/nexus-pick.js'), 'utf8');

function screen() {
  const elements = new Map();
  const requests = [];
  const notices = [];
  const confirmations = [];
  let repositories = [];
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      value: '', style: {}, dataset: {}, classList: { add() {}, remove() {} },
      dispatchEvent() {}, textContent: '', innerHTML: ''
    });
    return elements.get(id);
  }
  const context = vm.createContext({
    document: {
      getElementById: element,
      querySelectorAll: () => [],
      querySelector: () => ({ content: 'csrf-token' })
    },
    Event: class Event {},
    escapeHtml: value => String(value ?? '').replace(/[&<>"']/g, c => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]),
    showToast: (...args) => notices.push(args),
    markInvalid() {}, closeModal() {},
    openConfirm: (title, message, confirm) => confirmations.push({ title, message, confirm }),
    fetch: async (url, options) => {
      requests.push({ url, ...options, body: options?.body ? JSON.parse(options.body) : null });
      return { json: async () => ({ success: true, data: { registry: 'misleading-global:5000', repositories } }) };
    }
  });
  vm.runInContext(pickerScript, context);
  return {
    context, element, requests, notices, confirmations,
    repositories: value => { repositories = value; },
    pick: name => context.nxPick({ dataset: { name }, classList: { add() {} } })
  };
}

const settle = () => new Promise(resolve => setImmediate(resolve));

async function createMaven(page, name) {
  page.context.nxOpen('repo-input', 'maven2', 'name', null, 'RELEASE');
  await settle();
  page.element('nx-new-name').value = name;
  page.context.nxCreate();
  page.confirmations.at(-1).confirm();
  await settle();
}

test('empty authorized list describes visibility instead of asserting no repository exists', async () => {
  const page = screen();
  page.context.nxOpen('repo-input', 'maven2', 'name');
  await settle();
  assert.match(page.element('nx-list').innerHTML, /조회할 수 있는 저장소/);
  assert.match(page.element('nx-list').innerHTML, /browse 권한/);
  assert.doesNotMatch(page.element('nx-list').innerHTML, /아직 없습니다|아래에서 만들/);
});

test('created but hidden repository retains exact name and scoped browse guidance without a second POST', async () => {
  const page = screen();
  await createMaven(page, 'qa-ui-release');
  assert.match(page.element('nx-list').innerHTML, /qa-ui-release/);
  assert.match(page.element('nx-list').innerHTML, /nx-repository-view-maven2-qa-ui-release-browse/);
  assert.match(page.element('nx-list').innerHTML, /다시 만들지 마세요/);
  page.context.nxLoad();
  await settle();
  assert.match(page.element('nx-list').innerHTML, /생성됐지만/);
  assert.equal(page.requests.filter(r => r.method === 'POST').length, 1);
  assert.equal(page.element('nx-new-name').value, '');
});

test('hidden creation notice remains above other repositories and clears once the same repository becomes visible', async () => {
  const page = screen();
  page.repositories([{ name: 'other', type: 'hosted', versionPolicy: 'RELEASE' }]);
  await createMaven(page, 'new-release');
  assert.match(page.element('nx-list').innerHTML, /다시 만들지 마세요/);
  assert.match(page.element('nx-list').innerHTML, /data-name="other"/);
  page.repositories([{ name: 'new-release', type: 'hosted', versionPolicy: 'RELEASE' }]);
  page.context.nxLoad();
  await settle();
  assert.doesNotMatch(page.element('nx-list').innerHTML, /생성됐지만|다시 만들지/);
  page.pick('new-release');
  page.context.nxApply();
  assert.equal(page.element('repo-input').value, 'new-release');
});

test('created name is escaped in the visible guidance', async () => {
  const page = screen();
  await createMaven(page, '<img src=x onerror=alert(1)>');
  assert.doesNotMatch(page.element('nx-list').innerHTML, /<img/);
  assert.match(page.element('nx-list').innerHTML, /&lt;img/);
});

test('post-create list failure keeps creation evidence and does not echo backend error payload', async () => {
  const page = screen();
  page.context.fetch = async (url, options) => ({ json: async () => options?.method === 'POST'
    ? { success: true, data: {} }
    : { success: false, message: 'SECRET_SENTINEL' } });
  await createMaven(page, 'created-release');
  assert.match(page.element('nx-list').innerHTML, /created-release.*목록 조회에 실패/);
  assert.doesNotMatch(page.element('nx-list').innerHTML, /SECRET_SENTINEL/);
});

test('late successful create cannot reset another picker or disclose the old repository there', async () => {
  const page = screen();
  let resolvePost;
  page.context.fetch = (url, options) => options?.method === 'POST'
    ? new Promise(resolve => { resolvePost = resolve; })
    : Promise.resolve({ json: async () => ({ success: true, data: { repositories: [] } }) });
  page.context.nxOpen('repo-input', 'maven2', 'name');
  await settle();
  page.element('nx-new-name').value = 'old-release';
  page.context.nxCreate(); page.confirmations[0].confirm();
  page.context.nxOpen('new-input', 'docker', 'name');
  await settle();
  resolvePost({ json: async () => ({ success: true, data: {} }) });
  await settle();
  assert.doesNotMatch(page.element('nx-list').innerHTML, /old-release/);
  assert.equal(page.context._nxTarget.format, 'docker');
});

test('image selection requires an actual repository and uses its reachable endpoint', async () => {
  const page = screen();
  page.repositories([{ name: 'docker-payments', type: 'hosted', registry: 'payments:5001', endpointReachable: true }]);
  page.context.nxOpen('image-input', 'docker', 'image', 'payments');
  await settle();
  assert.match(page.element('nx-lead').textContent, /실제 인증·업로드 권한은 이미지 push 시 확인됩니다/);
  page.context.nxApply();
  assert.equal(page.element('image-input').value, '');
  assert.match(page.notices.at(-1)[0], /저장소를 고르세요/);
  page.pick('docker-payments');
  page.element('nx-image-path').value = 'team/payments';
  page.context.nxApply();
  assert.equal(page.element('image-input').value, 'payments:5001/team/payments');
  assert.equal(page.element('nx-registry').textContent, 'payments:5001');
});

test('unreachable connector and group cannot be selected by directly invoking the handler', async () => {
  const page = screen();
  page.repositories([
    { name: 'unknown', type: 'hosted', registry: 'unknown:5000' },
    { name: 'group', type: 'group', registry: 'group:5000', endpointReachable: true }
  ]);
  page.context.nxOpen('image-input', 'docker', 'image', 'payments');
  await settle();
  for (const name of ['unknown', 'group']) {
    page.pick(name);
    page.context.nxApply();
    assert.equal(page.element('image-input').value, '');
  }
  assert.equal((page.element('nx-list').innerHTML.match(/ disabled /g) || []).length, 2);
});

test('snapshot field excludes RELEASE and creates with SNAPSHOT in the actual POST body', async () => {
  const page = screen();
  page.repositories([
    { name: 'releases', type: 'hosted', versionPolicy: 'RELEASE' },
    { name: 'snapshots', type: 'hosted', versionPolicy: 'SNAPSHOT' }
  ]);
  page.context.nxOpen('snapshot-input', 'maven2', 'name', null, 'SNAPSHOT');
  await settle();
  assert.equal(page.element('nx-new-policy').value, 'SNAPSHOT');
  assert.equal(page.element('nx-new-policy').disabled, true);
  page.pick('releases');
  page.context.nxApply();
  assert.equal(page.element('snapshot-input').value, '');
  page.pick('snapshots');
  page.context.nxApply();
  assert.equal(page.element('snapshot-input').value, 'snapshots');
  page.element('nx-new-name').value = 'new-snapshots';
  page.context.nxCreate();
  assert.match(page.confirmations[0].message, /SNAPSHOT/);
  page.confirmations[0].confirm();
  await settle();
  assert.deepEqual(page.requests.find(request => request.method === 'POST').body, {
    format: 'maven2', name: 'new-snapshots', versionPolicy: 'SNAPSHOT'
  });
});

test('reloading invalidates the prior repository choice immediately', async () => {
  const page = screen();
  page.repositories([{ name: 'images', type: 'hosted', registry: 'registry:5000', endpointReachable: true }]);
  page.context.nxOpen('image-input', 'docker', 'image', 'payments');
  await settle();
  page.pick('images');
  page.context.nxLoad();
  page.context.nxApply();
  assert.equal(page.element('image-input').value, '');
  await settle();
});

test('late response from the previous picker cannot populate a newly opened picker', async () => {
  const page = screen();
  const pending = [];
  page.context.fetch = () => new Promise(resolve => pending.push(resolve));
  page.context.nxOpen('image-input', 'docker', 'image', 'payments');
  page.context.nxOpen('snapshot-input', 'maven2', 'name', null, 'SNAPSHOT');
  pending[1]({ json: async () => ({ success: true, data: { repositories: [
    { name: 'snapshots', type: 'hosted', versionPolicy: 'SNAPSHOT' }
  ] } }) });
  await settle();
  pending[0]({ json: async () => ({ success: true, data: { repositories: [
    { name: 'stale-docker', type: 'hosted', registry: 'old:5000', endpointReachable: true }
  ] } }) });
  await settle();
  assert.match(page.element('nx-list').innerHTML, /snapshots/);
  assert.doesNotMatch(page.element('nx-list').innerHTML, /stale-docker/);
});

test('standalone management lists and selects without project or GitLab requests and restores project copy', async () => {
  const page = screen();
  page.repositories([{ name: 'snapshots', type: 'hosted', versionPolicy: 'SNAPSHOT' }]);
  page.context.nxOpen('nxop-selected-repository', 'maven2', 'manage');
  await settle();
  assert.equal(page.element('modal-nexus-title').textContent, 'Nexus 저장소 조회·생성');
  assert.equal(page.element('nx-apply').textContent, '선택한 이름 표시');
  assert.equal(page.element('nx-new-policy').disabled, false);
  assert.doesNotMatch(page.element('nx-lead').textContent, /이 프로젝트/);
  page.pick('snapshots');
  page.context.nxApply();
  assert.equal(page.element('nxop-selected-repository').value, 'snapshots');
  assert.equal(page.notices.at(-1)[0], '선택한 저장소 이름을 표시했습니다.');
  assert.deepEqual(page.requests.map(r => r.url), ['/api/v1/nexus/repositories?format=maven2']);
  page.context.nxOpen('repo-input', 'maven2', 'name');
  await settle();
  assert.equal(page.element('nx-apply').textContent, '이 값으로 넣기');
  assert.match(page.element('nx-lead').textContent, /이 프로젝트/);
  page.pick('snapshots');
  page.context.nxApply();
  assert.match(page.notices.at(-1)[0], /저장을 눌러야/);
});

test('standalone management sends the selected Maven policy and Docker port through the existing API', async () => {
  const page = screen();
  page.context.nxOpen('nxop-selected-repository', 'maven2', 'manage');
  await settle();
  page.element('nx-new-name').value = 'standalone-snapshots';
  page.element('nx-new-policy').value = 'SNAPSHOT';
  page.context.nxCreate();
  page.confirmations.at(-1).confirm();
  await settle();
  assert.deepEqual(page.requests.find(r => r.method === 'POST').body, {
    format: 'maven2', name: 'standalone-snapshots', versionPolicy: 'SNAPSHOT'
  });
  page.context.nxOpen('nxop-selected-repository', 'docker', 'manage');
  await settle();
  assert.equal(page.element('nx-new-policy-wrap').style.display, 'none');
  assert.equal(page.element('nx-new-port-wrap').style.display, '');
  page.element('nx-new-name').value = 'standalone-docker';
  page.element('nx-new-port').value = '5000';
  page.context.nxCreate();
  page.confirmations.at(-1).confirm();
  await settle();
  assert.deepEqual(page.requests.filter(r => r.method === 'POST').at(-1).body, {
    format: 'docker', name: 'standalone-docker', dockerHttpPort: 5000
  });
});
