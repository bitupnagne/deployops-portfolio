const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const {harness,html}=require('./helpers/nginx-policy-fixture.cjs');
test('second registered WAS row is required only when proxy or static API is enabled',()=>{
 const h=harness();h.context.toggleNginxPolicyType();assert.equal(h.root.children.length,1);
 h.context.addNginxUpstream();
 for(const row of h.root.children)for(const control of [row.server,row.port]){assert.equal(control.disabled,false);assert.equal(control.required,true);}
 h.nodes['ng-policy-type'].value='STATIC_SPA';h.context.toggleNginxPolicyType();
 for(const row of h.root.children)for(const control of [row.server,row.port]){assert.equal(control.disabled,true);assert.equal(control.required,false);}
});
test('enabled form submits real ordered pair; deleting second omits its values and legacy hosts',()=>{
 const h=harness();h.context.addNginxUpstream();h.root.children[1].server.value='3';h.root.children[1].port.value='18080';h.submit();
 assert.equal(h.sent[0].upstreamListPresent,'true');assert.equal(h.sent[0]['upstreams[0].serverId'],'2');assert.equal(h.sent[0]['upstreams[1].serverId'],'3');assert.equal(h.sent[0]['upstreams[1].port'],'18080');
 h.context.removeNginxUpstream({closest:()=>h.root.children[1]});h.submit();
 assert.equal(h.sent[1]['upstreams[0].serverId'],'2');assert.equal(h.sent[1]['upstreams[1].serverId'],undefined);
 for(const name of ['upstreamHost','secondaryUpstreamEnabled','secondaryUpstreamHost','secondaryUpstreamPort','apiUpstreamHost'])assert.equal(h.sent[0][name],undefined);
});

function tlsHarness(enabled) {
 const h=harness();
 const elements={policyType:h.nodes['ng-policy-type'],apiProxyEnabled:h.nodes['ng-api-proxy']};
 for(const name of ['websocketEnabled','proxyBuffering','securityHeaders','tlsEnabled','tls12','tls13','http2Enabled','redirectHttp','hstsEnabled'])elements[name]={checked:true,disabled:false,value:'true'};
 elements.tlsEnabled.checked=enabled;
 elements.tls13.checked=false;
 h.nodes['ng-tls']=elements.tlsEnabled;h.nodes['ng-tls-files']={style:{}};
 h.nodes['ng-cert-name']=elements.certificateName={value:'public-test-cert',disabled:false};
 h.nodes['ng-cert']=elements.certificate={value:'public-test-cert.pem',files:[{name:'public-test-cert.pem'}],disabled:false};
 h.nodes['ng-key']=elements.privateKey={value:'public-test-key.pem',files:[{name:'public-test-key.pem'}],disabled:false};
 h.nodes['nginx-policy-form']={elements};return {...h,elements};
}
test('TLS off/on disables dependent controls while preserving preferences and selected files',()=>{
 const h=tlsHarness(true),names=['tls12','tls13','http2Enabled','redirectHttp','hstsEnabled'];
 h.context.toggleTlsFields();assert.equal(h.nodes['ng-tls-files'].style.display,'grid');
 const before=names.map(n=>h.elements[n].checked),files=h.nodes['ng-cert'].files;
 h.elements.tlsEnabled.checked=false;h.context.toggleTlsFields();
 assert.equal(h.nodes['ng-tls-files'].style.display,'none');
 for(const name of names)assert.equal(h.elements[name].disabled,true);
 for(const id of ['ng-cert-name','ng-cert','ng-key'])assert.equal(h.nodes[id].disabled,true);
 assert.deepEqual(names.map(n=>h.elements[n].checked),before);assert.equal(h.nodes['ng-cert'].files,files);assert.equal(h.elements.certificateName.value,'public-test-cert');
 h.elements.tlsEnabled.checked=true;h.context.toggleTlsFields();
 assert.equal(h.nodes['ng-tls-files'].style.display,'grid');
 for(const name of names)assert.equal(h.elements[name].disabled,false);
 for(const id of ['ng-cert-name','ng-cert','ng-key'])assert.equal(h.nodes[id].disabled,false);
 assert.deepEqual(names.map(n=>h.elements[n].checked),before);assert.equal(h.nodes['ng-cert'].files,files);
});
test('TLS-off submission omits disabled certificate fields and preserves API boolean preferences',async()=>{
 const h=tlsHarness(false);h.context.toggleNginxPolicyType();h.context.toggleTlsFields();
 const submit=()=>h.context.submitNginxPolicy({preventDefault(){},currentTarget:{elements:h.elements}});
 submit();assert.equal(h.sent.length,1);assert.equal(h.sent[0].tlsEnabled,'false');
 for(const name of ['certificateName','certificate','privateKey'])assert.equal(h.sent[0][name],undefined);
 assert.equal(h.sent[0].tls12,'true');assert.equal(h.sent[0].tls13,'false');assert.equal(h.sent[0].http2Enabled,'true');
 h.elements.tlsEnabled.checked=true;h.context.toggleTlsFields();submit();assert.equal(h.sent.length,2);
 assert.equal(h.sent[1].certificate,'public-test-cert.pem');assert.equal(h.sent[1].privateKey,'public-test-key.pem');assert.equal(h.sent[1].certificateName,'public-test-cert');assert.equal(h.sent[1].tlsEnabled,'true');
 await new Promise(r=>setImmediate(r));
});
test('actual page initialization applies TLS-disabled state without changing a restored preference',()=>{
 const h=tlsHarness(false);let otherCalls=0;
 h.context.loadNginxCertExpiry=()=>{otherCalls++;};h.context.toggleNginxPolicyType=()=>{otherCalls++;};
 const initializers=html.slice(html.lastIndexOf('  loadNginxCertExpiry();'),html.lastIndexOf('  </script>'));
 vm.runInContext(initializers,h.context);
 assert.equal(otherCalls,2);assert.equal(h.elements.tls12.disabled,true);assert.equal(h.elements.tls13.disabled,true);
 assert.equal(h.elements.tls12.checked,true);assert.equal(h.elements.tls13.checked,false);assert.equal(h.elements.certificate.disabled,true);
});
