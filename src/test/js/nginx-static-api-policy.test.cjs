const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./helpers/nginx-policy-fixture.cjs');

test('API route starts off and prefix plus registered WAS are required only for enabled static policies', () => {
  for (const type of ['STATIC_SPA', 'STATIC_FILES']) {
    const h = harness(type, false); h.ctx.toggleNginxPolicyType();
    assert.equal(h.nodes['ng-api-prefix'].disabled, true);
    assert.equal(h.groups['.ng-api-field'][0].style.display, 'none');
    assert.equal(h.root.children[0].server.disabled, true);
    h.nodes['ng-api-proxy'].checked = true; h.ctx.toggleNginxPolicyType();
    for (const control of [h.nodes['ng-api-prefix'], h.root.children[0].server, h.root.children[0].port]) {
      assert.equal(control.disabled, false); assert.equal(control.required, true);
    }
    for (const id of ['ng-api-host', 'ng-api-port']) assert.equal(h.nodes[id].disabled, true);
    assert.equal(h.groups['.ng-proxy-options'][0].style.display, '');
  }
});

test('switching to reverse policy excludes retained API input and submits explicit false', () => {
  const h = harness('STATIC_SPA', true); h.ctx.toggleNginxPolicyType();
  h.nodes['ng-api-host'].value = 'obsolete-api-host';
  h.nodes['ng-policy-type'].value = 'REVERSE_PROXY'; h.ctx.toggleNginxPolicyType(); h.submit();
  assert.equal(h.nodes['ng-api-proxy'].disabled, true);
  assert.equal(h.nodes['ng-api-host'].disabled, true);
  assert.equal(h.sent[0].apiProxyEnabled, 'false');
  assert.equal(h.sent[0].apiUpstreamHost, undefined);
  assert.equal(h.sent[0]['upstreams[0].serverId'], '2');
});

test('static SPA and files API pairs submit registered WAS; API off excludes retained row values', () => {
  for (const type of ['STATIC_SPA', 'STATIC_FILES']) {
    const h = harness(type, true); h.ctx.addNginxUpstream(); h.root.children[1].server.value = '3';
    h.root.children[1].port.value = '18080'; h.ctx.toggleNginxPolicyType(); h.submit();
    assert.equal(h.groups['.ng-upstream-choice'][0].style.display, '');
    assert.equal(h.sent[0].apiProxyEnabled, 'true'); assert.equal(h.sent[0].upstreamListPresent, 'true');
    assert.equal(h.sent[0]['upstreams[1].serverId'], '3'); assert.equal(h.sent[0]['upstreams[1].port'], '18080');
    h.nodes['ng-api-proxy'].checked = false; h.ctx.toggleNginxPolicyType(); h.submit();
    assert.equal(h.groups['.ng-upstream-choice'][0].style.display, 'none');
    assert.equal(h.root.children[1].server.value, '3'); assert.equal(h.root.children[1].server.disabled, true);
    assert.equal(h.sent[1].apiProxyEnabled, 'false'); assert.equal(h.sent[1].upstreamListPresent, 'false');
    assert.equal(h.sent[1]['upstreams[0].serverId'], undefined); assert.equal(h.sent[1]['upstreams[1].port'], undefined);
    assert.equal(h.sent[1].secondaryUpstreamEnabled, undefined);
  }
});
