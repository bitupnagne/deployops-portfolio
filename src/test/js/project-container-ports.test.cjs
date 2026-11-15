const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const source = html.slice(html.indexOf('  function saveProject()'), html.indexOf('  function toggleBuildConfigFields()'));

function save(value, present = true) {
  const elements = { 'project-data': { dataset: { id: '7' } }, 'proj-name': { value: 'demo-app' } };
  if (present) elements['proj-container-ports'] = { value };
  const calls = [];
  const context = vm.createContext({ document: { getElementById: id => elements[id], querySelector: () => ({ content: 'csrf' }) },
    showToast() {}, loadReadiness() {},
    fetch: async (url, options) => { calls.push(JSON.parse(options.body)); return { json: async () => ({ success: true }) }; }
  });
  vm.runInContext(source, context);
  context.saveProject();
  return calls[0];
}

test('project save sends both TCP bindings and renders their persisted value', () => {
  assert.equal(save(' 9090:9090,9080:9080 ').containerPorts, '9090:9090,9080:9080');
  assert.match(html, /id="proj-container-ports"[^>]+th:value="\$\{project.containerPorts\}"/);
});
test('clearing the field is explicit while a missing field is omitted', () => {
  assert.equal(save('  ').containerPorts, null);
  assert.equal(Object.hasOwn(save('', false), 'containerPorts'), false);
});
