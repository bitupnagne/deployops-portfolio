const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/nexus-dependencies.js'),'utf8');
const deferred = () => { let resolve, reject; const promise=new Promise((a,b)=>{resolve=a;reject=b;}); return {promise,resolve,reject}; };
const flush = () => new Promise(resolve=>setImmediate(resolve));
class Element {
  constructor(tag='div') {this.tag=tag;this.children=[];this.dataset={};this.style={};this.events={};this.value='';this.textContent='';this.isConnected=true;this.files=[];}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=nodes;}
  setAttribute(){}
  addEventListener(name,fn){this.events[name]=fn;}
  querySelector(selector){return this.querySelectorAll(selector)[0] || null;}
  querySelectorAll(selector){
    return this.children.flatMap(child=>[
      ...((selector==='[data-receive-id]' ? child.dataset.receiveId : child.tag===selector) ? [child] : []),
      ...child.querySelectorAll(selector)
    ]);
  }
}
function page(fetch, {search='', projectIds=[], confirm=()=>true, onSend, timer=setTimeout}={}) {
  const elements=new Map(), root=new Element(), transfers=[];
  const el=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  root.querySelector=id=>el(id.replace('#nxd-',''));
  el('project').options=projectIds.map(value=>({value:String(value)}));
  class Xhr {
    constructor(){this.upload={};transfers.push(this);}
    open(method,url){this.url=url;}
    setRequestHeader(){}
    send(blob){this.blob=blob;onSend?.(this);}
    abort(){this.onabort?.();}
  }
  const document={body:new Element(), getElementById:id=>id==='nxdependency'?root:null,querySelector:()=>null,createElement:tag=>new Element(tag)};
  class Option extends Element {
    constructor(text,value){super('option');this.textContent=text;this.value=value;}
  }
  vm.runInNewContext(source,{document,Option,URLSearchParams,window:{location:{search},confirm,addEventListener(){},removeEventListener(){}},
    MutationObserver:class{observe(){}disconnect(){}},fetch,XMLHttpRequest:Xhr,setTimeout:timer,console});
  return {el,root,transfers};
}
const ok=data=>({ok:true,json:async()=>({success:true,data})});
const binding=name=>({binding:{format:'raw',repository:name,namespace:''},canUpload:true,canManage:true});
async function batch(format,paths) {
  const rows=[], posted=[];
  const screen=page((url,options)=>{
    if(url.endsWith('/uploads') && options.method==='POST') {
      const data=JSON.parse(options.body);posted.push(data.relativePath);
      const row={id:'item-'+rows.length,repositoryName:'repo',repositoryFormat:format,targetPath:data.relativePath,
        totalBytes:data.totalBytes,receivedBytes:0,publishedBytes:0,status:'RECEIVING'};
      rows.push(row);return Promise.resolve(ok(row));
    }
    if(url.endsWith('/publish')) {
      const row=rows.find(row=>url.includes('/'+row.id+'/'));
      row.status='COMPLETED';row.publishedBytes=row.totalBytes;return Promise.resolve(ok(row));
    }
    return Promise.resolve(ok(url.endsWith('/uploads')?rows:{...binding('repo'),binding:{format,repository:'repo',namespace:''}}));
  },{timer:callback=>setImmediate(callback),onSend:transfer=>setImmediate(()=>{
    const row=rows.find(row=>transfer.url.includes('/'+row.id+'/'));
    row.status='READY';row.receivedBytes=row.totalBytes;
    transfer.status=200;transfer.responseText=JSON.stringify({success:true,data:row});transfer.onload();
  })});
  screen.el('project').value='1';await screen.el('project').events.change();
  screen.el('prefix').value='incoming';
  screen.el('folder').files=paths.map(relative=>({name:relative.split('/').at(-1),webkitRelativePath:'selected/'+relative,size:3,slice:()=>({})}));
  await screen.el('start').events.click();
  return {posted,summary:screen.el('selection-summary').textContent,message:screen.el('message').textContent};
}
test('Maven batch omits matching sidecars irrespective of input order while keeping standalone files and queue order',async()=>{
  const result=await batch('maven2',[
    'com/a/lib.jar.SHA1','com/a/lib.pom.md5','com/a/lib.jar','com/a/lib.pom',
    'com/a/lib.jar.sha256','com/a/lib.jar.asc','com/a/other.jar.sha1','com/a/LIB.jar.sha1',
    'com/a/lib.jar.asc.sha1'
  ]);
  assert.deepEqual(result.posted,[
    'incoming/com/a/lib.jar','incoming/com/a/lib.pom','incoming/com/a/lib.jar.asc',
    'incoming/com/a/other.jar.sha1','incoming/com/a/LIB.jar.sha1','incoming/com/a/lib.jar.asc.sha1'
  ]);
  assert.match(result.summary,/선택 9개 · 전송 6개/);
  assert.match(result.summary,/checksum 3개 자동 제외/);
  assert.match(result.message,/완료/);
});
test('RAW batch preserves body and all checksum and signature files',async()=>{
  const paths=['a.jar.sha1','a.jar','a.jar.md5','a.jar.sha256','a.jar.asc'];
  const result=await batch('raw',paths);
  assert.deepEqual(result.posted,paths.map(path=>'incoming/'+path));
  assert.match(result.summary,/선택 5개 · 전송 5개 · 자동 제외 없음/);
});
test('Maven filtered batch pause and resume reuse the body session and recheck confirmed bytes',async()=>{
  const chunk=8*1024*1024;
  let row,creates=0;
  const screen=page((url,options)=>{
    if(url.endsWith('/uploads') && options.method==='POST') {
      creates++;
      const data=JSON.parse(options.body);
      row={id:'resume-body',repositoryName:'repo',repositoryFormat:'maven2',targetPath:data.relativePath,
        totalBytes:data.totalBytes,receivedBytes:0,publishedBytes:0,status:'RECEIVING'};
      return Promise.resolve(ok(row));
    }
    return Promise.resolve(ok(url.endsWith('/uploads')?(row?[row]:[]):{
      ...binding('repo'),binding:{format:'maven2',repository:'repo',namespace:''}}));
  });
  screen.el('project').value='1';await screen.el('project').events.change();
  screen.el('files').files=[
    {name:'a.jar.sha1',size:40,slice:()=>({})},
    {name:'a.jar',size:chunk+3,slice:(a,b)=>({a,b})}
  ];
  const first=screen.el('start').events.click();await flush();
  assert.equal(row.targetPath,'a.jar');
  row.receivedBytes=chunk;
  screen.transfers[0].status=200;
  screen.transfers[0].responseText=JSON.stringify({success:true,data:row});
  screen.transfers[0].onload();await flush();
  assert.equal(screen.transfers[1].blob.a,chunk);
  screen.el('pause').events.click();await first;
  const resumed=screen.el('start').events.click();await flush();
  assert.equal(creates,1);
  assert.equal(screen.transfers[2].blob.a,0);
  assert.match(screen.transfers[2].url,/\/resume-body\/chunk/);
  assert.match(screen.el('selection-summary').textContent,/checksum 1개 자동 제외/);
  screen.el('pause').events.click();await resumed;
});
test('dependencyProjectId deep link selects an available project and loads its binding and upload history',async()=>{
  const requested=[];
  const screen=page(url=>{
    requested.push(url);
    return Promise.resolve(ok(url.endsWith('/uploads')?[]:binding('linked-repo')));
  },{search:'?dependencyProjectId=42',projectIds:['1','42']});
  await flush();
  assert.equal(screen.el('project').value,'42');
  assert.equal(screen.el('repository').value,'linked-repo');
  assert.deepEqual(requested,[
    '/api/v1/nexus/deploy-projects/42/dependencies',
    '/api/v1/nexus/deploy-projects/42/dependencies/uploads'
  ]);
});
test('Maven checksum repair is explicit, project scoped and hidden for RAW and sidecars',async()=>{
  let allowed=false;
  const calls=[];
  const base={repositoryName:'repo',repositoryFormat:'maven2',totalBytes:3,receivedBytes:3,publishedBytes:3,status:'COMPLETED'};
  const rows=[
    {...base,id:'jar',targetPath:'com/acme/lib/1/lib-1.jar'},
    {...base,id:'raw',repositoryFormat:'raw',targetPath:'a.bin'},
    {...base,id:'checksum',targetPath:'com/acme/lib/1/lib-1.jar.sha1'}
  ];
  const screen=page((url,options)=>{
    calls.push({url,method:options.method});
    return Promise.resolve(ok(url.endsWith('/uploads')?rows:binding('repo')));
  },{confirm:()=>allowed});
  screen.el('project').value='1';await screen.el('project').events.change();
  const buttons=screen.el('rows').querySelectorAll('button');
  assert.equal(buttons.length,1);
  assert.equal(buttons[0].textContent,'누락 checksum 등록 재개');
  await buttons[0].events.click();
  assert.equal(calls.filter(call=>call.method==='POST').length,0);
  allowed=true;await buttons[0].events.click();
  assert.deepEqual(calls.filter(call=>call.method==='POST'),[
    {url:'/api/v1/nexus/deploy-projects/1/dependencies/uploads/jar/checksums',method:'POST'}
  ]);
});
test('late project A response cannot replace project B binding and history',async()=>{
  const a=deferred();
  const screen=page(url=>url.endsWith('/uploads')?Promise.resolve(ok([])):url.includes('/1/')?a.promise:Promise.resolve(ok(binding('repo-b'))));
  screen.el('project').value='1';const first=screen.el('project').events.change();
  screen.el('project').value='2';await screen.el('project').events.change();
  a.resolve(ok(binding('repo-a')));await first;
  assert.equal(screen.el('repository').value,'repo-b');
});
test('failed stale request cannot overwrite message in the new project',async()=>{
  const a=deferred();
  const screen=page(url=>url.endsWith('/uploads')?Promise.resolve(ok([])):url.includes('/1/')?a.promise:Promise.resolve(ok(binding('repo-b'))));
  screen.el('project').value='1';const first=screen.el('project').events.change();
  screen.el('project').value='2';await screen.el('project').events.change();
  screen.el('message').textContent='새 프로젝트';
  a.reject(Error('old project unavailable'));await first;
  assert.equal(screen.el('message').textContent,'새 프로젝트');
});
test('late repository choices and binding-save response do not affect another project',async()=>{
  const choices=deferred(), save=deferred();
  const screen=page((url,options)=>options.method==='PUT'?save.promise:url.includes('/repositories?')?choices.promise:
    Promise.resolve(ok(url.endsWith('/uploads')?[]:binding(url.includes('/1/')?'repo-a':'repo-b'))));
  screen.el('project').value='1';await screen.el('project').events.change();
  const listing=screen.el('list').events.click(), saving=screen.el('save').events.click();
  screen.el('project').value='2';await screen.el('project').events.change();
  choices.resolve(ok([{name:'only-project-a'}]));save.resolve(ok({}));
  await Promise.all([listing,saving]);
  assert.equal(screen.el('repository').value,'repo-b');
  assert.equal(screen.el('repositories').children.length,0);
});
test('chunk upload displays live percentage before acknowledgement and pauses cleanly',async()=>{
  const row={id:'upload-a',repositoryName:'repo',repositoryFormat:'raw',targetPath:'a.bin',totalBytes:100,receivedBytes:0,publishedBytes:0,status:'RECEIVING'};
  const screen=page((url,options)=>Promise.resolve(ok(url.endsWith('/uploads')?(options.method==='POST'?row:[]):binding('repo'))));
  screen.el('project').value='1';await screen.el('project').events.change();
  screen.el('files').files=[{name:'a.bin',size:100,slice:(a,b)=>({a,b})}];
  const start=screen.el('start').events.click();await flush();
  assert.equal(screen.transfers.length,1);
  assert.equal(screen.el('project').disabled,true);
  screen.transfers[0].upload.onprogress({loaded:37});
  const cell=screen.el('rows').querySelector('[data-receive-id]');
  assert.equal(cell.querySelector('progress').value,37);
  assert.match(cell.querySelector('div').textContent,/37\.0%/);
  assert.match(screen.el('message').textContent,/37\.0%/);
  screen.el('pause').events.click();await start;
  assert.equal(screen.el('project').disabled,false);
});
