const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const html=fs.readFileSync(path.resolve(__dirname,'../../main/resources/templates/pages/request-create.html'),'utf8');
const source=html.slice(html.indexOf('  function syncTargetAvailability()'),html.indexOf('  function syncRequestGroups()'));
function screen(strategy,groups) {
  const elements={
    'req-scope':{value:'WAS_ONLY'},'req-strategy':{value:strategy},'req-submit':{},
    'req-target-warning':{},'req-target-settings':{},'req-strategy-warning':{},'req-group-approval-hint':{}
  };
  const sandbox={projectSelect:{value:'1',selectedOptions:[{dataset:{}}]},envSelect:{value:'PROD'},
    effectiveRequestTargetCount:()=>2,syncRequestGroups(){},
    document:{getElementById:id=>elements[id],querySelectorAll:()=>groups}};
  vm.createContext(sandbox);vm.runInContext(source,sandbox);sandbox.syncTargetAvailability();
  return elements;
}
const group=(policy,disabled=false)=>({disabled,dataset:{was:'2',web:'2',failurePolicy:policy}});
test('manual rollback selection disables submission and explains unsupported policy',()=>{
  const elements=screen('MANUAL',[group('ROLLBACK')]);
  assert.equal(elements['req-submit'].disabled,true);
  assert.equal(elements['req-strategy-warning'].hidden,false);
});
test('automatic rollback and manual stop remain selectable',()=>{
  for(const [strategy,policy] of [['SAFE','ROLLBACK'],['WAVE','ROLLBACK'],['MANUAL','STOP']]) {
    const elements=screen(strategy,[group(policy)]);
    assert.equal(elements['req-submit'].disabled,false);
    assert.equal(elements['req-strategy-warning'].hidden,true);
  }
});
test('hidden groups from another project or environment do not block manual stop',()=>{
  const elements=screen('MANUAL',[group('STOP'),group('ROLLBACK',true)]);
  assert.equal(elements['req-submit'].disabled,false);
});
test('additional approval hint follows only selected enabled groups without disabling automatic deployment',()=>{
  const approvalGroup=group('STOP');
  approvalGroup.dataset.additionalApproval='true';
  const selected=screen('SAFE',[approvalGroup]);
  assert.equal(selected['req-group-approval-hint'].hidden,false);
  assert.equal(selected['req-submit'].disabled,false);
  approvalGroup.disabled=true;
  assert.equal(screen('SAFE',[group('STOP'),approvalGroup])['req-group-approval-hint'].hidden,true);
  assert.equal(screen('SAFE',[group('STOP')])['req-group-approval-hint'].hidden,true);
});
