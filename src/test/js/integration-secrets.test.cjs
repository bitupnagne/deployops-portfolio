const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/integration.html'), 'utf8');
const script = html.slice(html.indexOf('  <script>') + '  <script>'.length, html.lastIndexOf('  </script>'));

for (const id of ['sso-shared-secret', 'gitlab-token', 'gitlab-webhook-secret', 'nexus-pass']) {
  test(`secret field ${id} has a configured status and explicit delete control`, () => {
    assert.ok(html.includes(`id="${id}-configured"`));
    assert.ok(html.includes(`'${id}')">삭제</button>`));
  });
}

function page(settings) {
  const elements = new Map();
  const requests = [];
  const element = id => {
    if (id === 'nexus-cleanup-body') return null;
    if (!elements.has(id)) elements.set(id, { id, isConnected: true, value: '', textContent: '', checked: false, style: {}, className: '', addEventListener() {} });
    return elements.get(id);
  };
  const context = vm.createContext({
    document: {
      getElementById: element,
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf' })
    },
    showToast() {},
    fetch: async (url, options) => {
      requests.push({ url, options, body: options?.body ? JSON.parse(options.body) : null });
      const data = url === '/api/v1/settings/integration' && !options ? settings : {};
      return { json: async () => ({ success: true, data }) };
    }
  });
  vm.runInContext(script, context);
  return { context, element, requests, ready: () => new Promise(resolve => setImmediate(resolve)) };
}

test('stored secrets hydrate only configured status and never password inputs', async () => {
  const screen = page({
    'gitlab.url': 'https://gitlab.example',
    'gitlab.token': 'unexpected-sensitive-value',
    configuredSecrets: { 'gitlab.token': true, 'nexus.pass': true }
  });
  await screen.ready();
  assert.equal(screen.element('gitlab-token').value, '');
  assert.equal(screen.element('nexus-pass').value, '');
  assert.match(screen.element('gitlab-token-configured').textContent, /등록됨/);
  assert.equal(screen.element('gitlab-url').value, 'https://gitlab.example');
});

test('blank save retains secrets, rotation clears input, and explicit delete sends null', async () => {
  const screen = page({ configuredSecrets: { 'gitlab.token': true } });
  await screen.ready();

  await screen.context.saveSection('gitlab');
  const blankSave = screen.requests.at(-1);
  assert.equal(blankSave.body['gitlab.token'], '');
  assert.equal(screen.element('gitlab-token').value, '');

  screen.element('gitlab-token').value = 'replacement-input';
  await screen.context.saveSection('gitlab');
  const rotated = screen.requests.at(-1);
  assert.equal(rotated.body['gitlab.token'], 'replacement-input');
  assert.equal(screen.element('gitlab-token').value, '');
  assert.match(screen.element('gitlab-token-configured').textContent, /등록됨/);

  await screen.context.clearSecret('gitlab.token', 'gitlab-token');
  const cleared = screen.requests.at(-1);
  assert.equal(cleared.body['gitlab.token'], null);
  assert.match(screen.element('gitlab-token-configured').textContent, /미등록/);
});
