const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const test=require('node:test');
const template=fs.readFileSync(path.resolve(__dirname,'../../main/resources/templates/pages/server-map.html'),'utf8');
const topology=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/topology.js'),'utf8');
function slice(a,b){return template.slice(template.indexOf(a),template.indexOf(b,template.indexOf(a)));}
function render(projectId){
 const context=vm.createContext({window:{}});vm.runInContext(topology,context);
 const host={};context.window.DeployTopology.render(host,{build:[],environments:[{environment:'TEST',groups:[{servers:[{serverId:5,serverName:'shared-was',serverType:'WAS'}]}]}]},
 {servers:[{serverId:5,state:'BROKEN'}]},[{serverId:5,running:true,exists:true,statusKnown:true}],{projectId});return host.innerHTML;
}
function harness(){
 const requests=[],elements={},disabled=[];
 const cards=[1,6].map(projectId=>({projectId,arch:{environments:[{groups:[{servers:[{serverId:5}]}]}],collisions:[]},svc:[{serverId:5,controlSupported:true,statusKnown:true,logsSupported:false}],health:{servers:[]}}));
 const response=()=>Promise.resolve({json:()=>Promise.resolve({success:true,data:[]})});
 const context=vm.createContext({Promise,Array,String,encodeURIComponent,smCards:cards,
  smCard:pid=>cards.find(c=>String(c.projectId)===String(pid)),
  document:{getElementById:id=>elements[id]??=(id.startsWith('tp-srv-')?{querySelector:()=>({textContent:id})}:{style:{},classList:{toggle(){}},textContent:'',innerHTML:''}),
   querySelector:selector=>({querySelectorAll:()=>[{set disabled(value){disabled.push({selector,value});}}]})},
  csrfFetch:(url)=>{requests.push(url);return response();},fetch:url=>{requests.push(url);return response();},
  smExecOpen(){},smExecDone(){},showToast(){},smLoadServiceStatus(){},openConfirm:(title,text,go)=>go(),
  smTailStop(){},smTailStart(){},smSetNewBadge(){},smRenderDeployLog(){},smEsc:String,
  smTailServerId:null,smTailProjectId:null,smTailLines:[],smTailFirst:true,smFindQ:'',smFindIdx:0});
 vm.runInContext(slice('  function smTargetExists(', '  /* ══ 실행 모달'),context);
 vm.runInContext(slice('  function smShowLog(', '  function smTailStart()'),context);
 vm.runInContext(slice('  function smShowWarn(', '  function smCloseWarn()'),context);
 return{context,requests,disabled};
}
test('shared server nodes and all action/warning callbacks retain their project',()=>{
 const first=render(1),second=render(6);
 assert.match(first,/id="tp-srv-1-5"/);assert.match(second,/id="tp-srv-6-5"/);
 assert.match(first,/data-actions="1-5"/);assert.match(second,/data-actions="6-5"/);
 for(const action of ['start','stop','restart']){assert.ok(first.includes(`smControl(1,5,'${action}')`));assert.ok(second.includes(`smControl(6,5,'${action}')`));}
 assert.ok(first.includes('smShowLog(1,5)'));assert.ok(second.includes('smShowLog(6,5)'));
 assert.ok(first.includes('smShowWarn(1,[5])'));assert.ok(second.includes('smShowWarn(6,[5])'));
});
test('shared-server controls target the clicked project and only its action box',async()=>{
 const h=harness();h.context.smControl(1,5,'stop');h.context.smControl(6,5,'start');await new Promise(setImmediate);
 assert.deepEqual(h.requests,['/api/v1/operator/services/1/5/stop','/api/v1/operator/services/6/5/start']);
 assert.deepEqual(h.disabled.map(x=>x.selector),['[data-actions="1-5"]','[data-actions="6-5"]']);
});
test('log and multi-server warning requests use explicit project, never last shared owner',async()=>{
 const h=harness();h.context.smShowLog(1,5);h.context.smShowLog(6,5);h.context.smShowWarn(1,[5]);await new Promise(setImmediate);
 assert.deepEqual(h.requests,['/api/v1/operator/servers/5/logs?projectId=1&limit=20','/api/v1/operator/servers/5/logs?projectId=6&limit=20','/api/v1/operator/servers/5/logs?projectId=1&limit=20']);
});
test('unmapped project/server pair cannot issue control or log request',()=>{
 const h=harness();h.context.smControl(7,5,'stop');h.context.smShowLog(7,5);h.context.smShowWarn(7,[5]);h.context.smControl(1,99,'start');
 assert.equal(h.requests.length,0);
});
test('renderer without project context remains viewable and fails closed for actions',()=>{
 const html=render(undefined);assert.match(html,/id="tp-srv-5"/);
 assert.match(html,/disabled[^>]+onclick="smControl\(null,5,'stop'\)/);assert.ok(!html.includes('onclick="smShowWarn('));
 assert.match(html,/disabled onclick="smShowLog\(null,5\)/);
});
