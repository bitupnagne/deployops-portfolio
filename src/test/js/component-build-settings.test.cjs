const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const start = html.indexOf('  function openComponentModal(');
const end = html.indexOf('  function deleteComponent(', start);
function screen() {
  const ids = [...html.matchAll(/id="(cmp-[a-z-]+|component-modal(?:-title)?)"/g)].map(m => m[1]);
  const fields = Object.fromEntries(ids.map(id => [id, {value: '', checked: false, style: {}, disabled: false}]));
  const calls = [], messages = [];
  const context = vm.createContext({
    window: {}, document: {getElementById: id => fields[id], querySelector: () => ({content:'csrf'})},
    componentProjectId: () => 7, showToast: text => messages.push(text), navigate() {},
    fetch: async (url, opts) => {calls.push({url,body:JSON.parse(opts.body)});return {json:async()=>({success:true})};}
  });
  vm.runInContext(html.slice(start,end),context);
  return {context,fields,calls,messages};
}
test('editor hydrates persisted build fields and submits them with the component, not project settings', () => {
  const {context,calls}=screen();
  context.openComponentModal({dataset:{id:'21',code:'web',role:'WEB',type:'STATIC_BUNDLE',
    image:'registry/c/web',webroot:'/var/www/c',buildTool:'STATIC',dockerfile:'Dockerfile.web',
    buildContext:'.',buildCommand:'mkdir -p web/dist',artifactPath:'web/dist/'}});
  context.saveComponent();
  assert.equal(calls[0].url,'/api/v1/projects/7/components/21');
  assert.equal(calls[0].body.buildTool,'STATIC');
  assert.equal(calls[0].body.dockerfilePath,'Dockerfile.web');
  assert.equal(calls[0].body.dockerContextPath,'.');
  assert.equal(calls[0].body.buildCommand,'mkdir -p web/dist');
  assert.equal(calls[0].body.artifactPath,'web/dist/');
});
test('opening new component after edit does not inherit another component build command', () => {
  const {context,fields}=screen();
  context.openComponentModal({dataset:{id:'21',code:'api',buildCommand:'private prior build',dockerfile:'old'}});
  context.openComponentModal();
  for(const id of ['cmp-build-tool','cmp-dockerfile','cmp-build-context','cmp-build-command','cmp-artifact-path']) {
    assert.equal(fields[id].value,'',id);
  }
});
test('invalid relative path is blocked before any UI save request', () => {
  const {context,calls,messages}=screen();
  context.openComponentModal({dataset:{id:'21',code:'api',role:'WAS',type:'CONTAINER',dockerfile:'../../Dockerfile'}});
  context.saveComponent();
  assert.equal(calls.length,0);
  assert.match(messages[0],/상대 경로/);
});
test('clearing inherited fields emits explicit null while artifacts permit relative globs', () => {
  const {context,fields,calls}=screen();
  context.openComponentModal({dataset:{id:'21',code:'api',role:'WAS',type:'CONTAINER',dockerfile:'old'}});
  fields['cmp-dockerfile'].value='';
  fields['cmp-artifact-path'].value='api/target/[ab]*.jar';
  context.saveComponent();
  assert.equal(calls[0].body.dockerfilePath,null);
  assert.equal(calls[0].body.artifactPath,'api/target/[ab]*.jar');
});
