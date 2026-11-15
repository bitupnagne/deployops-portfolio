const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const source = html.slice(html.indexOf('  function saveProject()'), html.indexOf('  function toggleBuildConfigFields()'));

function save(mode) {
  const elements = { 'project-data': { dataset: { id: '7' } }, 'proj-name': { value: 'fixture' } };
  if (mode !== undefined) elements['proj-build-config-mode'] = { value: mode };
  const calls = [];
  const context = vm.createContext({ document: { getElementById: id => elements[id], querySelector: () => ({ content: 'csrf' }) },
    showToast() {}, loadReadiness() {},
    fetch: async (url, options) => { calls.push({ url, body: JSON.parse(options.body) }); return { json: async () => ({ success: true }) }; }
  });
  vm.runInContext(source, context);
  context.saveProject();
  assert.equal(calls.length, 1);
  return calls[0].body;
}

for (const mode of ['IMPORT', 'AUTO', 'MANUAL']) {
  test(`actual project save preserves combined ${mode} mode without phantom axes`, () => {
    const body = save(mode);
    assert.equal(body.buildConfigMode, mode);
    for (const field of ['dockerMode', 'ciMode', 'dockerConfigMode', 'ciConfigMode']) assert.equal(Object.hasOwn(body, field), false);
  });
}
test('missing mode retains the existing AUTO default', () => assert.equal(save(undefined).buildConfigMode, 'AUTO'));
test('page offers one persisted mode and no unpersisted independent controls', () => {
  assert.equal((html.match(/id="proj-build-config-mode"/g) || []).length, 1);
  assert.doesNotMatch(html, /id="proj-(?:docker|ci)-config-mode"|Dockerfile과 CI는 서로 독립적으로 관리/);
  assert.match(html, /Dockerfile·CI 설정 모드/);
});
