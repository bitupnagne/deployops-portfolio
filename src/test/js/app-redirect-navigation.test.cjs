const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const appScript = readFileSync(path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');
const navigateScript = appScript.slice(
  appScript.indexOf('async function navigate('),
  appScript.indexOf('/* 뒤로/앞으로')
);

const navigationPrelude = appScript.slice(appScript.indexOf('let currentPage ='), appScript.indexOf('let _loaderTimer ='));

function page(response) {
  const assigned = [];
  const history = [];
  const content = { innerHTML: 'current screen', scrollTop: 0, querySelectorAll: () => [] };
  const context = vm.createContext({
    URLSearchParams,
    Event,
    PAGE_META: { dashboard: { label: '대시보드' } },
    document: {
      querySelectorAll: () => [],
      querySelector: () => null,
      getElementById: id => id === 'content' ? content : null
    },
    fetch: async () => response,
    showPageLoader() {},
    makeClickablesFocusable() {},
    wireModalFocus() {},
    history: { pushState: (...args) => history.push(args) },
    location: { pathname: '/deploy/profile', search: '?pwchange=1' },
    window: { innerWidth: 1280, dispatchEvent() {}, location: { assign: url => assigned.push(url) } },
    console
  });
  vm.runInContext(`${navigationPrelude}\n${navigateScript}\nglobalThis.navigate = navigate;`, context);
  return { assigned, content, context, history };
}

test('SPA auth redirects navigate to the final server URL instead of rendering a mismatched fragment', async () => {
  let fragmentRead = false;
  const finalUrl = 'http://localhost:9090/deploy/profile?pwchange=1';
  const screen = page({
    redirected: true,
    url: finalUrl,
    ok: true,
    text: async () => { fragmentRead = true; return '<main>profile</main>'; }
  });

  await screen.context.navigate('dashboard');

  assert.deepEqual(screen.assigned, [finalUrl]);
  assert.equal(fragmentRead, false);
  assert.equal(screen.content.innerHTML, 'current screen');
  assert.equal(screen.history.length, 0);
});

test('ordinary SPA fragments still render and update their route', async () => {
  const screen = page({ redirected: false, ok: true, text: async () => '<main>dashboard</main>' });

  await screen.context.navigate('dashboard');

  assert.equal(screen.content.innerHTML, '<main>dashboard</main>');
  assert.equal(screen.history.length, 1);
  assert.deepEqual(screen.assigned, []);
});
