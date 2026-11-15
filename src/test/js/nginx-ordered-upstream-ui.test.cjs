const assert=require('node:assert/strict');
const test=require('node:test');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../../main/resources/templates/pages/nginx-policies.html'),'utf8');
const code=html.slice(html.indexOf('  function syncNginxUpstreams()'),html.indexOf('  function approveNginx(id)'));
function fixture(){
 const root={children:[],appendChild(r){r.parentNode=this;this.children.push(r);},insertBefore(row,other){this.children.splice(this.children.indexOf(row),1);this.children.splice(this.children.indexOf(other),0,row);}};
 function row(){const r={server:{value:'',disabled:false,options:[{value:''},{value:'2',dataset:{environment:'TEST'}},{value:'99',dataset:{environment:'PROD'}}],selectedOptions:[{disabled:false}]} ,port:{value:'8080',disabled:false},parentNode:null,querySelector(q){return q.includes('server')?this.server:this.port;},remove(){root.children.splice(root.children.indexOf(this),1);}};Object.defineProperties(r,{previousElementSibling:{get:()=>root.children[root.children.indexOf(r)-1]},nextElementSibling:{get:()=>root.children[root.children.indexOf(r)+1]}});return r;}
 const type={value:'REVERSE_PROXY'},api={checked:false},vip={value:'',disabled:false};let sent=null,toasts=0;
 const flags=Object.fromEntries(['websocketEnabled','proxyBuffering','securityHeaders','tlsEnabled','tls12','tls13','http2Enabled','redirectHttp','hstsEnabled'].map(x=>[x,{checked:false}]));
 const form={elements:{policyType:type,apiProxyEnabled:api,...flags}};
 class FormData{constructor(){this.values=new Map([['upstreamHost','forbidden-old'],['secondaryUpstreamHost','old'],['apiUpstreamHost','old']]);root.children.forEach(r=>{if(!r.server.disabled){this.values.set(r.server.name,r.server.value);this.values.set(r.port.name,r.port.value);}});}set(k,v){this.values.set(k,v);}delete(k){this.values.delete(k);}get(k){return this.values.get(k);}}
 const document={getElementById(id){return {'ng-upstream-rows':root,'ng-upstream-row-template':{content:{cloneNode:row}},'ng-policy-type':type,'ng-was-vip':vip,'ng-api-proxy':api,'ng-server':{selectedOptions:[{dataset:{environment:'TEST'}}]}}[id];},querySelectorAll(q){return q.includes('legacy')?[]:root.children;}};
 const ctx={document,FormData,showToast(){toasts++;},nginxCsrfHeaders:()=>({}),nginxResult:x=>x,navigate:()=>{},fetch(u,o){sent=o.body;return Promise.resolve({success:true});}};vm.createContext(ctx);vm.runInContext(code,ctx);
 return {ctx,root,type,api,vip,form,get sent(){return sent;},get toasts(){return toasts;},button(r){return {closest:()=>r};}};
}
test('actual functions add/reorder/delete reindex all three multipart fields and omit legacy',()=>{const f=fixture();for(let i=0;i<3;i++){f.ctx.addNginxUpstream();f.root.children[i].server.value=String(i+2);}f.ctx.moveNginxUpstream(f.button(f.root.children[2]),-1);assert.deepEqual(f.root.children.map(r=>r.server.value),['2','4','3']);f.ctx.submitNginxPolicy({preventDefault(){},currentTarget:f.form});assert.equal(f.sent.get('upstreams[1].serverId'),'4');assert.equal(f.sent.get('upstreams[2].port'),'8080');assert.equal(f.sent.get('upstreamListPresent'),'true');for(const n of ['upstreamHost','secondaryUpstreamHost','apiUpstreamHost'])assert.equal(f.sent.get(n),undefined);f.ctx.removeNginxUpstream(f.button(f.root.children[0]));assert.equal(f.root.children[0].server.name,'upstreams[0].serverId');});
test('static-only disables and omits rows; static API enables exact list',()=>{const f=fixture();f.ctx.addNginxUpstream();f.type.value='STATIC_SPA';f.ctx.syncNginxUpstreams();assert.equal(f.root.children[0].server.disabled,true);f.ctx.submitNginxPolicy({preventDefault(){},currentTarget:f.form});assert.equal(f.sent.get('upstreamListPresent'),'false');assert.equal(f.sent.get('upstreams[0].serverId'),undefined);f.api.checked=true;f.ctx.syncNginxUpstreams();assert.equal(f.root.children[0].server.disabled,false);});
test('max32 explicit bound and empty list still marked for server rejection',()=>{const f=fixture();for(let i=0;i<33;i++)f.ctx.addNginxUpstream();assert.equal(f.root.children.length,32);assert.equal(f.toasts,1);for(const r of [...f.root.children])f.ctx.removeNginxUpstream(f.button(r));f.ctx.submitNginxPolicy({preventDefault(){},currentTarget:f.form});assert.equal(f.sent.get('upstreamListPresent'),'true');});
test('approver page with no editable form initializes safely',()=>{const ctx={document:{getElementById:()=>null},showToast:()=>{}};vm.createContext(ctx);vm.runInContext(code,ctx);assert.doesNotThrow(()=>ctx.addNginxUpstream());});

test('registered WAS options filter selected WEB environment',()=>{const f=fixture();f.ctx.addNginxUpstream();assert.equal(f.root.children[0].server.options[1].disabled,false);assert.equal(f.root.children[0].server.options[2].disabled,true);});

test('VIP selection disables actual individual rows and submits trusted IDs without literal endpoint',()=>{
 const f=fixture();f.ctx.addNginxUpstream();f.root.children[0].server.value='2';f.vip.value='7:8';
 f.ctx.submitNginxPolicy({preventDefault(){},currentTarget:f.form});
 assert.equal(f.root.children[0].server.disabled,true);assert.equal(f.sent.get('upstreamListPresent'),'false');
 assert.equal(f.sent.get('upstreams[0].serverId'),undefined);assert.equal(f.sent.get('wasVipProjectId'),'7');assert.equal(f.sent.get('wasVipGroupId'),'8');
 assert.equal(f.sent.get('upstreamHost'),undefined);assert.equal(f.sent.get('upstreamPort'),undefined);
 f.vip.value='';f.ctx.submitNginxPolicy({preventDefault(){},currentTarget:f.form});
 assert.equal(f.root.children[0].server.disabled,false);assert.equal(f.sent.get('upstreamListPresent'),'true');assert.equal(f.sent.get('wasVipProjectId'),undefined);
});
test('static-only form never leaks a previously selected VIP context',()=>{
 const f=fixture();f.vip.value='7:8';f.type.value='STATIC_FILES';f.ctx.submitNginxPolicy({preventDefault(){},currentTarget:f.form});
 assert.equal(f.vip.disabled,true);assert.equal(f.sent.get('wasVipProjectId'),undefined);assert.equal(f.sent.get('upstreamListPresent'),'false');
});
