const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/server-agent.js'),'utf8');
function fixture(reason,ack,recoverable) {
  const state={id:7,data:{busyTask:{id:'query7',readOnlyQueryRecoverable:recoverable}}};
  const calls=[];let callback;
  const context={state,active:state,host:{querySelector: selector=>selector.includes('reason')?{value:reason}:{checked:ack}},
    bind:(_,fn)=>callback=fn,endpoint:(_,suffix)=>'/api/v1/servers/7/agent'+suffix,
    api:async(...args)=>calls.push(args),refresh:async()=>{calls.push(['refresh']);}};
  const start=source.indexOf("    bind('[data-agent-query-button]'");
  const end=source.indexOf("    bind('[data-agent-resolve-button]'",start);
  vm.runInNewContext(source.slice(start,end),context);
  return {callback,calls};
}
test('query recovery requires explicit reason and acknowledgment',async()=>{
  for(const args of [['',true,true],['operator reviewed',false,true],['operator reviewed',true,false]]) {
    const f=fixture(...args);await assert.rejects(()=>f.callback({}));assert.equal(f.calls.length,0);
  }
});
test('only the selected read-only query is retired and UI does not claim backup success',async()=>{
  const f=fixture(' reviewed ',true,true),output={};await f.callback(output);
  assert.equal(f.calls[0][0],'/api/v1/servers/7/agent/tasks/query7/resume-query');
  assert.equal(f.calls[0][1],'POST');assert.equal(f.calls[0][2].reason,'reviewed');assert.equal(f.calls[0][2].acknowledged,true);
  assert.deepEqual(f.calls[1],['refresh']);assert.match(output.textContent,/성공 처리하거나 재실행하지 않았습니다/);
});
