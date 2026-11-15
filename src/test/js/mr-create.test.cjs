const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const template = readFileSync(path.resolve(__dirname,
  '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const start = template.indexOf('  function createMergeRequestFromInputs(');
const end = template.indexOf('  /* ══ GitLab CI 파일 ══', start);
assert.ok(start >= 0 && end > start, 'MR creation functions must be present in the real template');
const mrScript = template.slice(start, end);

function screen({ source = '', target = 'main', title = '' } = {}) {
  const requests = [];
  const notices = [];
  const fields = {
    'project-data': { dataset: { id: '2' } },
    'mr-create-source-branch': { value: source },
    'mr-create-target-branch': { value: target },
    'mr-create-title': { value: title, focus() { this.focused = true; } },
    'proj-delete-source-branch': { value: 'false' }
  };
  const context = vm.createContext({
    document: {
      getElementById: id => fields[id],
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-token' })
    },
    showToast: (...args) => notices.push(args),
    fetch: async (url, options) => {
      requests.push({ url, ...options, body: JSON.parse(options.body) });
      return { json: async () => ({ success: true, message: 'MR created.' }) };
    },
    loadMergeRequests: () => {}
  });
  vm.runInContext(mrScript, context, { filename: 'project-detail-mr-create.js' });
  return { context, fields, requests, notices };
}

test('MR creation requires a title in the page form and never opens a browser prompt', () => {
  assert.match(template, /id="mr-create-title"[^>]*aria-label="MR 제목"/);
  assert.doesNotMatch(mrScript, /window\.prompt/);
  const page = screen({ source: 'codex/deployops-uiqa', title: '  ' });
  page.context.createMergeRequestFromInputs();
  assert.equal(page.requests.length, 0);
  assert.match(page.notices[0][0], /MR 제목을 입력/);
  assert.equal(page.fields['mr-create-title'].focused, true);
});

test('MR creation submits trimmed branches and title through the protected API route', async () => {
  const page = screen({
    source: ' codex/deployops-uiqa ', target: ' main ', title: '  데모 앱 CI UI QA  '
  });
  page.context.createMergeRequestFromInputs();
  await new Promise(setImmediate);
  assert.equal(page.requests.length, 1);
  const request = page.requests[0];
  assert.equal(request.url, '/api/v1/gitlab/deploy-projects/2/merge-requests');
  assert.equal(request.method, 'POST');
  assert.equal(request.headers['X-CSRF-TOKEN'], 'csrf-token');
  assert.equal(request.body.sourceBranch, 'codex/deployops-uiqa');
  assert.equal(request.body.targetBranch, 'main');
  assert.equal(request.body.title, '데모 앱 CI UI QA');
  assert.equal(request.body.removeSourceBranch, false);
  assert.equal(page.notices[0][0], 'MR created.');
});
