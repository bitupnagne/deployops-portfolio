const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const read = file => readFileSync(path.resolve(__dirname, '../../main/resources/' + file), 'utf8');
const detail = read('templates/pages/project-detail.html');
const serviceScript = detail.slice(detail.indexOf('  var serviceStatusLoaded ='), detail.indexOf('  var _pbPath ='));
const topologyScript = read('static/js/topology.js');
const mapTemplate = read('templates/pages/server-map.html');
const showLogScript = mapTemplate.slice(mapTemplate.indexOf('  function smShowLog('), mapTemplate.indexOf('  function smTailStart('));
const staticRow = {
  serverId: 21, serverName: 'devweb', environment: 'TEST', artifactType: 'STATIC_BUNDLE',
  serviceTarget: '/var/www/payments', exists: true, running: true, statusKnown: true,
  statusLabel: '릴리스 확인', controlSupported: false, logsSupported: false,
  controlReason: '정적 파일은 기동·정지 대상이 아닙니다.'
};
const settle = () => new Promise(resolve => setImmediate(resolve));

function page(rows = [staticRow]) {
  const elements = new Map();
  const calls = [];
  const notices = [];
  const buttonNodes = new Map();
  const querySelectorAll = selector => {
    assert.equal(selector, '#svc-list [data-svc-control]');
    const html = element('svc-list').innerHTML;
    return [...html.matchAll(/<button\b[^>]*\bdata-svc-control(?:\s|=|>)[^>]*>/g)].map((match, index) => {
      const key = html + ':' + index;
      if (!buttonNodes.has(key)) buttonNodes.set(key, {
        disabled: /\sdisabled(?:\s|=|>)/.test(match[0]),
        hasAttribute: name => name === 'data-svc-control'
      });
      return buttonNodes.get(key);
    });
  };
  const element = id => {
    if (!elements.has(id)) elements.set(id, { dataset: { id: '7' }, innerHTML: '', textContent: '', value: '', style: {}, querySelector: () => ({ textContent: 'devweb' }) });
    return elements.get(id);
  };
  const context = vm.createContext({
    document: { getElementById: element, querySelectorAll },
    escHtml: v => String(v ?? ''),
    showToast: text => notices.push(text),
    openConfirm: () => calls.push('confirm'),
    fetch: async (url, options) => { calls.push({ url, options }); return { json: async () => ({ success: true, data: rows }) }; }
  });
  vm.runInContext(serviceScript, context);
  return { context, element, calls, notices, querySelectorAll };
}

test('project service tab shows static release and WebRoot without container control buttons', async () => {
  const screen = page();
  screen.context.loadServiceStatus(true);
  await settle();
  const html = screen.element('svc-list').innerHTML;
  assert.match(html, /릴리스 확인/);
  assert.match(html, /\/var\/www\/payments/);
  assert.match(html, /기동·정지 대상이 아닙니다/);
  assert.doesNotMatch(html, /onclick="svcControl/);
  screen.context.svcControl(21, 'stop', 'devweb');
  assert.equal(screen.calls.length, 1, 'Direct handler call must not POST or confirm a static stop');
});

test('unknown state stays distinct from undeployed and cannot be controlled', async () => {
  const screen = page([{ ...staticRow, artifactType: 'CONTAINER', controlSupported: true, statusKnown: false,
    statusLabel: '확인 불가', exists: null, running: null }]);
  screen.context.loadServiceStatus(true);
  await settle();
  assert.match(screen.element('svc-list').innerHTML, /확인 불가/);
  assert.doesNotMatch(screen.element('svc-list').innerHTML, /미배포|onclick="svcControl/);
});

test('pending selector follows rendered mixed static and container controls and unlocks them', async () => {
  const screen = page([staticRow, { ...staticRow, serverId: 22, serverName: 'devwas',
    artifactType: 'CONTAINER', controlSupported: true, statusKnown: true }]);
  screen.context.loadServiceStatus(true);
  await settle();
  const controls = screen.querySelectorAll('#svc-list [data-svc-control]');
  assert.equal(controls.length, 2, 'Only the running container renders restart and stop controls');
  assert.ok(controls.every(button => button.hasAttribute('data-svc-control') && !button.disabled));
  screen.context.serviceControlPending = { serverName: 'devwas', action: 'restart' };
  screen.context.syncServiceControlPending();
  assert.ok(controls.every(button => button.disabled));
  assert.equal(screen.element('svc-control-progress').hidden, false);
  screen.context.serviceControlPending = null;
  screen.context.syncServiceControlPending();
  assert.ok(controls.every(button => !button.disabled));
  assert.equal(screen.element('svc-control-progress').hidden, true);
  assert.equal(screen.calls.length, 1, 'Selector/pending synchronization does not submit service controls');
});

test('topology disables all static control buttons while retaining deployment history', () => {
  const window = {};
  vm.runInNewContext(topologyScript, { window });
  const host = { innerHTML: '' };
  window.DeployTopology.render(host, { environments: [{ environment: 'TEST', groups: [{ groupName: 'web', servers: [
    { serverId: 21, serverName: 'devweb', serverType: 'WEB', ipAddress: '192.0.2.21' }
  ] }] }] }, {}, [staticRow], { projectId: 7 });
  const controlButtons = host.innerHTML.match(/<button[^>]*onclick="smControl[^>]*>/g);
  assert.equal(controlButtons.length, 3);
  assert.ok(controlButtons.every(button => button.includes(' disabled')));
  assert.match(host.innerHTML, /릴리스 확인/);
  assert.match(host.innerHTML, /smShowLog\(7,21\)/);
});

test('topology separates Nexus management TCP from EULA-blocked Docker Registry and identifies self', () => {
  const window = {};
  vm.runInNewContext(topologyScript, { window });
  const host = { innerHTML: '' };
  window.DeployTopology.render(host, {
    build: [
      { id: 'nexus', label: 'Nexus', role: '이미지 저장소', host: 'nexus.lab', port: 8081,
        registry: 'nexus.lab:5000', registryPort: 5000, configured: true },
      { id: 'deployops', label: '배포시스템', role: '이 시스템 — 승인 · 실행', host: '',
        configured: true, self: true }
    ],
    environments: []
  }, {
    nexus: { state: 'OK', detail: 'nexus.lab:8081 연결됨' },
    registry: { state: 'BROKEN', detail: 'EULA acceptance required' }
  }, []);

  assert.match(host.innerHTML, /Nexus 관리/);
  assert.match(host.innerHTML, /관리 포트 TCP: 정상/);
  assert.match(host.innerHTML, /Docker Registry 5000/);
  assert.match(host.innerHTML, /Docker Registry — 연결 끊김 — EULA acceptance required/);
  assert.match(host.innerHTML, /현재 DeployOps 응답 중/);
  assert.doesNotMatch(host.innerHTML, /배포시스템\(.*\) 주소 미설정/);
});

test('static log modal loads deployment history and never starts container polling', async () => {
  const screen = page();
  Object.assign(screen.context, {
    smTargetExists: (pid, sid) => pid === 7 && sid === 21, smCard: () => ({ svc: [staticRow] }),
    smTailStop() {}, smSetNewBadge() {}, smRenderDeployLog() {},
    smTailStart: () => screen.calls.push('container-poll')
  });
  vm.runInContext(showLogScript, screen.context);
  screen.context.smShowLog(7, 21);
  await settle();
  assert.equal(screen.calls.length, 1);
  assert.match(screen.calls[0].url, /\/logs\?projectId=7/);
  assert.equal(screen.element('sm-tail-follow').disabled, true);
  assert.match(screen.element('sm-tail-out').textContent, /컨테이너 로그가 없습니다/);
});

test('edited page scripts and shared topology parse without a browser', () => {
  for (const [name, html] of [['project-detail', detail], ['server-map', mapTemplate]]) {
    let count = 0;
    for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) {
      if (match[1].trim()) { new vm.Script(match[1], { filename: name }); count++; }
    }
    assert.ok(count > 0);
  }
});
