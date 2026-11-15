const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/nginx-config-editor.js','utf8');
function fixture() {
  const nodes = {}, confirmations = [], notices = [], pending = [];
  const node = (id,value='') => nodes[id] = {value,textContent:'',isConnected:true,disabled:false,
    selectionStart:0,selectionEnd:0,style:{},dataset:{},focus(){this.focused=true;},
    setSelectionRange(a,b){this.selectionStart=a;this.selectionEnd=b;},setCustomValidity(message){this.validation=message;}};
  const editor = node('nginx-config-editor'); node('nginx-editor-status');
  node('ng-server','1');node('ng-policy','portal');node('nginx-config-feature','websocket');
  node('nginx-current-editor');node('nginx-current-status');node('nginx-current-sha');node('nginx-current-config');
  node('nginx-config-viewer-modal');node('nginx-config-viewer-title');node('nginx-request-config');node('nginx-request-config-sha');node('nginx-request-config-status');
  const form = node('nginx-policy-form');form.elements={};form.reportValidity=()=>true;
  form.listeners={};form.addEventListener=(type,handler)=>{form.listeners[type]=handler;};
  form.elements.policyName=nodes['ng-policy'];
  form.elements.policyType=node('ng-policy-type','REVERSE_PROXY');
  form.elements.listenPort=node('ng-listen','443');
  for(const name of ['tlsEnabled','tls12','tls13']){form.elements[name]=node(name);form.elements[name].checked=false;}
  const ctx={document:{getElementById:id=>nodes[id]||null,querySelector:()=>null},TextEncoder,
    showToast:(...args)=>notices.push(args),openConfirm:(title,body,action)=>confirmations.push(action),
    nginxPolicyFormData:()=>{const data=new FormData();for(const [name,input] of Object.entries(form.elements))data.set(name,typeof input.checked==='boolean'?String(input.checked):input.value);return data;},nginxCsrfHeaders:()=>({'X-CSRF-TOKEN':'qa'}),
    nginxResult:async response=>{if(response.error)throw new Error(response.error);return response;},
    fetch:(url,options)=>new Promise(resolve=>pending.push({url,options,resolve}))};
  ctx.window=ctx;vm.createContext(ctx);vm.runInContext(source,ctx);
  return {ctx,nodes,editor,form,confirmations,notices,pending,node};
}
const result = data => ({success:true,data});

test('required dynamic WAS select focuses its visible control and blocks preview without clearing the draft',async()=>{
  const f=fixture();f.editor.value='server { # keep this draft\n}';
  const details={open:false},attrs={},button={focus(){this.focused=true;},setAttribute(k,v){attrs[k]=v;},removeAttribute(k){delete attrs[k];}};
  // Added after script initialization, like an upstream row cloned by the form.
  const select={_cs:{btn:button},validity:{valid:false},closest:()=>details,getAttribute:()=> 'WAS 서버'};
  f.form.querySelector=()=>select;
  let prevented=false;
  f.form.reportValidity=()=>{
    assert.equal(f.editor.disabled,true);
    f.form.listeners.invalid({target:select,preventDefault(){prevented=true;}});
    return false;
  };
  await f.ctx.generateNginxEditorTemplate();
  assert.equal(prevented,true);assert.equal(details.open,true);assert.equal(button.focused,true);
  assert.equal(attrs['aria-invalid'],'true');assert.match(f.notices.at(-1)[0],/WAS 서버.*선택/);
  assert.equal(f.pending.length,0);assert.equal(f.editor.disabled,false);
  assert.equal(f.editor.value,'server { # keep this draft\n}');
  select.validity.valid=true;f.form.listeners.change({target:select});
  assert.equal(attrs['aria-invalid'],undefined);
});

test('form validation preserves native fields and focuses only the first invalid custom field',()=>{
  const f=fixture(),details={open:false};
  const first={closest:()=>details};f.form.querySelector=()=>first;
  let canceled=false,focused=false;
  f.form.listeners.invalid({target:first,preventDefault(){canceled=true;}});
  assert.equal(canceled,false);assert.equal(details.open,true);
  const later={closest:()=>details,_cs:{btn:{setAttribute(){},focus(){focused=true;}}}};
  f.form.listeners.invalid({target:later,preventDefault(){canceled=true;}});
  assert.equal(canceled,true);assert.equal(focused,false);assert.equal(f.notices.length,0);
});

test('template validation restores editor state even if browser validation throws',async()=>{
  const f=fixture();f.form.reportValidity=()=>{throw new Error('validation error');};
  await assert.rejects(f.ctx.generateNginxEditorTemplate(),/validation error/);
  assert.equal(f.editor.disabled,false);assert.equal(f.pending.length,0);
});

test('template generation cannot silently replace a draft or overwrite edits made while waiting',async()=>{
  const f=fixture();f.editor.value='server { # handwritten\n}\n';
  const first=f.ctx.generateNginxEditorTemplate();
  assert.equal(f.pending[0].url,'/api/v1/nginx-changes/preview');
  f.pending[0].resolve(result({config:'server {\n listen 443 ssl;\n}\n'}));await first;
  assert.equal(f.editor.value,'server { # handwritten\n}\n');assert.equal(f.confirmations.length,1);
  f.editor.value+='\n# newer edit';f.confirmations[0]();
  assert.match(f.editor.value,/# newer edit/);assert.match(f.notices[0][0],/유지/);
  const next=f.ctx.generateNginxEditorTemplate();f.pending[1].resolve(result({config:'server {\n listen 80;\n}\n'}));await next;
  f.confirmations[1]();assert.equal(f.editor.value,'server {\n listen 80;\n}\n');
});

test('template responses from another page or a changed server identity are discarded',async()=>{
  const f=fixture();const request=f.ctx.generateNginxEditorTemplate();
  f.nodes['ng-server'].value='2';f.pending[0].resolve(result({config:'server {}'}));await request;
  assert.equal(f.editor.value,'');assert.equal(f.confirmations.length,0);
  const leaving=f.ctx.generateNginxEditorTemplate();f.form.isConnected=false;
  f.pending[1].resolve(result({config:'server {}'}));await leaving;assert.equal(f.editor.value,'');
});

test('typed listen or TLS changes during preview discard the old generated content',async()=>{
  const f=fixture();f.editor.value='server {\n# keep me\n}\n';
  const request=f.ctx.generateNginxEditorTemplate();f.form.elements.listenPort.value='9443';
  f.pending[0].resolve(result({config:'server { listen 443 ssl; }'}));await request;
  assert.match(f.editor.value,/# keep me/);assert.equal(f.confirmations.length,0);assert.match(f.notices.at(-1)[0],/입력값/);
});

test('changing typed values while the template replacement confirmation is open preserves the draft',async()=>{
  const f=fixture();f.editor.value='server {\n# original handwritten\n}\n';
  const request=f.ctx.generateNginxEditorTemplate();f.pending[0].resolve(result({config:'server { listen 443 ssl; }'}));await request;
  assert.equal(f.confirmations.length,1);f.form.elements.listenPort.value='9443';f.confirmations[0]();
  assert.equal(f.editor.value,'server {\n# original handwritten\n}\n');assert.match(f.notices.at(-1)[0],/입력값/);
});

test('reading current configuration never edits the draft; copying requires confirmation and same policy',async()=>{
  const f=fixture();f.editor.value='server {\n# preserve my draft\n}\n';
  const current=f.ctx.loadCurrentNginxConfiguration();
  assert.match(f.pending[0].url,/servers\/1\/configuration\?policyName=portal$/);
  f.pending[0].resolve(result({exists:true,config:'server {\n# actual\n}\n',sha256:'abc'}));await current;
  assert.match(f.editor.value,/preserve my draft/);assert.match(f.nodes['nginx-current-editor'].value,/# actual/);
  f.ctx.useCurrentNginxConfiguration();assert.equal(f.confirmations.length,1);
  f.confirmations[0]();assert.equal(f.editor.value,'server {\n# actual\n}\n');
  f.nodes['ng-policy'].value='other';f.ctx.useCurrentNginxConfiguration();assert.equal(f.confirmations.length,1);
});

test('UTF-8 byte limit is checked without trimming or rewriting submitted text',()=>{
  const f=fixture();f.editor.value='가'.repeat(20480);
  assert.equal(f.ctx.validateNginxEditorDraft(),true);assert.equal(f.editor.value.length,20480);
  f.editor.value+='가';assert.equal(f.ctx.validateNginxEditorDraft(),false);assert.match(f.editor.validation,/60KB/);
  f.editor.value='\n  server {\n  }\n\n';assert.equal(f.ctx.validateNginxEditorDraft(),true);assert.equal(f.editor.value,'\n  server {\n  }\n\n');
});

test('feature insertion preserves handwritten configuration and skips braces inside strings and comments',()=>{
  const f=fixture();const original='# server { comment }\nserver {\n  set $v "}";\n  location / { return 200; }\n}\n';
  f.editor.value=original;f.ctx.insertNginxEditorFeature();
  assert.match(f.editor.value,/set \$v "}";/);assert.match(f.editor.value,/proxy_set_header Upgrade \$http_upgrade;/);
  assert.ok(f.editor.value.indexOf('# WebSocket')>f.editor.value.indexOf('return 200;'));
  assert.ok(f.editor.value.endsWith('}\n'));
  assert.equal(f.editor.value.replace(/  # WebSocket\n  proxy_http_version 1\.1;\n  proxy_set_header Upgrade \$http_upgrade;\n  proxy_set_header Connection "upgrade";\n/,''),original);
});

test('feature replaces only an explicit selection and does not invent a missing server block',()=>{
  const f=fixture();f.nodes['nginx-config-feature'].value='upload';f.form.elements.clientMaxBodyMb={value:'75'};
  f.editor.value='server {\n  client_max_body_size 1m;\n}\n';
  f.editor.selectionStart=f.editor.value.indexOf('  client');f.editor.selectionEnd=f.editor.value.indexOf('}\n');
  f.ctx.insertNginxEditorFeature();assert.equal(f.editor.value,'server {\n  client_max_body_size 75m;\n}\n');
  f.editor.value='# no server yet';f.editor.selectionStart=f.editor.selectionEnd=0;
  f.ctx.insertNginxEditorFeature();assert.equal(f.editor.value,'# no server yet');
});

test('WebSocket goes into the application proxy location rather than an HTTP redirect server, without duplicating HTTP version',()=>{
  const f=fixture();
  f.editor.value='server { listen 80; return 301 https://$host$request_uri; }\nserver {\n listen 443 ssl;\n location / {\n proxy_pass http://app;\n proxy_http_version  1.1;\n proxy_set_header Host $host;\n }\n}\n';
  f.ctx.insertNginxEditorFeature();
  assert.equal((f.editor.value.match(/proxy_http_version/g)||[]).length,1);
  assert.ok(f.editor.value.indexOf('# WebSocket')>f.editor.value.indexOf('proxy_set_header Host'));
  assert.ok(f.editor.value.indexOf('# WebSocket')<f.editor.value.indexOf('}',f.editor.value.indexOf('proxy_set_header Host')));
  const once=f.editor.value;f.editor.selectionStart=f.editor.selectionEnd=0;
  f.ctx.insertNginxEditorFeature();assert.equal(f.editor.value,once);assert.match(f.notices.at(-1)[0],/이미/);
});

test('proxy feature reuses canonical multi-WAS preview and preserves unrelated handwritten lines',async()=>{
  const f=fixture();f.nodes['nginx-config-feature'].value='reverse-proxy';
  f.editor.value='server {\n listen 8080;\n # handwritten keep\n gzip on;\n location / { return 200; }\n}\n';
  const canonical='upstream deploy_policy_portal {\n server 198.18.8.1:8080;\n server 198.18.8.2:8080;\n}\nserver {\n listen 8080;\n location / {\n proxy_pass http://deploy_policy_portal;\n proxy_set_header Host $deploy_request_host;\n proxy_set_header X-Forwarded-For $remote_addr;\n proxy_set_header Forwarded "";\n proxy_set_header X-Forwarded-Port "";\n proxy_set_header X-Forwarded-Prefix "";\n proxy_set_header X-Forwarded-Ssl "";\n }\n}\n';
  const request=f.ctx.insertNginxEditorFeature();f.pending[0].resolve(result({config:canonical}));await request;
  assert.match(f.editor.value,/return 200/);f.confirmations[0]();
  assert.match(f.editor.value,/handwritten keep/);assert.match(f.editor.value,/gzip on/);
  assert.match(f.editor.value,/198\.18\.8\.1:8080/);assert.match(f.editor.value,/198\.18\.8\.2:8080/);
  assert.match(f.editor.value,/set \$deploy_request_host \$http_host/);
  assert.match(f.editor.value,/X-Forwarded-For \$remote_addr/);assert.doesNotMatch(f.editor.value,/proxy_add_x_forwarded_for/);
  assert.equal((f.editor.value.match(/location \/ \{/g)||[]).length,1);assert.doesNotMatch(f.editor.value,/return 200/);
});

test('TLS feature synchronizes checkbox metadata and SSL listen while preserving custom configuration',async()=>{
  const f=fixture();f.nodes['nginx-config-feature'].value='tls';
  f.editor.value='server {\n listen 8080;\n # my custom line\n gzip on;\n location / { return 200; }\n}\n';
  const canonical='server { listen 80; server_name portal; return 301 https://$host$request_uri; }\nserver {\n listen 443 ssl;\n ssl_certificate /etc/nginx/tls/portal/current/fullchain.pem;\n ssl_certificate_key /etc/nginx/tls/portal/current/privkey.pem;\n ssl_protocols TLSv1.2 TLSv1.3;\n location / { return 200; }\n}\n';
  const request=f.ctx.insertNginxEditorFeature();assert.equal(f.form.elements.tlsEnabled.checked,false);
  f.pending[0].resolve(result({config:canonical}));await request;f.confirmations[0]();assert.equal(f.form.elements.tlsEnabled.checked,true);
  assert.match(f.editor.value,/listen 443 ssl/);assert.doesNotMatch(f.editor.value,/listen 8080/);
  assert.match(f.editor.value,/my custom line/);assert.match(f.editor.value,/ssl_certificate_key \/etc\/nginx\/tls\/portal\/current\/privkey.pem/);
  assert.match(f.editor.value,/return 301/);
});

test('static feature replaces only the root location, removes obsolete managed upstream and synchronizes service type',async()=>{
  const f=fixture();f.nodes['nginx-config-feature'].value='static-spa';
  f.editor.value='upstream deploy_policy_portal { server 198.18.8.1:80; }\nserver {\n listen 8080;\n # preserve header\n add_header X-Test yes;\n location / { proxy_pass http://deploy_policy_portal; }\n}\n';
  const request=f.ctx.insertNginxEditorFeature();assert.equal(f.form.elements.policyType.value,'REVERSE_PROXY');
  f.pending[0].resolve(result({config:'server {\n listen 8080;\n location / {\n root /var/www/portal/current;\n try_files $uri $uri/ /index.html;\n }\n}\n'}));await request;f.confirmations[0]();
  assert.equal(f.form.elements.policyType.value,'STATIC_SPA');assert.match(f.editor.value,/preserve header/);assert.match(f.editor.value,/try_files \$uri \$uri\/ \/index.html/);
  assert.doesNotMatch(f.editor.value,/upstream deploy_policy_portal|proxy_pass/);
});

test('approval view assigns stored text as text and ignores responses from older requests',async()=>{
  const f=fixture();const first=f.ctx.showNginxRequestConfig(1),second=f.ctx.showNginxRequestConfig(2);
  const payload='<script>alert(1)</script>\nserver {}';
  f.pending[1].resolve(result({config:payload,sha256:'second'}));await second;
  f.pending[0].resolve(result({config:'stale',sha256:'first'}));await first;
  assert.equal(f.nodes['nginx-request-config'].value,payload);assert.match(f.nodes['nginx-request-config-sha'].textContent,/second/);
  f.ctx.closeNginxRequestConfig();assert.equal(f.nodes['nginx-config-viewer-modal'].style.display,'none');
});

test('failed current read clears the old snapshot so it cannot be copied into a different draft',async()=>{
  const f=fixture();let load=f.ctx.loadCurrentNginxConfiguration();f.pending[0].resolve(result({exists:true,config:'server {}',sha256:'old'}));await load;
  load=f.ctx.loadCurrentNginxConfiguration();f.pending[1].resolve({error:'unreachable'});await load;
  f.ctx.useCurrentNginxConfiguration();assert.equal(f.editor.value,'');assert.equal(f.nodes['nginx-current-editor'].value,'');
});

test('server status distinguishes missing, stopped, failed and unknown from a real inspect result',()=>{
  const html=fs.readFileSync('src/main/resources/templates/pages/server-groups.html','utf8');
  const code=html.slice(html.indexOf('  function groupNginxStatus('),html.indexOf('  async function inspectGroupNginx('));
  const ctx=vm.createContext({});vm.runInContext(code,ctx);
  assert.equal(ctx.groupNginxStatus(''),'진단 결과 미확인');
  assert.equal(ctx.groupNginxStatus('NGINX_INSTALLED=false'),'미설치');
  assert.equal(ctx.groupNginxStatus('NGINX_INSTALLED=true\nNGINX_ACTIVE=inactive'),'설치됨 · 중지');
  assert.equal(ctx.groupNginxStatus('NGINX_INSTALLED=true\nNGINX_ACTIVE=active'),'설치됨 · 실행 중');
  assert.equal(ctx.groupNginxStatus('NGINX_INSTALLED=true\nNGINX_ACTIVE=failed'),'설치됨 · 실행 실패');
});


test('current edit hydrates exact completed policy fields and VIP only when copy is confirmed',async()=>{
  const f=fixture();f.editor.value='server { # working draft\n}';
  for(const name of ['serverName','webRoot','apiPrefix'])f.form.elements[name]=f.node(name,'');
  f.form.elements.apiProxyEnabled=f.node('apiProxyEnabled');f.form.elements.apiProxyEnabled.checked=false;
  const vip=f.node('ng-was-vip','');
  const settings={policyType:'STATIC_SPA',serverName:'multi.qa.local',listenPort:18093,
    webRoot:'/var/www/qa-multi-c/current',apiPrefix:'/api/',apiProxyEnabled:true,tlsEnabled:false,
    wasVipSelection:'3:2',upstreams:[],upstreamsResolved:true,rawConfig:'must not replace raw',serverId:99};
  const read=f.ctx.loadCurrentNginxConfiguration();f.pending[0].resolve(result({exists:true,config:'server { # live\n}',editorSettings:settings}));await read;
  assert.equal(f.form.elements.policyType.value,'REVERSE_PROXY');
  f.ctx.useCurrentNginxConfiguration();assert.equal(f.form.elements.listenPort.value,'443');
  f.confirmations[0]();assert.equal(f.editor.value,'server { # live\n}');
  assert.equal(f.form.elements.policyType.value,'STATIC_SPA');assert.equal(f.form.elements.serverName.value,'multi.qa.local');
  assert.equal(f.form.elements.listenPort.value,'18093');assert.equal(f.form.elements.apiProxyEnabled.checked,true);
  assert.equal(f.form.elements.tlsEnabled.checked,false);assert.equal(vip.value,'3:2');assert.equal(f.nodes['ng-server'].value,'1');
});

test('metadata or draft edits during copy confirmation cancel both halves without losing user changes',async()=>{
  for(const change of ['metadata','draft']) {
    const f=fixture();f.editor.value='server { # working\n}';
    const read=f.ctx.loadCurrentNginxConfiguration();f.pending[0].resolve(result({exists:true,config:'server { # live\n}',editorSettings:{listenPort:18093}}));await read;
    f.ctx.useCurrentNginxConfiguration();
    if(change==='metadata')f.form.elements.listenPort.value='9000';else f.editor.value+='\n# later';
    f.confirmations[0]();assert.match(f.editor.value,/# working/);assert.notEqual(f.form.elements.listenPort.value,'18093');
    if(change==='draft')assert.match(f.editor.value,/# later/);
  }
});

test('unmatched metadata copies raw explicitly but warns and preserves manually entered form fields',async()=>{
  const f=fixture();f.form.elements.listenPort.value='9001';
  const read=f.ctx.loadCurrentNginxConfiguration();f.pending[0].resolve(result({exists:true,config:'server { listen 9001; }',editorSettings:null}));await read;
  f.ctx.useCurrentNginxConfiguration();assert.equal(f.editor.value,'server { listen 9001; }');
  assert.equal(f.form.elements.listenPort.value,'9001');assert.match(f.notices.at(-1)[0],/직접 확인/);
});


test('hydration synchronizes visible custom select labels and disabled state after policy mode changes',async()=>{
  const f=fixture(), policy=f.form.elements.policyType, vip=f.node('ng-was-vip','');
  policy._cs={btn:{disabled:false},label:'REVERSE_PROXY'};vip._cs={btn:{disabled:false},label:'old VIP'};
  f.form.querySelectorAll=selector=>selector==='select'?[policy,vip]:[];
  f.ctx.DeploySelect={sync:select=>{select._cs.label=select.value;}};
  f.ctx.toggleNginxPolicyType=()=>{vip.disabled=policy.value==='STATIC_FILES';};
  const read=f.ctx.loadCurrentNginxConfiguration();f.pending[0].resolve(result({exists:true,config:'server {}',
    editorSettings:{policyType:'STATIC_FILES',wasVipSelection:'',upstreamsResolved:true}}));await read;
  f.ctx.useCurrentNginxConfiguration();
  assert.equal(policy._cs.label,'STATIC_FILES');assert.equal(vip._cs.label,'');assert.equal(vip._cs.btn.disabled,true);
});
