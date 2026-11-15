const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/storage-mounts.js'), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));
class Element {
  constructor(tag) { this.tagName=tag; this.children=[]; this.style={}; this.dataset={}; this.listeners={}; this.attributes={}; this._value=''; this.textContent=''; this.disabled=false; }
  set value(v) { this._value=String(v); }
  get value() { return this._value; }
  append(...nodes) { for(const node of nodes) { node.parent=this; this.children.push(node); if(this.tagName==='select'&&this.children.length===1) this.value=node.value; } }
  replaceChildren(...nodes) { this.children=[]; if(this.tagName==='select')this.value=''; this.append(...nodes); }
  remove() { if(this.parent)this.parent.children=this.parent.children.filter(n=>n!==this); }
  setAttribute(k,v) { this.attributes[k]=v; }
  addEventListener(k,f) { this.listeners[k]=f; }
  showModal() { this.open=true; }
  close() { this.open=false; this.listeners.close?.(); }
  querySelectorAll(selector) {
    const parts=selector.split(',').map(s=>s.trim()); const result=[];
    const visit = node => { for(const child of node.children) { if(parts.some(p=>p===child.tagName||p==='[data-mount-field]'&&child.dataset.mountField))result.push(child); visit(child); } };
    visit(this); return result;
  }
}
function screen() {
  const requests=[], body=new Element('body');
  const context={document:{body,createElement:t=>new Element(t),querySelector:s=>s.includes('_csrf_header')?{content:'X-CSRF'}:{content:'csrf-fixture'}},window:{},fetch:(url,options)=>new Promise((resolve,reject)=>requests.push({url,options,resolve,reject}))};
  vm.createContext(context); vm.runInContext(source,context);
  const current=()=>body.children.at(-1);
  const field=text=>current().querySelectorAll('label').find(l=>l.children[0].textContent===text)?.children[1];
  const button=text=>current().querySelectorAll('button').find(b=>b.textContent===text);
  return {context,requests,current,field,button,api:context.window.DeployStorage};
}
function respond(request,data) { request.resolve({ok:true,json:async()=>({success:true,data})}); }
const view = (manage=true,name='API') => ({canManage:manage,components:[{id:5,name}],bindings:[{componentId:5,storageKey:'shared',relativePath:'orders',containerPath:'/app/data',readOnly:true}],locations:{11:[{storageKey:'shared',rootPath:'/mnt/shared'}]},serverNames:{11:'WAS1'}});

test('read-only project remains read-only after loading and shows existing binding',async()=>{
  const s=screen(); const pending=s.api.openProject(1); respond(s.requests[0],view(false)); await pending;
  assert.equal(s.button('연결 저장').disabled,true); assert.equal(s.button('마운트 추가').disabled,true);
  assert.equal(s.field('컨테이너 절대 경로').value,'/app/data'); assert.equal(s.field('컨테이너 절대 경로').disabled,true);
  assert.equal(s.requests[0].options.method,'GET');
});
test('async dialog selects opt out of the snapshot-only global enhancer and are usable after initial load',async()=>{
  const s=screen(); const pending=s.api.openProject(1);
  for(const label of ['환경','WAS 배포 단위','경로·권한 검사 대상 WAS']) {
    assert.equal(s.field(label).attributes['data-no-custom-select'],'');
    assert.equal(s.field(label).disabled,true);
  }
  respond(s.requests[0],view()); await pending;
  for(const label of ['환경','WAS 배포 단위','경로·권한 검사 대상 WAS']) assert.equal(s.field(label).disabled,false);
  assert.equal(s.field('WAS 배포 단위').value,'5'); assert.equal(s.field('경로·권한 검사 대상 WAS').value,'11');
  assert.equal(s.field('저장소 키').attributes['data-no-custom-select'],'');
  s.field('환경').value='PROD'; const reload=s.field('환경').onchange();
  assert.equal(s.current().children[1].textContent,'처리 중입니다…');
  respond(s.requests[1],view(true,'운영 API')); await reload;
  assert.equal(s.field('환경').disabled,false); assert.equal(s.field('WAS 배포 단위').children[0].textContent,'운영 API');
  assert.match(s.current().children[1].textContent,/PROD 환경.*설정을 저장한 다음/);
  assert.doesNotMatch(s.current().children[1].textContent,/불러왔습니다/);
  assert.doesNotMatch(s.current().children[1].textContent,/처리 중/);
});
test('environment reload completion preserves readonly guidance and actions',async()=>{
  const s=screen(); const pending=s.api.openProject(1); respond(s.requests[0],view()); await pending;
  s.field('환경').value='PROD'; const reload=s.field('환경').onchange();
  respond(s.requests[1],view(false)); await reload;
  assert.match(s.current().children[1].textContent,/PROD 환경.*조회 화면/);
  assert.equal(s.button('연결 저장').disabled,true);
  assert.equal(s.field('읽기 전용').disabled,true);
});
test('late response from a closed project cannot replace a new project modal',async()=>{
  const s=screen(); const first=s.api.openProject(1), second=s.api.openProject(2);
  respond(s.requests[1],view(true,'Second')); await second;
  respond(s.requests[0],view(true,'First')); await first;
  assert.equal(s.field('WAS 배포 단위').children[0].textContent,'Second');
});
test('save uses selected component, readonly flag and CSRF without granting direct deployment',async()=>{
  const s=screen(); const load=s.api.openProject(1); respond(s.requests[0],view()); await load;
  s.field('변경 사유').value='업무 데이터 경로'; s.field('읽기 전용').checked=false;
  const save=s.button('연결 저장').onclick(); await settle();
  const request=s.requests[1]; assert.equal(request.url,'/api/v1/projects/1/storage/TEST'); assert.equal(request.options.method,'PUT');
  assert.equal(request.options.headers['X-CSRF'],'csrf-fixture');
  assert.deepEqual(JSON.parse(request.options.body),{componentId:5,mounts:[{storageKey:'shared',relativePath:'orders',containerPath:'/app/data',readOnly:false}],reason:'업무 데이터 경로'});
  assert.equal(s.field('환경').disabled,true);
  respond(request,null); await settle(); respond(s.requests[2],view()); await save;
  assert.equal(s.field('환경').disabled,false); assert.equal(s.requests.some(r=>/execute|deployments/.test(r.url)),false);
});
test('saved-binding check is explicit and scoped to the selected WAS/component/environment',async()=>{
  const s=screen(); const load=s.api.openProject(1); respond(s.requests[0],view()); await load;
  s.field('컨테이너 절대 경로').value='/app/unsaved';
  const check=s.button('저장된 연결 경로·권한 검사').onclick(); await settle();
  assert.equal(s.requests[1].url,'/api/v1/projects/1/storage/TEST/check?serverId=11&componentId=5');
  assert.equal(s.requests[1].options.method,'POST'); assert.equal(s.requests[1].options.body,undefined);
  respond(s.requests[1],null); await check;
  assert.match(s.current().children[1].textContent,/미저장/);
});
test('server inspect identity cannot be attached to a subsequently changed root',async()=>{
  const s=screen(); const load=s.api.openServer(11); respond(s.requests[0],[]); await load;
  s.field('서버의 기존 절대 경로').value='/mnt/first';
  const inspect=s.button('서버에서 경로·마운트 확인').onclick(); await settle();
  s.field('서버의 기존 절대 경로').value='/mnt/second'; s.field('서버의 기존 절대 경로').listeners.input();
  respond(s.requests[1],{rootPath:'/mnt/first',mountTarget:'/mnt/first',mountSource:'nas:/first',fsType:'nfs4'}); await inspect;
  assert.equal(s.field('확인된 마운트 원본').value,'');
  await s.button('허용 저장소 저장').onclick(); assert.equal(s.requests.length,2);
  assert.match(s.current().children[1].textContent,/먼저 서버/);
});
test('failed environment reload clears previous mounts and cannot submit previous data to PROD',async()=>{
  const s=screen(); const load=s.api.openProject(1); respond(s.requests[0],view()); await load;
  s.field('환경').value='PROD'; const reload=s.field('환경').onchange();
  assert.equal(s.field('컨테이너 절대 경로'),undefined); assert.equal(s.button('연결 저장').disabled,true);
  s.requests[1].reject(new Error('PROD 조회 실패')); await reload;
  assert.equal(s.button('연결 저장').disabled,true); assert.equal(s.button('저장된 연결 경로·권한 검사').disabled,true);
  await s.button('연결 저장').onclick(); await s.button('저장된 연결 경로·권한 검사').onclick();
  assert.equal(s.requests.length,2); assert.match(s.current().children[1].textContent,/현재 환경/);
});
