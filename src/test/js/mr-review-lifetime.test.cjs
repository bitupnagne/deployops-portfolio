const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const template = fs.readFileSync(path.resolve(__dirname,
  '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const script = template.slice(template.indexOf('  function loadMergeRequests('),
  template.indexOf('  function createMergeRequestFromInputs('));
assert.ok(script.includes('function executeMergeRequestAction('));
const flush = () => new Promise(setImmediate);
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; }
const reviewed = () => ({iid: 3, state: 'opened', sha: 'a'.repeat(40), targetBranch: 'main',
  diffRefs: {base_sha: 'b'.repeat(40), start_sha: 'c'.repeat(40)},
  deployOpsCanApprove: true, deployOpsCanMerge: true, canMerge: true, changes: []});
function screen() {
  const calls = [], confirmations = [], notices = [], pending = [];
  const elements = { 'project-data': {dataset: {id: '5'}}, 'mr-state-filter': {value: 'opened'},
    'mr-test-scope': {value: 'ALL', selectedOptions: [{textContent: '환경 내 전체 매핑 대상'}]},
    'mr-list-container': {innerHTML: ''}, 'mr-detail-container': {innerHTML: ''} };
  const buttons = [{disabled: false, dataset: {}}, {disabled: true, dataset: {}}];
  const context = vm.createContext({selectedMergeRequestIid: 3, selectedMergeRequestInfo: reviewed(),
    mergeRequestsLoaded: false, window: {}, escHtml: String,
    document: {getElementById: id => elements[id], querySelector: () => ({content: 'csrf'}),
      querySelectorAll: selector => selector.includes('button[data-iid]') ? [] : buttons},
    showToast: (...args) => notices.push(args),
    navigate: () => {},
    openConfirm: (title, message, confirm) => confirmations.push({title, message, confirm}),
    fetch: (url, options) => { const d = deferred(); calls.push({url, options}); pending.push(d); return d.promise; }
  });
  vm.runInContext(script, context);
  return {context, calls, confirmations, notices, pending, elements, buttons};
}
const answer = (pending, data) => pending.resolve({json: async () => data});

for (const delta of [{overflow:true}, {reviewComplete:false},
  {changes:[{collapsed:true}]}, {changes:[{tooLarge:true}]}]) {
  test(`unresolved diff refuses approval, merge, and rejection: ${JSON.stringify(delta)}`, () => {
    const p=screen(); Object.assign(p.context.selectedMergeRequestInfo, {deployOpsCanReject:true}, delta);
    p.context.approveSelectedMergeRequest(); p.context.mergeSelectedMergeRequest(); p.context.openMrRejectModal();
    assert.equal(p.calls.length,0); assert.equal(p.confirmations.length,0);
    assert.equal(p.notices.length,3);
  });
}

test('binary empty diff without omission flags remains eligible for approval', () => {
  const p=screen(); p.context.selectedMergeRequestInfo.changes=[{newPath:'icon.png',diff:''}];
  p.context.approveSelectedMergeRequest(); assert.equal(p.calls.length,1);
});

test('merged MR reconciliation requires pending exact approval and project merge permission', () => {
  for (const [permission, pending, expected] of [[true,true,true],[true,false,false],[false,true,false]]) {
    const p=screen();
    const mr={...reviewed(),state:'merged',canMerge:false,deployOpsCanMerge:permission,deployOpsCanReconcileMerge:pending};
    p.context.renderMergeRequestReview(mr,[]);
    p.context.mergeSelectedMergeRequest();
    assert.equal(p.confirmations.length,expected ? 1 : 0);
    if (expected) {
      assert.match(p.confirmations[0].title,/병합 기록 복구/);
      p.confirmations[0].confirm();
      assert.match(p.calls[0].url,/\/3\/merge$/);
      assert.equal(JSON.parse(p.calls[0].options.body).reviewedSha,mr.sha);
    }
  }
});

test('rendered omitted diff explains and disables all review mutations', () => {
  const p=screen(); p.context.renderMergeRequestReview({...reviewed(),deployOpsCanReject:true,
    changes:[{newPath:'Dockerfile',diff:'',collapsed:true}]}, []);
  const html=p.elements['mr-detail-container'].innerHTML;
  assert.match(html,/diff를 생략/);
  for(const action of ['approveSelectedMergeRequest','mergeSelectedMergeRequest','openMrRejectModal'])
    assert.match(html,new RegExp(`onclick="${action}\\(\\)" disabled`));
});

test('merge confirmation owns a deep review snapshot when selected review is cleared or mutated', () => {
  const p = screen(); p.context.mergeSelectedMergeRequest();
  p.context.selectedMergeRequestInfo.diffRefs.base_sha = 'd'.repeat(40);
  p.context.selectedMergeRequestInfo = null;
  p.confirmations[0].confirm();
  assert.equal(p.calls.length, 1);
  const body = JSON.parse(p.calls[0].options.body);
  assert.equal(body.reviewedSha, 'a'.repeat(40));
  assert.equal(body.reviewedDiffBaseSha, 'b'.repeat(40));
  assert.equal(body.reviewedTargetBranch, 'main');
});

for (const mutation of ['different MR', 'different project', 'loading', 'new review generation']) {
  test(`confirmation refuses ${mutation} without a POST`, () => {
    const p = screen(); p.context.mergeSelectedMergeRequest();
    if (mutation === 'different MR') p.context.selectedMergeRequestIid = 4;
    if (mutation === 'different project') p.elements['project-data'].dataset.id = '6';
    if (mutation === 'loading') p.context.mrReviewLoading = true;
    if (mutation === 'new review generation') p.context.mrReviewGeneration++;
    p.confirmations[0].confirm(); assert.equal(p.calls.length, 0);
    assert.match(p.notices.at(-1)[0], /다시 검토/);
  });
}

test('double confirmation and approval clicks cannot start a second in-flight mutation', () => {
  const p = screen(); p.context.mergeSelectedMergeRequest();
  p.confirmations[0].confirm(); p.confirmations[0].confirm();
  p.context.approveSelectedMergeRequest(); p.context.mergeSelectedMergeRequest();
  p.context.selectMergeRequest(4);
  assert.equal(p.calls.length, 1); assert.equal(p.context.selectedMergeRequestIid, 3);
  assert.equal(p.context.mrActionBusy, true); assert.equal(p.buttons[0].disabled, true);
});

test('approval busy covers both POST and final list refresh, then releases', async () => {
  const p = screen(); p.context.approveSelectedMergeRequest();
  assert.equal(p.context.mrActionBusy, true);
  answer(p.pending[0], {success: true}); await flush();
  assert.equal(p.calls.length, 2); assert.equal(p.context.mrActionBusy, true);
  assert.equal(p.context.mrReviewLoading, true);
  p.context.mergeSelectedMergeRequest(); assert.equal(p.confirmations.length, 0);
  answer(p.pending[1], {success: true, data: []}); await flush();
  assert.equal(p.context.mrActionBusy, false); assert.equal(p.context.mrReviewLoading, false);
  assert.equal(p.buttons[0].disabled, false); assert.equal(p.buttons[1].disabled, true);
});

test('network failure releases busy and does not erase the reviewed context', async () => {
  const p = screen(); p.context.approveSelectedMergeRequest();
  p.pending[0].reject(new Error('offline')); await flush();
  assert.equal(p.context.mrActionBusy, false); assert.equal(p.context.selectedMergeRequestInfo.sha, 'a'.repeat(40));
  assert.match(p.notices.at(-1)[0], /네트워크 오류/);
});

test('late MR detail cannot replace a newer selected review or end its loading state', async () => {
  const p = screen(); p.context.selectMergeRequest(3); p.context.selectMergeRequest(4);
  answer(p.pending[0], {success: true, data: reviewed()}); answer(p.pending[1], {success: true, data: []});
  await flush(); assert.equal(p.context.selectedMergeRequestInfo, null); assert.equal(p.context.mrReviewLoading, true);
  const next = {...reviewed(), iid: 4, sha: 'd'.repeat(40)};
  answer(p.pending[2], {success: true, data: next}); answer(p.pending[3], {success: true, data: []});
  await flush(); assert.equal(p.context.selectedMergeRequestInfo.iid, 4); assert.equal(p.context.mrReviewLoading, false);
});

test('existing absent permission and malformed review guards remain fail closed', () => {
  for (const delta of [{deployOpsCanMerge: false}, {canMerge: false}, {sha: 'bad'}, {diffRefs: {base_sha: 'bad', start_sha: 'c'.repeat(40)}}]) {
    const p = screen(); Object.assign(p.context.selectedMergeRequestInfo, delta);
    p.context.mergeSelectedMergeRequest(); p.confirmations[0]?.confirm(); assert.equal(p.calls.length, 0);
  }
});

for (const refresh of ['list', 'detail']) {
  test(`real deferred ${refresh} refresh invalidates an already open TEST confirmation`, async () => {
    const p=screen(); p.context.selectedMergeRequestInfo.state='merged';
    p.context.createTestDeployRequestFromSelectedMr();
    if(refresh==='list') p.context.loadMergeRequests(true);
    else p.context.loadMergeRequestReview(3);
    p.confirmations[0].confirm();
    assert.equal(p.calls.filter(call=>call.options?.method==='POST').length,0);
    assert.equal(p.context.mrReviewLoading,true);
    if(refresh==='list') answer(p.pending[0],{success:true,data:[]});
    else {answer(p.pending[0],{success:true,data:{...reviewed(),state:'merged'}});answer(p.pending[1],{success:true,data:[]});}
    await flush();assert.equal(p.context.mrReviewLoading,false);
    assert.equal(p.calls.filter(call=>call.options?.method==='POST').length,0);
  });
}
