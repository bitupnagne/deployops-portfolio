const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/topology.js'), 'utf8');

function render(servers, health = [], services = []) {
  const window = {};
  vm.runInNewContext(source, { window });
  const host = {};
  window.DeployTopology.render(host, { environments: [{ environment: 'TEST', groups: [{ groupName: 'group', servers }] }] },
    { servers: health }, services, { projectId: 7 });
  return host.innerHTML;
}

test('agent topology never presents an unused SSH port or SSH deployment edge', () => {
  const html = render([{ serverId: 11, serverName: 'agent-was', controlMode: 'AGENT', sshPort: 2022 }],
    [{ serverId: 11, state: 'OK' }]);
  assert.match(html, /에이전트 배포/);
  assert.match(html, /agent-was .*에이전트/);
  assert.doesNotMatch(html, /SSH/);
  assert.match(html, /서비스 <b>확인 중<\/b>/);
});

test('legacy topology requires enrollment', () => {
  const html = render([{ serverId: 12, serverName: 'legacy', sshPort: 2022 }]);
  assert.match(html, /에이전트 전환 필요/);
  assert.doesNotMatch(html, /SSH 배포/);
  assert.doesNotMatch(html, /SSH 2022/);
});

test('mixed groups flag legacy registrations', () => {
  const html = render([{ serverId: 11, serverName: 'agent', controlMode: 'AGENT', sshPort: 2022 },
    { serverId: 12, serverName: 'legacy', controlMode: 'SSH', sshPort: 2202 }]);
  assert.match(html, /에이전트 전환 필요 서버 포함/);
  assert.doesNotMatch(html, /SSH 2202/);
  assert.doesNotMatch(html, /SSH 2022/);
});

test('agent transport labels do not manufacture service readiness or enable controls', () => {
  const html = render([{ serverId: 11, serverName: 'agent', controlMode: 'AGENT' }],
    [{ serverId: 11, state: 'BROKEN' }], [{ serverId: 11, statusKnown: false, statusLabel: '확인 불가' }]);
  assert.match(html, /연결 끊김/);
  assert.match(html, /확인 불가/);
  const controls = html.match(/<button[^>]*onclick="smControl[^>]*>/g);
  assert.equal(controls.length, 3);
  assert.ok(controls.every(button => button.includes(' disabled')));
});
