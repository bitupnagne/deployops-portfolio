const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../../../main/resources/templates/pages/nginx-policies.html'), 'utf8');

// Current registered-WAS controls, with browser-style disabled field omission.
function harness(type = 'REVERSE_PROXY', checked = false) {
  const nodes = Object.fromEntries(['ng-policy-type', 'ng-api-proxy', 'ng-upstream-host',
    'ng-upstream-port', 'ng-web-root', 'ng-api-prefix', 'ng-api-host', 'ng-api-port']
    .map(id => [id, { style: {}, value: '', checked: false, disabled: false }]));
  nodes['ng-policy-type'].value = type;
  nodes['ng-api-proxy'].checked = checked;
  nodes['ng-server'] = { selectedOptions: [{ dataset: { environment: 'TEST' } }] };
  const root = { children: [], appendChild(row) { row.parentNode = this; this.children.push(row); } };
  function row() {
    const server = { value: '', options: [{ value: '' }, ...['2', '3'].map(value => ({ value, dataset: { environment: 'TEST' } }))] };
    Object.defineProperty(server, 'selectedOptions', { get: () => server.options.filter(option => option.value === server.value) });
    return { server, port: { value: '8080' }, querySelector(selector) { return selector.includes('server') ? this.server : this.port; },
      remove() { root.children.splice(root.children.indexOf(this), 1); } };
  }
  nodes['ng-upstream-rows'] = root;
  nodes['ng-upstream-row-template'] = { content: { cloneNode: row } };
  const groups = Object.fromEntries(['.ng-proxy-field', '.ng-static-field', '.ng-api-field',
    '.ng-proxy-options', '.ng-upstream-choice', '.ng-legacy-upstream'].map(key => [key, [{ style: {} }]]));
  const elements = { policyType: nodes['ng-policy-type'], apiProxyEnabled: nodes['ng-api-proxy'],
    upstreamHost: nodes['ng-upstream-host'], apiUpstreamHost: nodes['ng-api-host'],
    secondaryUpstreamHost: { value: 'obsolete-host' } };
  for (const key of ['websocketEnabled', 'proxyBuffering', 'securityHeaders', 'tlsEnabled', 'tls12', 'tls13', 'http2Enabled', 'redirectHttp', 'hstsEnabled'])
    elements[key] = { checked: false };
  const form = { elements }, sent = [];
  const context = vm.createContext({
    document: { getElementById: id => nodes[id], querySelectorAll: selector => selector === '#ng-upstream-rows .ng-upstream-row' ? root.children : groups[selector] || [] },
    FormData: class {
      constructor(source) {
        this.data = {};
        for (const [name, control] of Object.entries(source.elements)) if (!control.disabled) this.data[name] = control.value;
        for (const item of root.children) for (const control of [item.server, item.port]) if (!control.disabled) this.data[control.name] = control.value;
      }
      set(key, value) { this.data[key] = value; }
      delete(key) { delete this.data[key]; }
    },
    fetch(url, options) { sent.push(options.body.data); return Promise.resolve({}); },
    nginxCsrfHeaders: () => ({}), nginxResult: () => ({ message: 'ok' }), showToast() {}, reloadNginxWorkspace() {}
  });
  vm.runInContext(html.slice(html.indexOf('function toggleTlsFields()'), html.indexOf('function approveNginx(')), context);
  context.addNginxUpstream();
  root.children[0].server.value = '2';
  return { nodes, groups, root, elements, form, context, ctx: context, sent,
    submit() { context.submitNginxPolicy({ preventDefault() {}, currentTarget: form }); } };
}
module.exports = { harness, html };
