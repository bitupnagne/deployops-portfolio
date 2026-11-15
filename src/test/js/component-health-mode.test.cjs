const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const script = html.slice(html.indexOf('  function componentProjectId()'), html.indexOf('  function deleteComponent('));

function page() {
  const elements = new Map();
  const requests = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, { value: '', checked: false, style: {}, dataset: { id: '7' } });
    return elements.get(id);
  };
  const context = vm.createContext({
    document: { getElementById: element, querySelector: () => ({ content: 'csrf' }) },
    showToast() {}, navigate() {},
    fetch: async (url, options) => {
      requests.push({ url, ...options, body: JSON.parse(options.body) });
      return { json: async () => ({ success: true }) };
    }
  });
  vm.runInContext(script, context);
  return { context, element, requests };
}

test('new static component submits explicit local curl mode with its health target', () => {
  const screen = page();
  screen.context.openComponentModal();
  screen.element('cmp-code').value = 'web';
  screen.element('cmp-role').value = 'WEB';
  screen.element('cmp-type').value = 'STATIC_BUNDLE';
  screen.element('cmp-webroot').value = '/var/www/app';
  screen.element('cmp-hport').value = '80';
  screen.element('cmp-hpath').value = '/index.html';
  screen.context.saveComponent();
  assert.equal(screen.requests[0].method, 'POST');
  assert.equal(screen.requests[0].body.healthCheckMode, 'SSH_LOCAL_CURL');
  assert.equal(screen.requests[0].body.healthCheckPort, 80);
  assert.equal(screen.requests[0].body.healthCheckPath, '/index.html');
});

test('editing existing and unset modes does not silently replace their values', () => {
  for (const hmode of ['NONE', 'HTTP_DIRECT', 'SSH_LOCAL_CURL', 'CONTAINER_RUNNING', undefined]) {
    const screen = page();
    screen.context.openComponentModal({ dataset: { id: '21', code: 'web', type: 'STATIC_BUNDLE', webroot: '/var/www/app', hmode } });
    screen.context.saveComponent();
    assert.equal(screen.requests[0].method, 'PUT');
    assert.equal(screen.requests[0].body.healthCheckMode, hmode || null);
  }
});

test('operator can select local curl to repair an unset component', () => {
  const screen = page();
  screen.context.openComponentModal({ dataset: { id: '21', code: 'api', type: 'CONTAINER' } });
  screen.element('cmp-hmode').value = 'SSH_LOCAL_CURL';
  screen.context.saveComponent();
  assert.equal(screen.requests[0].body.healthCheckMode, 'SSH_LOCAL_CURL');
  assert.match(html, /th:data-hmode="\$\{c\.healthCheckMode\?\.name\(\)\}"/);
  assert.match(html, /<option value="HTTP_DIRECT" disabled>[^<]*실행 미지원/);
  assert.match(html, /<option value="CONTAINER_RUNNING">컨테이너 실행 상태/);
});

test('proxy HTTP Host survives edit and can be cleared or removed when switching artifact type', () => {
  const screen = page();
  screen.context.openComponentModal({ dataset: { id: '21', code: 'web', role: 'WEB', type: 'PROXY_RELOAD', hhost: 'portal.internal' } });
  assert.equal(screen.element('cmp-hhost').value, 'portal.internal');
  assert.equal(screen.element('cmp-hhost').disabled, false);
  screen.context.saveComponent();
  assert.equal(screen.requests[0].body.proxyHealthHost, 'portal.internal');
  screen.element('cmp-hhost').value = '';
  screen.context.saveComponent();
  assert.equal(screen.requests[1].body.proxyHealthHost, null);
  screen.element('cmp-hhost').value = 'portal.internal';
  screen.element('cmp-type').value = 'CONTAINER';
  screen.context.updateProxyHealthHostField();
  screen.context.saveComponent();
  assert.equal(screen.requests[2].body.proxyHealthHost, null);
  assert.equal(screen.element('cmp-hhost').value, '');
  assert.equal(screen.element('cmp-hhost').disabled, true);
});

test('proxy environment Host fields remain separate through edit, clear and artifact switch', () => {
  const screen = page();
  screen.context.openComponentModal({ dataset: { id: '21', code: 'web', role: 'WEB', type: 'PROXY_RELOAD', hhost: 'common.internal', hhostTest: 'test.internal', hhostProd: 'prod.internal' } });
  assert.equal(screen.element('cmp-hhost-test').value, 'test.internal');
  assert.equal(screen.element('cmp-hhost-prod').value, 'prod.internal');
  screen.context.saveComponent();
  assert.equal(screen.requests[0].body.proxyHealthHostTest, 'test.internal');
  assert.equal(screen.requests[0].body.proxyHealthHostProd, 'prod.internal');
  screen.element('cmp-hhost-test').value = '';
  screen.context.saveComponent();
  assert.equal(screen.requests[1].body.proxyHealthHostTest, null);
  assert.equal(screen.requests[1].body.proxyHealthHostProd, 'prod.internal');
  screen.element('cmp-type').value = 'CONTAINER';
  screen.context.updateProxyHealthHostField();
  screen.context.saveComponent();
  assert.equal(screen.requests[2].body.proxyHealthHostTest, null);
  assert.equal(screen.requests[2].body.proxyHealthHostProd, null);
  assert.equal(screen.element('cmp-hhost-prod').disabled, true);
});
