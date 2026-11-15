const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/nexus-backup.js'),'utf8');
async function fixture(role='APPROVER',requestedById=10,options={}){
  const state={calls:[],confirm:null,toast:[],ready:true,pendingReadiness:options.pendingReadiness===true,unavailable:false,readinessResolve:null,restoreRows:[],pendingDetail:false,detailFailures:0,detailResolve:null,detailResolves:[],timer:null,closed:false,rows:[{id:1,requestedById,requestedBy:'요청자',status:'PENDING',stage:'PREFLIGHT',reason:'<script>secret()</script>',createdAt:'2026-10-11',completedAt:null}]};
  if(options.rows)state.rows=options.rows;
  let document;
  class Element{constructor(tag){this.tag=tag;this.isConnected=true;this.children=[];this.dataset={};this.style={};this.textContent='';this.checked=false;this.value='';}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}contains(node){return this===node||this.children.some(x=>x.contains&&x.contains(node));}querySelectorAll(tag){const result=[];for(const x of this.children){if(x.tag===tag)result.push(x);if(x.querySelectorAll)result.push(...x.querySelectorAll(tag));}return result;}focus(){document.activeElement=this;}setAttribute(key,value){this[key]=value;}reportValidity(){return true;}set innerHTML(value){throw new Error('unsafe HTML insertion');}}
  const elements={};for(const id of ['nexus-ops-context','nxbackup-body','nxbackup-error','nxbackup-ready','nxbackup-external','nxbackup-reason','nxbackup-refresh','nxbackup-request','nxrestore-body','nxrestore-error','nxrestore-ready','nxrestore-detail'])elements[id]=new Element(id==='nxbackup-refresh'?'button':'div');elements['nexus-ops-context'].dataset={userId:'20',role};
  document={activeElement:null,hidden:false,getElementById:id=>elements[id],createElement:tag=>new Element(tag),createTextNode:text=>({textContent:text}),querySelector:selector=>({content:selector.includes('_csrf_header')?'X-CSRF':'fixture-csrf'})};
  vm.runInNewContext(source,{document,fetch:async(url,options)=>{state.calls.push({url,options});if(url==='/api/v1/nexus/backup-requests/readiness')return{ok:true,json:async()=>({success:true,data:{ready:true}})};if(url.endsWith('/readiness')&&state.unavailable)throw new Error('준비 상태 조회 실패');if(url.endsWith('/readiness')&&state.pendingReadiness)return new Promise(resolve=>state.readinessResolve=ready=>resolve({ok:true,json:async()=>({success:true,data:{ready}})}));const detailId=/^\/api\/v1\/nexus\/restore-requests\/(\d+)$/.exec(url);if(detailId&&state.detailFailures>0){state.detailFailures--;throw new Error('detail fixture temporary failure');}if(detailId&&state.pendingDetail)return new Promise(resolve=>{state.detailResolve=data=>resolve({ok:true,json:async()=>({success:true,data})});state.detailResolves.push({id:Number(detailId[1]),resolve:state.detailResolve});});let data=url.endsWith('/readiness')?{ready:state.ready}:url==='/api/v1/nexus/restore-requests'?state.restoreRows:detailId?state.restoreRows.find(row=>row.id===Number(detailId[1])):state.rows;return{ok:true,json:async()=>({success:true,message:'처리됨',data})};},showToast:(message,type)=>state.toast.push({message,type}),openConfirm:(title,message,yes)=>state.confirm={title,message,yes},setInterval:fn=>(state.timer=fn,1),clearInterval:()=>state.closed=true,window:{addEventListener:(name,fn)=>state.close=fn,removeEventListener(){},prompt:()=> '유지보수 창 없음'}});
  const flush=async()=>{await new Promise(setImmediate);await new Promise(setImmediate);};await flush();
  return {state,elements,document,flush,buttons:()=>elements['nxbackup-body'].querySelectorAll('button')};
}

const pending=()=>({id:1,backupRequestId:2,requestedById:10,requestedBy:'요청자',status:'PENDING',reason:'<script>unsafe</script>',manifestSha256:'a'.repeat(64)});
const health=()=>({...pending(),status:'HEALTH_READY',cloneContainerId:'b'.repeat(64),cloneName:'nexus-restore-1',originalProjectionPreserved:true,mutationUncertain:false,expectedArtifacts:'[]',cloneAccess:JSON.stringify({schema:1,executionHost:'deploy01',requiresVerifiedRelay:true,ports:[{containerPort:8081,hostIp:'127.0.0.1',hostPort:18003}]})});

test('distinct approver actions and safe text are limited to pending restore rows',async()=>{
  const ui=await fixture();ui.state.restoreRows=[pending()];ui.state.timer();await ui.flush();
  const row=ui.elements['nxrestore-body'].children[0];assert.ok(row.children[0].textContent.includes('<script>unsafe</script>'));
  assert.deepEqual(ui.elements['nxrestore-body'].querySelectorAll('button').map(b=>b.textContent),['상세','격리복원 승인','반려']);
  ui.state.restoreRows[0].requestedById=20;ui.state.timer();await ui.flush();
  assert.deepEqual(ui.elements['nxrestore-body'].querySelectorAll('button').map(b=>b.textContent),['상세']);
});

test('completed backup restore request uses immutable backup ID and CSRF only once',async()=>{
  const ui=await fixture('OPERATOR');ui.state.rows=[{id:2,status:'COMPLETED',artifactId:'nexus-backup-2',manifestSha256:'a'.repeat(64)}];ui.state.timer();await ui.flush();
  const button=ui.buttons().find(b=>b.textContent==='격리복원 신청');assert.ok(button);button.onclick();button.onclick();await ui.flush();
  const posts=ui.state.calls.filter(c=>c.options?.method==='POST');assert.equal(posts.length,1);assert.equal(posts[0].url,'/api/v1/nexus/restore-requests');assert.deepEqual(JSON.parse(posts[0].options.body),{backupRequestId:2,reason:'유지보수 창 없음'});assert.equal(posts[0].options.headers['X-CSRF'],'fixture-csrf');
});

test('not-ready restore explains root opt-in and never publishes a request',async()=>{
  const ui=await fixture('OPERATOR');ui.state.ready=false;ui.state.rows=[{id:2,status:'COMPLETED'}];ui.state.timer();await ui.flush();ui.buttons().find(b=>b.textContent==='격리복원 신청').onclick();await ui.flush();
  assert.match(ui.elements['nxrestore-ready'].textContent,/미준비/);assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('initial readiness loading does not offer an enabled restore request',async()=>{
  const ui=await fixture('OPERATOR',10,{pendingReadiness:true,rows:[{id:2,status:'COMPLETED'}]});
  assert.match(ui.elements['nxrestore-ready'].textContent,/확인하고/);
  assert.equal(ui.buttons().filter(b=>b.textContent==='격리복원 신청'&&!b.disabled).length,0);
  ui.state.readinessResolve(false);await ui.flush();
  const button=ui.buttons().find(b=>b.textContent==='격리복원 신청');
  assert.equal(button.disabled,true);assert.match(button.title,/미준비/);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('unchanged completed row follows loading false true unavailable readiness in its DOM',async()=>{
  const ui=await fixture('OPERATOR',10,{rows:[{id:2,status:'COMPLETED'}]});
  const button=ui.buttons().find(b=>b.textContent==='격리복원 신청');
  assert.equal(button.disabled,false);
  ui.state.pendingReadiness=true;ui.state.timer();await ui.flush();
  assert.equal(button.disabled,true);assert.match(button.title,/확인하고/);
  button.onclick();assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
  ui.state.readinessResolve(false);await ui.flush();
  assert.equal(button.disabled,true);assert.match(button.title,/미준비/);
  ui.state.pendingReadiness=false;ui.state.ready=true;ui.state.timer();await ui.flush();
  assert.equal(ui.buttons().find(b=>b.textContent==='격리복원 신청'),button);
  assert.equal(button.disabled,false);assert.match(button.title,/신청합니다/);
  ui.state.unavailable=true;ui.state.timer();await ui.flush();
  assert.equal(button.disabled,true);assert.match(button.title,/확인하지 못/);
  assert.match(ui.elements['nxrestore-ready'].textContent,/다시 조회/);
  button.onclick();assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('restore readiness never grants request permission to an approver',async()=>{
  const ui=await fixture('APPROVER',10,{rows:[{id:2,status:'COMPLETED'}]});
  for(const ready of [false,true]){
    ui.state.ready=ready;ui.state.timer();await ui.flush();
    assert.equal(ui.buttons().filter(b=>b.textContent==='격리복원 신청').length,0);
  }
  const admin=await fixture('SUPER_ADMIN',10,{rows:[{id:2,status:'COMPLETED'}]});
  assert.equal(admin.buttons().find(b=>b.textContent==='격리복원 신청').disabled,false);
});

test('legacy health detail distinguishes server loopback, manual checklist and unavailable artifact baseline',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();ui.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ui.flush();
  const detail=ui.elements['nxrestore-detail'];assert.ok(detail.querySelectorAll('p').some(c=>c.textContent.includes('현재 브라우저의 localhost 주소가 아닙니다')));assert.ok(detail.querySelectorAll('p').some(c=>c.textContent.includes('개별 자료 기준 미제공')));
  const form=detail.querySelectorAll('form')[0],inputs=form.querySelectorAll('input');assert.equal(inputs.length,6);inputs.forEach(i=>i.checked=true);form.onsubmit({preventDefault(){}});await ui.flush();
  const post=ui.state.calls.find(c=>c.url.endsWith('/functional-confirmation'));const value=JSON.parse(post.options.body);assert.equal(value.cloneContainerId,'b'.repeat(64));assert.equal(value.manifestSha256,'a'.repeat(64));assert.deepEqual(value.artifacts,[]);assert.equal(value.checklist.length,6);assert.equal(ui.state.calls.filter(c=>c.url.includes('cleanup')).length,0);
});

test('late detail completion after page leave cannot write old or new context',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();ui.state.pendingDetail=true;ui.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ui.flush();ui.state.close();ui.state.detailResolve(health());await ui.flush();assert.equal(ui.elements['nxrestore-detail'].children.length,0);assert.equal(ui.state.closed,true);
});

test('confirmed detail displays durable actor and time without another confirmation form',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[{...health(),status:'OPERATOR_CONFIRMED',confirmedById:20,confirmedAt:'2026-10-11T07:00:00',operatorChecklist:'["LOGIN"]',artifactConfirmations:'[]'}];ui.state.timer();await ui.flush();ui.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ui.flush();const detail=ui.elements['nxrestore-detail'];assert.ok(detail.querySelectorAll('p').some(c=>c.textContent.includes('운영자 확인자 #20')));assert.ok(detail.querySelectorAll('p').some(c=>c.textContent.includes('자동 검증 성공·원장 해소·자동 정리를 뜻하지 않습니다')));assert.equal(detail.querySelectorAll('form').length,0);
});

test('precontainer recheck is OP-only exact-token CSRF and double click publishes once',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',submitted:true}];ui.state.timer();await ui.flush();
  const button=ui.elements['nxrestore-body'].querySelectorAll('button').find(b=>b.textContent==='실행 전 실패 상태 재확인');assert.ok(button);button.onclick();await ui.flush();
  const confirm=ui.state.confirm;confirm.yes();confirm.yes();await ui.flush();
  const posts=ui.state.calls.filter(c=>c.options?.method==='POST');assert.equal(posts.length,1);assert.equal(posts[0].url,'/api/v1/nexus/restore-requests/1/recheck');assert.equal(posts[0].options.headers['X-CSRF'],'fixture-csrf');assert.deepEqual(JSON.parse(posts[0].options.body),{});
});
test('held active check has no retry action and approver cannot request recheck',async()=>{
 for(const role of ['OPERATOR','APPROVER']){const ui=await fixture(role);ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',activeCheckId:7}];ui.state.timer();await ui.flush();assert.equal(ui.elements['nxrestore-body'].querySelectorAll('button').filter(b=>b.textContent==='실행 전 실패 상태 재확인').length,0);}
});
test('unconfirmed recheck remains visibly held and readiness false prevents submission',async()=>{
 const ui=await fixture('OPERATOR');ui.state.ready=false;ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',lastCheckStatus:'UNCONFIRMED'}];ui.state.timer();await ui.flush();const button=ui.elements['nxrestore-body'].querySelectorAll('button').find(b=>b.textContent==='실행 전 실패 상태 재확인');assert.equal(button.disabled,true);button.onclick();assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);assert.ok(ui.elements['nxrestore-body'].children[0].children.some(td=>td.children.some(c=>c.textContent.includes('이전 확인 미확정'))));
});

const detailGets=ui=>ui.state.calls.filter(c=>/^\/api\/v1\/nexus\/restore-requests\/\d+$/.test(c.url)&&!c.options?.method);
const openFirstDetail=async ui=>{ui.elements['nxrestore-body'].querySelectorAll('button').find(b=>b.textContent==='상세').onclick();await ui.flush();};

test('open uncertain restore detail follows the polled confirmed failure without reopening or POST',async()=>{
  const ui=await fixture('OPERATOR');
  ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',stage:'RESTORING',errorCode:'RESULT_UNCERTAIN',mutationUncertain:true,originalProjectionPreserved:false}];
  ui.state.timer();await ui.flush();await openFirstDetail(ui);
  assert.match(ui.elements['nxrestore-detail'].querySelectorAll('p')[0].textContent,/미확정·차단 유지/);
  ui.state.restoreRows=[{...ui.state.restoreRows[0],status:'FAILED',stage:'DONE',errorCode:'RESTORE_FAILED',mutationUncertain:false,originalProjectionPreserved:true}];
  ui.state.timer();await ui.flush();
  const identity=ui.elements['nxrestore-detail'].querySelectorAll('p')[0].textContent;
  assert.match(identity,/격리복원 실패 · 원본 보존 확인 · 실행 불확실성 없음/);
  assert.doesNotMatch(identity,/미확정·차단 유지/);
  assert.equal(detailGets(ui).length,2);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
  ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,2);
});

test('eligible detail refresh retains the same manual form, entered values and focused input',async()=>{
  const ui=await fixture('OPERATOR');
  const row={...health(),expectedArtifacts:JSON.stringify([{ref:'maven/example.jar',baselineReceiptSha256:'c'.repeat(64)}])};
  ui.state.restoreRows=[row];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const detail=ui.elements['nxrestore-detail'],form=detail.querySelectorAll('form')[0],inputs=form.querySelectorAll('input');
  inputs[0].checked=true;inputs[6].value='d'.repeat(64);inputs[6].focus();
  ui.state.restoreRows=[{...row,reason:'새로운 공개 설명'}];ui.state.timer();await ui.flush();
  assert.equal(detail.querySelectorAll('form')[0],form);
  assert.equal(form.querySelectorAll('input')[0],inputs[0]);assert.equal(inputs[0].checked,true);
  assert.equal(inputs[6].value,'d'.repeat(64));assert.equal(ui.document.activeElement,inputs[6]);
  assert.equal(detailGets(ui).length,2);assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
  inputs.forEach(i=>{if(i.type==='checkbox')i.checked=true;});
  form.onsubmit({preventDefault(){}});form.onsubmit({preventDefault(){}});await ui.flush();
  const posts=ui.state.calls.filter(c=>c.options?.method==='POST');assert.equal(posts.length,1);
  assert.equal(posts[0].url,'/api/v1/nexus/restore-requests/1/functional-confirmation');
  assert.deepEqual(JSON.parse(posts[0].options.body).artifacts,[{ref:'maven/example.jar',downloadedSha256:'d'.repeat(64)}]);
});

test('unchanged selected row and another request change do not refetch or replace manual detail',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health(),{...pending(),id:2}];
  ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const form=ui.elements['nxrestore-detail'].querySelectorAll('form')[0],input=form.querySelectorAll('input')[0];
  input.checked=true;input.focus();
  ui.state.timer();await ui.flush();
  ui.state.restoreRows=[health(),{...pending(),id:2,reason:'다른 요청 변경'}];ui.state.timer();await ui.flush();
  assert.equal(detailGets(ui).length,1);assert.equal(ui.elements['nxrestore-detail'].querySelectorAll('form')[0],form);
  assert.equal(input.checked,true);assert.equal(ui.document.activeElement,input);
});

test('late polled response cannot overwrite a newly selected restore detail',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[pending(),{...pending(),id:2}];
  ui.state.timer();await ui.flush();await openFirstDetail(ui);
  ui.state.pendingDetail=true;
  ui.state.restoreRows=[{...pending(),status:'FAILED'},ui.state.restoreRows[1]];ui.state.timer();await ui.flush();
  const old=ui.state.detailResolves[0];assert.equal(old.id,1);
  ui.elements['nxrestore-body'].querySelectorAll('button').filter(b=>b.textContent==='상세')[1].onclick();await ui.flush();
  ui.state.detailResolves[1].resolve(ui.state.restoreRows[1]);await ui.flush();
  old.resolve(ui.state.restoreRows[0]);await ui.flush();
  assert.match(ui.elements['nxrestore-detail'].querySelectorAll('p')[0].textContent,/복원 #2/);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('late automatic detail refresh after page leave does not update the retained DOM',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[pending()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const fields=ui.elements['nxrestore-detail'].children[0],identity=fields.children[0];
  ui.state.pendingDetail=true;ui.state.restoreRows=[{...pending(),status:'FAILED'}];ui.state.timer();await ui.flush();
  ui.state.close();ui.state.detailResolve(ui.state.restoreRows[0]);await ui.flush();
  assert.equal(ui.elements['nxrestore-detail'].children[0],fields);assert.equal(fields.children[0],identity);
  assert.equal(ui.state.closed,true);
});

test('a changed clone binding invalidates the old confirmation form and its stale submit handler',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const oldForm=ui.elements['nxrestore-detail'].querySelectorAll('form')[0];oldForm.querySelectorAll('input')[0].checked=true;
  ui.state.restoreRows=[{...health(),cloneContainerId:'e'.repeat(64)}];ui.state.timer();await ui.flush();
  const newForm=ui.elements['nxrestore-detail'].querySelectorAll('form')[0];assert.notEqual(newForm,oldForm);
  assert.equal(newForm.querySelectorAll('input')[0].checked,false);
  oldForm.onsubmit({preventDefault(){}});await ui.flush();
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('failed automatic detail GET retries on the next unchanged poll and preserves manual input and focus',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const detail=ui.elements['nxrestore-detail'],form=detail.querySelectorAll('form')[0],input=form.querySelectorAll('input')[0];
  input.checked=true;input.focus();
  ui.state.detailFailures=1;ui.state.restoreRows=[{...health(),confirmedAt:'2026-10-12T01:00:00Z',confirmedById:20}];
  ui.state.timer();await ui.flush();
  assert.equal(detailGets(ui).length,2);assert.equal(detail.querySelectorAll('form')[0],form);
  assert.equal(input.checked,true);assert.equal(ui.document.activeElement,input);
  assert.equal(detail.querySelectorAll('p').some(p=>p.textContent.includes('운영자 확인자 #20')),false);
  ui.state.timer();await ui.flush();
  assert.equal(detailGets(ui).length,3);
  assert.equal(detail.querySelectorAll('p').some(p=>p.textContent.includes('운영자 확인자 #20')),true);
  assert.equal(detail.querySelectorAll('form')[0],form);assert.equal(input.checked,true);assert.equal(ui.document.activeElement,input);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
  ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,3);
});

test('failed manual detail GET with unchanged row retries and retains existing form and focus',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const form=ui.elements['nxrestore-detail'].querySelectorAll('form')[0],input=form.querySelectorAll('input')[0];
  input.checked=true;input.focus();ui.state.detailFailures=1;
  await openFirstDetail(ui);
  assert.equal(detailGets(ui).length,2);assert.match(ui.elements['nxrestore-error'].textContent,/다음 조회/);
  assert.equal(ui.elements['nxrestore-detail'].querySelectorAll('form')[0],form);
  ui.state.timer();await ui.flush();
  assert.equal(detailGets(ui).length,3);assert.equal(ui.elements['nxrestore-error'].textContent,'');
  assert.equal(ui.elements['nxrestore-detail'].querySelectorAll('form')[0],form);
  assert.equal(input.checked,true);assert.equal(ui.document.activeElement,input);
  ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,3);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('detail retries stop after three polled attempts and explicit detail action resets the budget',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  ui.state.detailFailures=10;await openFirstDetail(ui);
  for(let i=0;i<6;i++){ui.state.timer();await ui.flush();}
  assert.equal(detailGets(ui).length,5);
  assert.match(ui.elements['nxrestore-error'].textContent,/상세 버튼으로 다시 조회/);
  ui.state.detailFailures=0;await openFirstDetail(ui);
  assert.equal(detailGets(ui).length,6);assert.equal(ui.elements['nxrestore-error'].textContent,'');
  ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,6);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('page leave cancels polling recovery after a failed manual detail GET',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  ui.state.detailFailures=1;await openFirstDetail(ui);ui.state.close();
  ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,2);
  assert.equal(ui.state.closed,true);assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('unchanged polls do not duplicate an in-flight automatic detail GET',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  ui.state.pendingDetail=true;ui.state.restoreRows=[{...health(),reason:'changed while open'}];ui.state.timer();await ui.flush();
  assert.equal(detailGets(ui).length,2);
  ui.state.timer();await ui.flush();ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,2);
  ui.state.detailResolve(ui.state.restoreRows[0]);await ui.flush();
  ui.state.timer();await ui.flush();assert.equal(detailGets(ui).length,2);
  assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('bridge health detail shows exact host scope without publish link and keeps six manual checks',async()=>{
  const ui=await fixture('OPERATOR');
  const row={...health(),cloneAccess:JSON.stringify({schema:2,transport:'EXECUTION_HOST_BRIDGE_ACCESS',executionHost:'deploy01',cloneContainerId:'b'.repeat(64),networkId:'d'.repeat(64),containerIpv4:'198.18.30.2',containerPort:8081,requiresVerifiedRelay:true})};
  ui.state.restoreRows=[row];ui.state.timer();await ui.flush();
  ui.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ui.flush();
  const detail=ui.elements['nxrestore-detail'],text=detail.querySelectorAll('p').map(p=>p.textContent).join(' ');
  assert.match(text,/내부 주소 198.18.30.2:8081/);
  assert.match(text,/승인된 관리 접속 경로/);
  assert.match(text,/접속할 수 없으면 아래 확인 항목을 선택하지 마세요/);
  assert.doesNotMatch(text,/SSH|localhost/);
  assert.ok(text.includes('d'.repeat(64)));assert.equal(detail.querySelectorAll('a').length,0);
  const form=detail.querySelectorAll('form')[0],inputs=form.querySelectorAll('input');assert.equal(inputs.length,6);
  inputs.forEach(i=>i.checked=true);form.onsubmit({preventDefault(){}});await ui.flush();
  const post=ui.state.calls.find(c=>c.url.endsWith('/functional-confirmation'));
  assert.equal(post.options.headers['X-CSRF'],'fixture-csrf');
  const value=JSON.parse(post.options.body);assert.deepEqual(value.checklist,['LOGIN','H2_LOCAL','FILE_BLOB_LOCAL','TASKS','USERS_ROLES_REALMS','REPOSITORIES']);assert.deepEqual(value.artifacts,[]);
  assert.equal(ui.state.calls.filter(c=>c.url.includes('cleanup')).length,0);
});

test('bridge detail refresh preserves in-progress manual inputs and approver cannot confirm',async()=>{
  const access=JSON.stringify({schema:2,transport:'EXECUTION_HOST_BRIDGE_ACCESS',executionHost:'deploy01',cloneContainerId:'b'.repeat(64),networkId:'d'.repeat(64),containerIpv4:'198.18.30.2',containerPort:8081,requiresVerifiedRelay:true});
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[{...health(),cloneAccess:access}];ui.state.timer();await ui.flush();ui.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ui.flush();
  const form=ui.elements['nxrestore-detail'].querySelectorAll('form')[0];form.querySelectorAll('input')[0].checked=true;
  ui.state.restoreRows[0]={...ui.state.restoreRows[0],stage:'DONE'};ui.state.timer();await ui.flush();
  assert.equal(ui.elements['nxrestore-detail'].querySelectorAll('form')[0],form);assert.equal(form.querySelectorAll('input')[0].checked,true);
  const ap=await fixture('APPROVER');ap.state.restoreRows=[{...health(),cloneAccess:access}];ap.state.timer();await ap.flush();ap.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ap.flush();assert.equal(ap.elements['nxrestore-detail'].querySelectorAll('form').length,0);
});

test('existing clone check is distinct OP-only confirmation and submits once with CSRF',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',errorCode:'RESULT_UNCERTAIN',submitted:true}];ui.state.timer();await ui.flush();
  const buttons=ui.elements['nxrestore-body'].querySelectorAll('button');
  assert.ok(buttons.some(b=>b.textContent==='실행 전 실패 상태 재확인'));
  const check=buttons.find(b=>b.textContent==='기존 복원본 준비 상태 확인');assert.ok(check);check.onclick();await ui.flush();
  assert.match(ui.state.confirm.message,/복원을 다시 실행하거나 자원을 삭제하지 않습니다/);assert.match(ui.state.confirm.message,/여섯 항목 기능 검증은 별도/);
  const confirm=ui.state.confirm;confirm.yes();confirm.yes();await ui.flush();
  const posts=ui.state.calls.filter(c=>c.options?.method==='POST');assert.equal(posts.length,1);
  assert.equal(posts[0].url,'/api/v1/nexus/restore-requests/1/existing-clone-recheck');assert.equal(posts[0].options.headers['X-CSRF'],'fixture-csrf');assert.deepEqual(JSON.parse(posts[0].options.body),{});
  const ap=await fixture('APPROVER');ap.state.restoreRows=ui.state.restoreRows;ap.state.timer();await ap.flush();
  assert.equal(ap.elements['nxrestore-body'].querySelectorAll('button').some(b=>b.textContent==='기존 복원본 준비 상태 확인'),false);
});

test('active check and missing readiness block both check kinds and health still requires six manual GUI checks',async()=>{
  const ui=await fixture('OPERATOR');ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',errorCode:'RESULT_UNCERTAIN',submitted:true}];ui.state.timer();await ui.flush();
  const button=ui.elements['nxrestore-body'].querySelectorAll('button').find(b=>b.textContent==='기존 복원본 준비 상태 확인');
  ui.state.ready=false;ui.state.timer();await ui.flush();assert.equal(button.disabled,true);button.onclick();assert.equal(ui.state.confirm,null);
  ui.state.ready=true;ui.state.restoreRows[0].activeCheckId=7;ui.state.timer();await ui.flush();
  assert.equal(ui.elements['nxrestore-body'].querySelectorAll('button').some(b=>b.textContent.includes('상태 확인')),false);
  ui.state.restoreRows=[health()];ui.state.timer();await ui.flush();await openFirstDetail(ui);
  const form=ui.elements['nxrestore-detail'].querySelectorAll('form')[0];assert.equal(form.querySelectorAll('input').length,6);
  assert.equal(form.querySelectorAll('input').some(i=>i.checked),false);assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('existing clone action is absent for populated clone fields or another hold reason',async()=>{
  const ui=await fixture('OPERATOR');
  for(const extra of [{cloneImageId:'sha256:'+ 'b'.repeat(64)},{cloneAccess:'{}'},{errorCode:'ORIGINAL_CHANGED'},{status:'RECONCILING'}]){
    ui.state.restoreRows=[{...pending(),status:'RECOVERY_REQUIRED',errorCode:'RESULT_UNCERTAIN',submitted:true,...extra}];ui.state.timer();await ui.flush();
    assert.equal(ui.elements['nxrestore-body'].querySelectorAll('button').some(b=>b.textContent==='기존 복원본 준비 상태 확인'),false);
    assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
  }
});


const neverExecutedRow=status=>({...pending(),status,submitted:false,cloneContainerId:null,cloneImageId:null,cloneName:null,cloneVolume:null,cloneNetwork:null,cloneAccess:null,expectedArtifacts:'[]',originalProjectionPreserved:false,mutationUncertain:true});
async function renderedRestore(row){
  const ui=await fixture();ui.state.restoreRows=[row];ui.state.timer();await ui.flush();
  ui.elements['nxrestore-body'].querySelectorAll('button')[0].onclick();await ui.flush();
  const detail=ui.elements['nxrestore-detail'];
  return {ui,detail,text:detail.children[0].children.map(node=>node.textContent).join('\n')};
}
test('unsubmitted pending and rejected restore explains execution has not happened',async()=>{
  for(const status of ['PENDING','REJECTED']){
    const {ui,detail,text}=await renderedRestore(neverExecutedRow(status));
    assert.ok(text.includes(status==='REJECTED'?'반려 · 실행되지 않음':'승인 대기 · 실행 전'));
    assert.ok(text.includes('복원본 생성 전'));assert.ok(text.includes('해당 없음(실행 전)'));
    assert.doesNotMatch(text,/실행 불확실성|개별 자료 기준 미제공|원본 다운로드 기준 영수증/);
    assert.equal(detail.querySelectorAll('form').length,0);
    assert.equal(ui.state.calls.filter(c=>c.options?.method==='POST').length,0);
  }
});
test('unknown submitted or any clone evidence cannot hide conservative restore warnings',async()=>{
  const overrides=[{submitted:true},{submitted:undefined},{submitted:null},{status:'RECONCILING'},{status:'FAILED'},
    ...['cloneContainerId','cloneImageId','cloneName','cloneVolume','cloneNetwork'].map(key=>({[key]:'existing'})),
    {cloneAccess:JSON.stringify({schema:1,executionHost:'deploy01',ports:[]})},
    {cloneContainerId:undefined},{expectedArtifacts:JSON.stringify([{ref:'artifact',baselineReceiptSha256:'c'.repeat(64)}])}];
  for(const override of overrides){
    const {text}=await renderedRestore({...neverExecutedRow('REJECTED'),...override});
    assert.match(text,/원본 보존 미확정 · 실행 불확실성 있음/);
    assert.doesNotMatch(text,/반려 · 실행되지 않음|해당 없음\(실행 전\)/);
    assert.match(text,/개별 자료 기준 미제공|원본 다운로드 기준 영수증/);
  }
});
