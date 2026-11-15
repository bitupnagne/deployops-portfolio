const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const template = readFileSync(path.resolve(__dirname,
  '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const start = template.indexOf('  function renderMergeRequestReview(');
const end = template.indexOf('  function createMergeRequestFromInputs(', start);
assert.ok(start >= 0 && end > start, 'MR UI functions must be present in the real template');
const mrScript = template.slice(start, end);

function screen(overrides = {}) {
  const requests = [];
  const confirmations = [];
  const notices = [];
  const navigations = [];
  const detail = { innerHTML: '' };
  const scope = {value: 'ALL', selectedOptions: [{textContent: '환경 내 전체 매핑 대상'}]};
  const mr = {
    iid: 31, state: 'merged', sourceBranch: 'feature/payment', targetBranch: 'main',
    title: '결제 검증 수정', sha: 'a'.repeat(40), changes: [], ...overrides
  };
  const context = vm.createContext({
    selectedMergeRequestIid: 31,
    selectedMergeRequestInfo: mr,
    document: {
      getElementById: id => id === 'project-data' ? { dataset: { id: '7' } } : (id === 'mr-test-scope' ? scope : detail),
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-token' })
    },
    escHtml: value => String(value ?? '').replace(/[&<>"']/g, character => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[character]),
    showToast: (...args) => notices.push(args),
    navigate: (...args) => navigations.push(args),
    openConfirm: (title, message, confirm) => confirmations.push({ title, message, confirm }),
    fetch: async (url, options) => {
      requests.push({ url, ...options, body: JSON.parse(options.body) });
      return { json: async () => ({ success: true, data: { id: 83 } }) };
    }
  });
  vm.runInContext(mrScript, context, { filename: 'project-detail-mr.js' });
  return { context, mr, requests, confirmations, notices, navigations, detail };
}

for (const state of ['opened', 'closed', undefined]) {
  test(`${state ?? 'unknown'} MR cannot submit a target-branch TEST request`, () => {
    const page = screen({ state });
    page.context.renderMergeRequestReview(page.mr, []);
    const button = page.detail.innerHTML.match(/<button[^>]*onclick="createTestDeployRequestFromSelectedMr\(\)"[^>]*>/)?.[0];
    assert.ok(button?.includes('disabled'), 'The unsafe shortcut must be disabled');
    page.context.createTestDeployRequestFromSelectedMr();
    assert.equal(page.requests.length, 0, 'Calling the handler directly must also be blocked');
    assert.equal(page.confirmations.length, 0);
    assert.match(page.notices[0][0], /병합이 완료/);
  });
}

test('merged MR with no target branch never falls back to its source branch', () => {
  const page = screen({ targetBranch: '  ' });
  page.context.createTestDeployRequestFromSelectedMr();
  assert.equal(page.requests.length, 0);
  assert.equal(page.confirmations.length, 0);
  assert.match(page.notices[0][0], /병합 대상 브랜치/);
});

test('out-of-order MR detail response cannot deploy another selected MR', () => {
  const page = screen({ iid: 30 });
  page.context.createTestDeployRequestFromSelectedMr();
  assert.equal(page.requests.length, 0);
  assert.equal(page.confirmations.length, 0);
  assert.match(page.notices[0][0], /상세 정보를 다시/);
});

test('merged MR explains immutable merge-result semantics and waits for confirmation', () => {
  const page = screen();
  page.context.renderMergeRequestReview(page.mr, []);
  const button = page.detail.innerHTML.match(/<button[^>]*onclick="createTestDeployRequestFromSelectedMr\(\)"[^>]*>/)?.[0];
  assert.ok(button && !button.includes('disabled'));
  assert.match(page.detail.innerHTML, /병합 브랜치 TEST 요청/);
  assert.match(page.detail.innerHTML, /<label for="mr-test-scope">TEST 배포 범위<\/label>/);
  assert.match(page.detail.innerHTML, /<option value="ALL" selected>/);
  page.context.createTestDeployRequestFromSelectedMr();
  assert.equal(page.requests.length, 0, 'Opening or cancelling the confirmation must not submit');
  assert.equal(page.confirmations.length, 1);
  assert.match(page.confirmations[0].message, /"main"/);
  assert.match(page.confirmations[0].message, /확정된 병합 결과 commit만/);
  assert.match(page.confirmations[0].message, /이후 변경이 추가되어도 이 요청에는 포함되지 않습니다/);
});

test('confirmation submits only the named merged target and keeps its captured MR context', async () => {
  const page = screen({ targetBranch: ' release/payments ' });
  page.context.createTestDeployRequestFromSelectedMr();
  page.context.selectedMergeRequestInfo.targetBranch = 'other';
  page.context.selectedMergeRequestInfo.title = 'changed after opening';
  page.confirmations[0].confirm();
  await new Promise(setImmediate);
  assert.equal(page.requests.length, 1);
  const request = page.requests[0];
  assert.equal(request.url, '/api/v1/deploy-requests');
  assert.equal(request.method, 'POST');
  assert.equal(request.headers['X-CSRF-TOKEN'], 'csrf-token');
  assert.equal(request.body.projectId, 7);
  assert.equal(request.body.environment, 'TEST');
  assert.equal(request.body.deployMode, 'BUILD_AND_DEPLOY');
  assert.equal(request.body.deployScope, 'ALL');
  assert.equal(request.body.branch, 'release/payments');
  assert.match(request.body.reason, /MR !31 병합 결과 TEST 빌드/);
  assert.doesNotMatch(request.body.reason, /검토 완료/);
  assert.deepEqual(page.navigations, [['request-detail', 83]]);
});

for (const [scope,label] of [['WAS_ONLY','WAS만'], ['WEB_ONLY','WEB만']]) {
  test(`MR TEST request confirms and submits ${scope} with its source MR`, async () => {
    const page = screen();
    Object.assign(page.context.document.getElementById('mr-test-scope'), {value:scope, selectedOptions:[{textContent:label}]});
    page.context.createTestDeployRequestFromSelectedMr();
    assert.match(page.confirmations[0].message, new RegExp(label));
    await page.confirmations[0].confirm();
    assert.equal(page.requests[0].body.deployScope, scope);
    assert.equal(page.requests[0].body.sourceMergeRequestIid, page.mr.iid);
  });
}

test('changing the deployment scope after confirmation opens requires review again', () => {
  const page = screen();
  page.context.createTestDeployRequestFromSelectedMr();
  page.context.document.getElementById('mr-test-scope').value = 'WAS_ONLY';
  page.confirmations[0].confirm();
  assert.equal(page.requests.length, 0);
  assert.match(page.notices.at(-1)[0], /다시 검토/);
});

for (const change of ['different selection', 'different project', 'list refresh', 'detail refresh']) {
  test(`TEST confirmation refuses ${change} before POST`, () => {
    const page = screen(); page.context.createTestDeployRequestFromSelectedMr();
    if (change === 'different selection') page.context.selectedMergeRequestIid = 99;
    if (change === 'different project') {
      const original = page.context.document.getElementById;
      page.context.document.getElementById = id => id === 'project-data' ? {dataset:{id:'8'}} : original(id);
    }
    if (change === 'list refresh') page.context.mrReviewLoading = true;
    if (change === 'detail refresh') page.context.mrReviewGeneration++;
    page.confirmations[0].confirm(); assert.equal(page.requests.length, 0);
    assert.match(page.notices.at(-1)[0], /다시 검토/);
  });
}

for (const flag of ['mrActionBusy', 'mrReviewLoading']) {
  test(`TEST shortcut refuses ${flag} before opening confirmation`, () => {
    const page = screen(); page.context[flag] = true;
    page.context.createTestDeployRequestFromSelectedMr();
    assert.equal(page.confirmations.length, 0); assert.equal(page.requests.length, 0);
  });
}

test('TEST shortcut requires a complete source SHA before opening confirmation', () => {
  const page = screen({sha:'short'}); page.context.createTestDeployRequestFromSelectedMr();
  assert.equal(page.confirmations.length, 0); assert.equal(page.requests.length, 0);
});

test('deferred TEST request has one consumed confirmation, busy until rejection, and releases', async () => {
  const page = screen(); let fail; const posts=[];
  page.context.fetch = (url, options) => { posts.push({url,options}); return new Promise((yes,no)=>{fail=no;}); };
  page.context.createTestDeployRequestFromSelectedMr();
  page.confirmations[0].confirm(); page.confirmations[0].confirm();
  page.context.createTestDeployRequestFromSelectedMr();
  assert.equal(posts.length, 1); assert.equal(page.context.mrActionBusy, true);
  assert.equal(page.confirmations.length, 1);
  fail(new Error('network')); await new Promise(setImmediate);
  assert.equal(page.context.mrActionBusy, false); assert.equal(page.navigations.length, 0);
  assert.match(page.notices.at(-1)[0], /네트워크 오류/);
});

test('TEST POST denial releases busy without navigating or creating another request', async () => {
  const page=screen(); page.context.fetch=async()=>({json:async()=>({success:false,message:'권한 거부'})});
  page.context.createTestDeployRequestFromSelectedMr();page.confirmations[0].confirm();
  await new Promise(setImmediate);assert.equal(page.context.mrActionBusy,false);
  assert.equal(page.navigations.length,0);assert.match(page.notices.at(-1)[0],/권한 거부/);
});

const approvalContext = {state:'opened',deployOpsCanApprove:true,sha:'a'.repeat(40),targetBranch:'main',diffRefs:{base_sha:'b'.repeat(40),start_sha:'c'.repeat(40)}};
for (const permission of [false,undefined,'true']) {
  test(`MR approval permission ${permission} disables button and direct handler`,()=>{
    const page=screen({...approvalContext,deployOpsCanApprove:permission});
    page.context.renderMergeRequestReview(page.mr,[]);
    assert.match(page.detail.innerHTML.match(/<button[^>]*onclick="approveSelectedMergeRequest\(\)"[^>]*>/)[0],/disabled/);
    page.context.approveSelectedMergeRequest();assert.equal(page.requests.length,0);assert.match(page.notices[0][0],/승인 권한/);
  });
}
for (const overrides of [{state:'merged'},{overflow:true},{sha:'short'},{diffRefs:{base_sha:'bad',start_sha:'c'.repeat(40)}}]) {
  test(`approval retains review guard ${JSON.stringify(overrides)}`,()=>{
    const page=screen({...approvalContext,...overrides});page.context.renderMergeRequestReview(page.mr,[]);
    assert.match(page.detail.innerHTML.match(/<button[^>]*onclick="approveSelectedMergeRequest\(\)"[^>]*>/)[0],/disabled/);
    page.context.approveSelectedMergeRequest();assert.equal(page.requests.length,0);
  });
}
test('authorized fully reviewed opened MR submits approval with exact SHA context',async()=>{
 const page=screen(approvalContext);page.context.renderMergeRequestReview(page.mr,[]);
 assert.doesNotMatch(page.detail.innerHTML.match(/<button[^>]*onclick="approveSelectedMergeRequest\(\)"[^>]*>/)[0],/disabled/);
 page.context.approveSelectedMergeRequest();await new Promise(setImmediate);
 assert.equal(page.requests.length,1);assert.match(page.requests[0].url,/\/31\/approve$/);
 assert.equal(page.requests[0].body.reviewedSha,approvalContext.sha);assert.equal(page.requests[0].body.reviewedDiffBaseSha,approvalContext.diffRefs.base_sha);
});
