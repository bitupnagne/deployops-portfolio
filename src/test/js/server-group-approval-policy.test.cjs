const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const test=require('node:test');
const vm=require('node:vm');
const html=fs.readFileSync(path.resolve(__dirname,'../../main/resources/templates/pages/server-groups.html'),'utf8');
const script=html.slice(html.indexOf('  function openGroupModal(d)'),html.indexOf('  function deleteGroup('));
function setup(readonly){
 const elements={};const changes=[];const requests=[];
 function element(id){if(id==='gm-approval-policy-readonly'&&!readonly)return null;
 return elements[id]??={value:'',style:{},textContent:'',dispatchEvent(){changes.push(id);}};}
 const context=vm.createContext({document:{getElementById:element},Event:class{},sgHeaders:{},showToast(){},setTimeout(){},navigate(){},fetch:async(url,options)=>{requests.push({url,body:JSON.parse(options.body)});return {json:async()=>({success:true})};}});
 vm.runInContext(script,context);
 return {context,element,changes,requests};
}
const assigned={id:'8',name:'그룹',env:'TEST',interval:'0',minAvailable:'1',observation:'30',failurePolicy:'STOP',approvalPolicyId:'4',approvalPolicyName:'추가 승인'};
test('operator ordinary edits retain hidden approval policy id and show its name',async()=>{
 const {context,element,requests}=setup(true);
 context.openGroupModalFrom({closest:()=>({dataset:assigned})});
 assert.equal(element('gm-approval-policy').value,'4');
 assert.match(element('gm-approval-policy-readonly').textContent,/추가 승인/);
 element('gm-name').value='일반 설정 수정';context.submitGroup();await new Promise(setImmediate);
 assert.equal(requests[0].body.approvalPolicyId,4);
});
test('new group after assigned edit resets retained id without inheriting another group policy',async()=>{
 const {context,element,requests}=setup(true);
 context.openGroupModal(assigned);context.openGroupModal();element('gm-name').value='새 그룹';
 assert.equal(element('gm-approval-policy').value,'');assert.match(element('gm-approval-policy-readonly').textContent,/추가 그룹 승인 없음/);
 context.submitGroup();await new Promise(setImmediate);assert.equal(requests[0].body.approvalPolicyId,null);
});
test('admin selector mirrors existing choice and explicitly clearing sends null',async()=>{
 const {context,element,changes,requests}=setup(false);
 context.openGroupModal(assigned);assert.ok(changes.includes('gm-approval-policy'));
 element('gm-approval-policy').value='';context.submitGroup();await new Promise(setImmediate);
 assert.equal(requests[0].body.approvalPolicyId,null);
});

test('existing group VIP survives modal edits and explicit disable is sent separately from approval policy',async()=>{
 const {context,element,requests}=setup(true);
 context.openGroupModal({...assigned,wasVipEnabled:'true',wasVipAddress:'198.18.12.100'});
 assert.equal(element('gm-was-vip-enabled').checked,true);assert.equal(element('gm-was-vip-address').disabled,false);
 context.submitGroup();await new Promise(setImmediate);assert.equal(requests[0].body.wasVipAddress,'198.18.12.100');assert.equal(requests[0].body.wasVipEnabled,true);
 element('gm-was-vip-enabled').checked=false;context.toggleGroupWasVip();context.submitGroup();await new Promise(setImmediate);
 assert.equal(requests[1].body.wasVipEnabled,false);assert.equal(requests[1].body.approvalPolicyId,4);
 context.openGroupModal();assert.equal(element('gm-was-vip-enabled').checked,false);assert.equal(element('gm-was-vip-address').value,'');
});
