const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const template = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/server-map.html'), 'utf8');
const start = template.indexOf('  function smTopBanner()');
const end = template.indexOf('  /* ── 연결 상태', start);
const bannerScript = template.slice(start, end);
const iconStart = template.indexOf('  var SM_ICON = {');
const iconEnd = template.indexOf('  function smBanner(', iconStart);
const iconScript = template.slice(iconStart, iconEnd);

function bannerFor(health) {
  const notices = [];
  const context = vm.createContext({
    smCards: [{
      arch: { collisions: [] },
      health,
      svc: [{ serverName: 'uiqa-rockywas1', running: true, exists: true }]
    }],
    smBanner: (...args) => notices.push(args),
    smEsc: value => String(value)
  });
  vm.runInContext(bannerScript, context);
  context.smTopBanner();
  return notices[0];
}

const healthyDependencies = () => ({
  gitlab: { state: 'OK' },
  nexus: { state: 'OK' },
  registry: { state: 'OK' },
  servers: [{ state: 'OK' }]
});

test('registry permission warning cannot be hidden by a running TEST container', () => {
  const notice = bannerFor({
    gitlab: { state: 'OK' },
    nexus: { state: 'OK' },
    registry: { state: 'WARN', detail: 'Push and pull permissions need verification' },
    servers: [{ state: 'OK' }]
  });

  assert.equal(notice[0], 'warn');
  assert.equal(notice[1], '추가 확인이 필요한 구간이 있습니다');
  assert.match(notice[2], /이미지 레지스트리/);
  assert.doesNotMatch(notice[1], /모든 서비스가 정상/);
});

test('health banner script avoids Thymeleaf inline-expression delimiters', () => {
  assert.doesNotMatch(bannerScript, /\[\[/);
});

test('server-map warning banner has an icon resource', () => {
  assert.match(iconScript, /warn:\s*'<svg/);
});

test('broken registry remains a blocking banner even while services run', () => {
  const notice = bannerFor({
    gitlab: { state: 'OK' },
    nexus: { state: 'OK' },
    registry: { state: 'BROKEN' },
    servers: [{ state: 'OK' }]
  });

  assert.equal(notice[0], 'danger');
  assert.match(notice[2], /이미지 레지스트리/);
});

for (const dependency of ['gitlab', 'nexus']) {
  test(`${dependency} warning cannot be hidden by a running TEST container`, () => {
    const health = healthyDependencies();
    health[dependency] = { state: 'WARN' };

    const notice = bannerFor(health);

    assert.equal(notice[0], 'warn');
    assert.match(notice[2], new RegExp(dependency === 'gitlab' ? 'GitLab' : 'Nexus'));
  });

  test(`${dependency} outage remains a blocking banner while services run`, () => {
    const health = healthyDependencies();
    health[dependency] = { state: 'BROKEN' };

    const notice = bannerFor(health);

    assert.equal(notice[0], 'danger');
    assert.match(notice[2], new RegExp(dependency === 'gitlab' ? 'GitLab' : 'Nexus'));
  });
}

test('an unconfigured integration is reported as needing confirmation', () => {
  const health = healthyDependencies();
  health.gitlab = { state: 'UNCONFIGURED' };

  const notice = bannerFor(health);

  assert.equal(notice[0], 'warn');
  assert.match(notice[2], /GitLab/);
});

test('a missing health response cannot produce an all-services-healthy banner', () => {
  const notice = bannerFor(undefined);

  assert.equal(notice[0], 'warn');
  assert.match(notice[2], /연결 상태 확인/);
});

test('a partial health response calls out integrations that were not checked', () => {
  const notice = bannerFor({});

  assert.equal(notice[0], 'warn');
  assert.match(notice[2], /GitLab/);
  assert.match(notice[2], /Nexus/);
  assert.match(notice[2], /이미지 레지스트리/);
});
