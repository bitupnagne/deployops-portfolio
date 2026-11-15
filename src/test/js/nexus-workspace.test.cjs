const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const resources = path.resolve(__dirname, '../../main/resources');
const html = fs.readFileSync(path.join(resources, 'templates/pages/nexus-operations.html'), 'utf8');
const source = fs.readFileSync(path.join(resources, 'static/js/nexus-workspace.js'), 'utf8');
const bootstrap = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
const keys = ['repositories', 'dependencies', 'cleanup', 'backup', 'restore', 'promotion'];

class Element {
  constructor(attributes = {}, dataset = {}) {
    this.attributes = {...attributes}; this.dataset = {...dataset}; this.listeners = new Map();
    this.hidden = false; this.disabled = false; this.isConnected = true; this.children = [];
  }
  setAttribute(name, value) { this.attributes[name] = value; }
  getAttribute(name) { return this.attributes[name] ?? null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  addEventListener(name, listener) {
    if (!this.listeners.has(name)) this.listeners.set(name, new Set());
    this.listeners.get(name).add(listener);
  }
  removeEventListener(name, listener) { this.listeners.get(name)?.delete(listener); }
  dispatchEvent(event) { for (const listener of [...(this.listeners.get(event.type) || [])]) listener(event); }
  contains(element) { return element === this || this.children.some(child => child.contains(element)); }
  focus() { this.focused = true; }
  closest() { return this.parent || this; }
}

function fixture({available = keys, href = 'https://demo.test/nexus-operations', initialize = true, stale = false} = {}) {
  const root = new Element({'aria-busy': 'true'}, {workspaceState: 'loading'});
  const content = new Element(); content.children = [root]; content.deployPageNavigation = 2;
  const status = new Element(), message = new Element(), retry = new Element(); retry.hidden = true;
  const tabs = available.map(key => {
    const tab = new Element({'aria-controls': key === 'dependencies' ? 'nxdependency' : 'nxpanel-' + key}, {nxTab:key});
    tab.disabled = true; return tab;
  });
  const panels = available.map(key => { const panel = new Element({}, {nxPanel:key}); panel.hidden = true; return panel; });
  const go = new Element({}, {nxGo:'backup'});
  const currentScript = new Element(); currentScript.parent = root;
  const moduleScript = new Element({'data-nx-workspace-script': ''});
  root.children = [...tabs, ...panels, status, message, retry, currentScript, moduleScript];
  root.querySelectorAll = selector => ({'[data-nx-tab]':tabs,'[data-nx-panel]':panels,'[data-nx-go]':[go]})[selector] || [];
  root.querySelector = selector => ({'.nx-workspace-state':status,'[data-nx-workspace-message]':message,'[data-nx-workspace-retry]':retry})[selector];
  const window = new Element(), location = new URL(href), replacements = [];
  const document = {currentScript, querySelector:()=>root, getElementById:()=>content};
  const context = vm.createContext({window, document, location, URL, Event,
    history:{state:{},replaceState:(state, title, url)=>{replacements.push(url);location.href=url.href;}}});
  vm.runInContext(bootstrap, context);
  currentScript.deployPageContent = content;
  currentScript.deployPageNavigation = stale ? 1 : 2;
  const run = () => vm.runInContext(source, context);
  if (initialize) run();
  return {root, status, message, retry, tabs, panels, window, moduleScript, go, location, replacements, run,
    visible:()=>panels.filter(panel=>!panel.hidden).map(panel=>panel.dataset.nxPanel)};
}

test('uninitialized server HTML hides every task panel and disables tabs before any script loads', () => {
  const panels = [...html.matchAll(/<div\b[^>]*\bdata-nx-panel="[^"]+"[^>]*>/g)].map(match=>match[0]);
  const tabs = [...html.matchAll(/<button\b[^>]*\bdata-nx-tab="[^"]+"[^>]*>/g)].map(match=>match[0]);
  assert.equal(panels.length, 6); assert.equal(tabs.length, 6);
  for (const panel of panels) assert.match(panel, /\shidden(?:\s|>)/);
  for (const tab of tabs) assert.match(tab, /\sdisabled(?:\s|>)/);
  assert.ok(/class="[^"]*\bnx-workspace-state\b[^"]*"[^>]*role="status"[^>]*aria-live="polite"/.test(html));
  const ui = fixture({initialize:false});
  assert.deepEqual(ui.visible(), []);
  assert.equal(ui.root.getAttribute('aria-busy'), 'true');
  assert.equal(ui.status.hidden, false);
});

test('ready shows only the first authorized panel and removes the loading state', () => {
  for (const available of [keys, keys.slice(1)]) {
    const ui = fixture({available});
    assert.deepEqual(ui.visible(), [available[0]]);
    assert.equal(ui.tabs.filter(tab=>tab.getAttribute('aria-selected') === 'true').length, 1);
    assert.equal(ui.tabs.every(tab=>!tab.disabled), true);
    assert.equal(ui.status.hidden, true);
    assert.equal(ui.root.getAttribute('aria-busy'), 'false');
    assert.equal(ui.window.listeners.get('error').size, 0);
  }
});

test('deep links and project dependency links retain their selected panel', () => {
  assert.deepEqual(fixture({href:'https://demo.test/nexus-operations#nxpanel-restore'}).visible(), ['restore']);
  assert.deepEqual(fixture({href:'https://demo.test/nexus-operations?dependencyProjectId=7'}).visible(), ['dependencies']);
  assert.deepEqual(fixture({href:'https://demo.test/nexus-operations?dependencyProjectId=7#nxpanel-backup'}).visible(), ['backup']);
  assert.deepEqual(fixture({available:keys.slice(1),href:'https://demo.test/nexus-operations#nxpanel-repositories'}).visible(), ['dependencies']);
});

test('click, keyboard, quick links and hash navigation never expose multiple panels', () => {
  const ui = fixture();
  ui.tabs[3].dispatchEvent({type:'click'}); assert.deepEqual(ui.visible(), ['backup']);
  let prevented = false;
  ui.tabs[3].dispatchEvent({type:'keydown',key:'ArrowRight',preventDefault:()=>{prevented=true;}});
  assert.equal(prevented, true); assert.deepEqual(ui.visible(), ['restore']); assert.equal(ui.tabs[4].focused, true);
  ui.go.dispatchEvent({type:'click'}); assert.deepEqual(ui.visible(), ['backup']); assert.equal(ui.panels[3].focused, true);
  ui.location.hash = '#nxpanel-promotion'; ui.window.dispatchEvent({type:'hashchange'});
  assert.deepEqual(ui.visible(), ['promotion']);
  ui.window.dispatchEvent({type:'deploy:page-leave'});
  assert.equal(ui.window.listeners.get('hashchange').size, 0);
});

test('failed module keeps every legacy panel hidden and offers a retry', () => {
  const ui = fixture({initialize:false});
  ui.window.dispatchEvent({type:'error',target:ui.window}); // Unrelated runtime errors are not resource failures.
  assert.equal(ui.root.dataset.workspaceState, 'loading');
  ui.window.dispatchEvent({type:'error',target:ui.moduleScript});
  ui.run(); // Browsers still execute subsequent defer scripts after a resource error.
  assert.deepEqual(ui.visible(), []);
  assert.equal(ui.tabs.every(tab=>tab.disabled), true);
  assert.equal(ui.retry.hidden, false);
  assert.match(ui.message.textContent, /다시 불러오세요/);
  assert.equal(ui.root.getAttribute('aria-busy'), 'false');
  assert.equal(ui.window.listeners.get('error').size, 0);
});

test('abandoned SPA navigation cannot reveal a replacement page or retain error listeners', () => {
  const ui = fixture({stale:true});
  assert.deepEqual(ui.visible(), []);
  assert.equal(ui.root.dataset.workspaceBound, undefined);
  ui.window.dispatchEvent({type:'deploy:page-leave'});
  assert.equal(ui.window.listeners.get('error').size, 0);
});

test('no authorized tasks produces a completed empty state', () => {
  const ui = fixture({available:[]});
  assert.equal(ui.root.dataset.workspaceState, 'empty');
  assert.equal(ui.root.getAttribute('aria-busy'), 'false');
  assert.match(ui.message.textContent, /사용할 수 있는/);
  assert.equal(ui.window.listeners.get('error').size, 0);
});
