const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const test=require('node:test');
const source=fs.readFileSync('src/main/resources/static/js/select.js','utf8');

function fixture(){
  const listeners={}, attrs={'aria-expanded':'true'}, child={nodeType:1}, page={nodeType:1}, removed=[];
  const list={nodeType:1,hidden:false,contains:node=>node===child,
    parentNode:{removeChild(node){removed.push(node);node.parentNode=null;}}};
  const api={list,btn:{setAttribute:(name,value)=>attrs[name]=value,removeAttribute:name=>delete attrs[name]},
    wrap:{classList:{remove(){}}}};
  const window={addEventListener:(type,callback)=>listeners[type]=callback};
  const document={readyState:'loading',addEventListener(){}};
  const context=vm.createContext({window,document,MutationObserver:class{observe(){}}});
  vm.runInContext(source.replace('window.DeploySelect = { scan: scan, sync: sync };',
    'window.DeploySelect = { scan: scan, sync: sync, setOpen: function(api) { openOne=api; } };'),context);
  window.DeploySelect.setOpen(api);
  return {listeners,list,child,page,window,attrs,removed};
}

test('mouse wheel and keyboard scrolling inside the popup preserve the list and current option DOM',()=>{
  const f=fixture();f.listeners.scroll({target:f.list});f.listeners.scroll({target:f.child});
  assert.equal(f.list.hidden,false);assert.equal(f.attrs['aria-expanded'],'true');assert.equal(f.removed.length,0);
});

test('scrolling a page or modal outside the list still closes the popup',()=>{
  const f=fixture();f.listeners.scroll({target:f.page});
  assert.equal(f.list.hidden,true);assert.equal(f.attrs['aria-expanded'],'false');assert.equal(f.removed.length,1);
});

test('window scroll remains safe and closes the popup; late scroll after close is harmless',()=>{
  const f=fixture();assert.doesNotThrow(()=>f.listeners.scroll({target:f.window}));
  assert.equal(f.list.hidden,true);assert.equal(f.removed.length,1);
  assert.doesNotThrow(()=>f.listeners.scroll({target:f.list}));assert.equal(f.removed.length,1);
});
