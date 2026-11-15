const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const script = html.slice(html.indexOf('  function testGitlab()'), html.indexOf('  function saveProjectLogPaths()'));

function page() {
  const elements = new Map();
  const requests = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, { value: '', dataset: { id: '3' }, style: {}, textContent: '' });
    return elements.get(id);
  };
  const context = vm.createContext({
    Number,
    encodeURIComponent,
    document: {
      getElementById: element,
      querySelector: selector => ({ content: selector.includes('header') ? 'X-CSRF-TOKEN' : 'csrf' })
    },
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { json: async () => ({ success: true, data: {
        projectId: 7, pathWithNamespace: 'root/lab-static-service', repositoryUrlVerified: true
      } }) };
    }
  });
  vm.runInContext(script, context);
  return { context, element, requests };
}

test('project detail tests numeric project ID without sending repository URL as API base or a token', async () => {
  const screen = page();
  screen.element('proj-gitlab-id').value = '7';
  screen.element('proj-gitlab-url').value = 'http://gitlab.example/root/lab-static-service.git';

  await screen.context.testGitlab();

  assert.equal(screen.requests[0].url, '/api/v1/projects/3/gitlab/test');
  const body = JSON.parse(screen.requests[0].options.body);
  assert.deepEqual(body, {
    gitlabProjectId: 7,
    repositoryUrl: 'http://gitlab.example/root/lab-static-service.git'
  });
  assert.equal(screen.element('gitlab-test-result').textContent,
    '정상: root/lab-static-service (ID 7) 프로젝트 접근 및 저장소 주소 일치 확인 (clone 통신은 미검사)');
});

test('project detail requires a positive project ID before a network request', () => {
  const screen = page();
  screen.element('proj-gitlab-url').value = 'http://gitlab.example/root/lab-static-service.git';

  screen.context.testGitlab();

  assert.equal(screen.requests.length, 0);
  assert.equal(screen.element('gitlab-test-result').textContent, 'GitLab 프로젝트 ID를 입력해 주세요.');
});
