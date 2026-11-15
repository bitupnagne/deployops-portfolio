const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/ai-settings.html'), 'utf8');
const script = html.slice(html.indexOf('  <script>') + '  <script>'.length, html.lastIndexOf('  </script>'));

test('AI key has visible configured status and delete control', () => {
  assert.ok(html.includes('id="ai-apikey-configured"'));
  assert.ok(html.includes('onclick="clearAiKey()">삭제</button>'));
});

test('AI key status hydrates without a value; blank save retains and delete clears', async () => {
  const elements = new Map();
  const requests = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, { value: '', textContent: '', checked: false, style: {} });
    return elements.get(id);
  };
  const context = vm.createContext({
    document: {
      getElementById: element,
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf' })
    },
    localStorage: { removeItem() {} },
    showToast() {},
    fetch: async (url, options) => {
      requests.push({ url, body: options?.body ? JSON.parse(options.body) : null });
      return { json: async () => ({ success: true, data: options ? null : {
        'ai.api-key': 'unexpected-sensitive-value', configuredSecrets: { 'ai.api-key': true }
      } }) };
    }
  });
  vm.runInContext(script, context);
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(element('ai-apikey').value, '');
  assert.match(element('ai-apikey-configured').textContent, /등록됨/);

  context.saveAiSettings();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(Object.hasOwn(requests.at(-1).body, 'ai.api-key'), false);

  context.clearAiKey();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.at(-1).body['ai.api-key'], null);
  assert.match(element('ai-apikey-configured').textContent, /미등록/);
});
