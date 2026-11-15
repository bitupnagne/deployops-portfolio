const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.resolve(__dirname, '../../main/resources');
const html = readFileSync(path.join(resources, 'templates/pages/integration.html'), 'utf8');
const script = html.slice(html.indexOf('  <script>') + '  <script>'.length, html.lastIndexOf('  </script>'));
const select = readFileSync(path.join(resources, 'static/js/select.js'), 'utf8');

function page(enhanced = true) {
  const elements = new Map(), requests = [];
  const field = (id) => {
    if (id === 'nexus-cleanup-body') return null;
    if (!elements.has(id)) elements.set(id, { id, isConnected: true, tagName: 'INPUT', value: '', textContent: '',
      checked: false, style: {}, className: '', addEventListener() {} });
    return elements.get(id);
  };
  const makeSelect = (id, pairs, initial) => {
    const options = pairs.map(([value, textContent]) => ({ value, textContent }));
    let index = options.findIndex(option => option.value === initial);
    const native = { tagName: 'SELECT', options,
      get value() { return options[index]?.value ?? ''; },
      set value(value) { index = options.findIndex(option => option.value === String(value)); },
      get selectedOptions() { return index < 0 ? [] : [options[index]]; },
      _cs: enhanced ? { txt: { textContent: options[index]?.textContent ?? '' },
        wrap: { classList: { toggle() {} } } } : undefined };
    if (native._cs) native._cs.sel = native;
    elements.set(id, native);
    return native;
  };
  const transfer = makeSelect('deploy-image-transfer', [['push', 'push (docker save + SSH 전송)'],
    ['registry', 'registry (Registry에서 pull)']], 'push');
  const apiVersion = makeSelect('gitlab-api-version', [['v4', 'v4']], 'v4');
  let resolveSettings;
  const pending = new Promise(resolve => { resolveSettings = resolve; });
  const context = vm.createContext({
    window: { addEventListener() {} },
    document: { readyState: 'loading', addEventListener() {}, getElementById: field,
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf' }) },
    MutationObserver: class { observe() {} }, showToast() {},
    fetch: (url, options) => {
      requests.push({ url, options });
      if (url === '/api/v1/settings/integration' && !options) return pending;
      return Promise.resolve({ json: async () => ({ success: true, data: {} }) });
    }
  });
  vm.runInContext(select, context);
  vm.runInContext(script, context);
  return { context, field, transfer, apiVersion, requests,
    async hydrate(settings) { resolveSettings({ json: async () => ({ success: true, data: settings }) });
      await new Promise(resolve => setImmediate(resolve)); } };
}

test('actual delayed settings response updates native registry and existing custom label without save/change', async () => {
  const s = page();
  assert.equal(s.transfer.value, 'push');
  assert.match(s.transfer._cs.txt.textContent, /^push/);
  await s.hydrate({ 'deploy.image.transfer': 'registry', 'gitlab.api-version': 'v4' });
  assert.equal(s.transfer.value, 'registry');
  assert.equal(s.transfer._cs.txt.textContent, 'registry (Registry에서 pull)');
  assert.equal(s.apiVersion._cs.txt.textContent, 'v4');
  assert.equal(s.requests.filter(request => request.options?.method === 'POST').length, 0);
});

test('push and omitted default both display exactly their native selected option', async () => {
  const push = page();
  await push.hydrate({ 'deploy.image.transfer': 'push' });
  assert.equal(push.transfer._cs.txt.textContent, push.transfer.selectedOptions[0].textContent);
  const omitted = page();
  await omitted.hydrate({});
  assert.equal(omitted.transfer.value, 'registry');
  assert.equal(omitted.transfer._cs.txt.textContent, omitted.transfer.selectedOptions[0].textContent);
});

test('load before select enhancement keeps native value and supports later actual widget sync', async () => {
  const s = page(false);
  await s.hydrate({ 'deploy.image.transfer': 'registry', 'gitlab.url': 'https://gitlab.example' });
  assert.equal(s.transfer.value, 'registry');
  s.transfer._cs = { sel: s.transfer, txt: { textContent: '' }, wrap: { classList: { toggle() {} } } };
  assert.equal(s.context.window.DeploySelect.sync(s.transfer), true);
  assert.match(s.transfer._cs.txt.textContent, /^registry/);
  assert.equal(s.field('gitlab-url').value, 'https://gitlab.example');
});

test('hydrate never fills stored secret inputs and unknown option does not retain stale push label', async () => {
  const s = page();
  await s.hydrate({ 'deploy.image.transfer': 'unsupported', 'gitlab.token': 'private-fixture',
    configuredSecrets: { 'gitlab.token': true } });
  assert.equal(s.transfer.value, '');
  assert.equal(s.transfer._cs.txt.textContent, '');
  assert.equal(s.field('gitlab-token').value, '');
  assert.match(s.field('gitlab-token-configured').textContent, /등록됨/);
});
