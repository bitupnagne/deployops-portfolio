/* Native Playwright fixture; original callbacks/owners remain authoritative, no server actions. */
async (page, source, css) => {
 const checks=[];const geometry=[];const assert=(ok,msg)=>{if(!ok)throw new Error(msg);};
 await page.route('http://deploy-ui.test/**',r=>r.fulfill({status:200,contentType:'text/html',body:'<!doctype html>'}));
 for(const width of [1440,390]){
  await page.setViewportSize({width,height:844});await page.goto('http://deploy-ui.test/fixture');
  await page.setContent(`<!doctype html><html><head><base href="http://deploy-ui.test/"></head><body>
   <button id="outside" type="button">Outside</button>
   <div class="grp" id="deleteHome"><button id="editSource" type="button" data-ui-delete-target="#modal" onclick="document.getElementById('record').value='17';document.getElementById('modal').style.display='flex'">수정</button><button id="originalDelete" type="button" data-ui-delete-source hidden onclick="window.deleteClicks++;window.deletedContext=this.__uiDeleteContext?.id">삭제</button></div>
   <div id="modal" class="modal-overlay" data-ui-edit-modal data-ui-edit-record="#record" style="display:none"><div class="modal" role="dialog" aria-modal="true"><div class="modal-header"><h3 class="modal-title">기존 설정</h3><button class="modal-close" type="button" onclick="document.getElementById('modal').style.display='none'">×</button></div><div class="modal-body">
    <form id="nativeForm" action="/must-not-send" method="post"><input id="record" type="hidden" name="id" value=""><input id="required" name="name" value="Valid" required><input id="permanent" name="fixed" value="Permanent" readonly><input id="disabled" name="locked" value="Locked" disabled>
     <div class="ds-help" data-ui-help><button id="help" type="button" class="ds-help-toggle" data-ui-help-toggle aria-label="설정 도움말">?</button><div id="panel" class="ds-help-panel" data-ui-help-panel hidden aria-label="설정 도움말"><strong>보조 설명</strong><div class="ds-help-content" id="dynamic">설명</div><div class="ds-help-foot"><button type="button" class="btn btn-primary ds-help-close" data-ui-help-close>닫기</button></div></div></div>
     <button id="other" type="button">Other field action</button><div class="modal-footer" id="footer"><button id="cancel" type="button" class="btn btn-ghost" data-ui-edit-cancel onclick="window.cancelClicks++;document.getElementById('modal').style.display='none'">취소</button><button id="save" type="submit" class="btn btn-primary" name="intent" value="update" formaction="/original-save" data-ui-edit-save onclick="window.saveClicks++">저장</button></div>
    </form>
   </div></div></div>
   <div class="ds-help" data-ui-help id="late-container"></div>
  </body></html>`);
  await page.addStyleTag({content:css});
  await page.evaluate(()=>{window.saveClicks=0;window.cancelClicks=0;window.deleteClicks=0;window.parentEsc=0;window.sent=[];window.originalSave=document.querySelector('#save');window.originalCancel=document.querySelector('#cancel');document.addEventListener('submit',e=>{e.preventDefault();window.sent.push({submitter:e.submitter.id,values:[...new FormData(e.target,e.submitter)]});});document.addEventListener('keydown',e=>{if(e.key==='Escape')window.parentEsc++;});});
  await page.addScriptTag({content:source});
  await page.evaluate(()=>{document.querySelector('#modal').style.display='flex';});
  assert(await page.locator('#footer #save').count()===1,'create flow must retain original footer save');
  assert(await page.locator('.ds-edit-toolbar').count()===0,'create flow must not acquire an edit toolbar');
  await page.locator('#cancel').click();
  await page.waitForFunction(()=>getComputedStyle(document.querySelector('#modal')).display==='none');
  await page.evaluate(()=>{document.querySelector('#record').value='17';document.querySelector('#modal').style.display='flex';});
  await page.waitForFunction(()=>document.querySelector('.modal-header .ds-edit-toolbar #save'));
  assert(await page.evaluate(()=>document.querySelector('#save')===window.originalSave&&document.querySelector('#cancel')===window.originalCancel),'actual action nodes must be moved without clones');
  assert(await page.evaluate(()=>document.querySelector('#save').form===document.querySelector('#nativeForm')&&document.querySelector('#save').getAttribute('formaction')==='/original-save'),'native form owner and overrides must survive header relocation');
  assert(await page.locator('#footer').isHidden(),'empty original footer must hide');
  assert(await page.locator('#permanent').evaluate(n=>n.readOnly)&&await page.locator('#disabled').isDisabled(),'permanent field locks must remain unchanged');
  await page.locator('#required').fill('');await page.locator('#save').click();assert(await page.evaluate(()=>window.sent.length===0),'native required validation must still block submit');
  await page.locator('#required').fill('Valid');await page.locator('#save').click();assert(await page.evaluate(()=>window.sent.length===1&&window.sent[0].submitter==='save'&&window.sent[0].values.some(([k,v])=>k==='intent'&&v==='update')),'original submitter payload must survive');
  const saveBox=await page.locator('#save').boundingBox();const cancelBox=await page.locator('#cancel').boundingBox();assert(saveBox.x>=0&&saveBox.x+saveBox.width<=width&&Math.abs(saveBox.y+saveBox.height/2-cancelBox.y-cancelBox.height/2)<1,'header actions must remain in one visible row');
  checks.push({width,id:'create/edit native actions',passed:true});

  await page.locator('#help').click();await page.waitForFunction(()=>document.querySelector('#help').getAttribute('aria-expanded')==='true');
  assert(await page.evaluate(()=>document.querySelector('#panel').parentElement===document.body&&document.activeElement.matches('[data-ui-help-close]')),'help must portal and focus its explicit close');
  await page.evaluate(()=>{document.querySelector('#panel').getAnimations().forEach(a=>a.finish());});
  let bounds=await page.locator('#panel').boundingBox();assert(bounds.x>=11&&bounds.x+bounds.width<=width-11&&bounds.y>=11&&bounds.y+bounds.height<=844-11,'help must stay inside viewport');geometry.push({width,...bounds});
  await page.locator('[data-ui-help-close]').click();assert(await page.locator('#panel').isHidden()&&await page.evaluate(()=>document.activeElement.id==='help'),'explicit close must restore trigger focus');
  await page.locator('#help').click();await page.keyboard.press('Escape');assert(await page.locator('#panel').isHidden()&&await page.evaluate(()=>window.parentEsc===0&&document.activeElement.id==='help'),'first Escape must close only help and restore focus');
  await page.keyboard.press('Escape');assert(await page.evaluate(()=>window.parentEsc===1),'Escape must pass through when help is closed');
  await page.locator('#help').click();await page.locator('#other').focus();assert(await page.locator('#panel').isHidden()&&await page.evaluate(()=>document.activeElement.id==='other'),'outside focus must close without stealing focus');
  await page.locator('#help').click();await page.locator('.modal-title').click();assert(await page.locator('#panel').isHidden(),'outside click must close help');
  await page.locator('#help').click();await page.evaluate(()=>window.dispatchEvent(new Event('deploy:page-leave')));assert(await page.locator('#panel').isHidden()&&await page.evaluate(()=>document.querySelector('#panel').closest('[data-ui-help]')!==null),'page leave must restore the panel home');
  checks.push({width,id:'help portal, close, Escape, outside, navigation',passed:true});

  await page.locator('#cancel').click();await page.waitForFunction(()=>document.querySelector('#footer #save')&&document.querySelector('#footer #cancel'));
  assert(await page.evaluate(()=>document.querySelector('#save').form===document.querySelector('#nativeForm')&&!document.querySelector('#save').hasAttribute('form')),'closing must restore the original form attribute');
  await page.evaluate(()=>{document.querySelector('#record').value='';document.querySelector('#modal').style.display='flex';});
  assert(await page.locator('#footer #save').isVisible()&&await page.locator('.ds-edit-toolbar').count()===0,'reopening create must retain its original footer flow');
  await page.addScriptTag({content:source});assert(await page.evaluate(()=>document.querySelectorAll('#save').length===1&&document.querySelectorAll('#cancel').length===1),'reinitialization must not duplicate controls');
  checks.push({width,id:'close/create restoration and initialization guard',passed:true});
  await page.locator('#cancel').click();await page.locator('#editSource').click();
  await page.waitForFunction(()=>document.querySelector('[data-ui-delete-slot] #originalDelete:not([hidden])'));
  assert(await page.evaluate(()=>document.querySelector('#originalDelete').__uiDeleteContext.id==='deleteHome'&&!document.querySelector('#originalDelete').closest('form')),'original delete context and independent owner must survive relocation');
  await page.locator('#originalDelete').click();assert(await page.evaluate(()=>window.deleteClicks===1&&window.deletedContext==='deleteHome'),'the original callback must run once with its original context');
  await page.locator('#cancel').click();await page.waitForFunction(()=>document.querySelector('#deleteHome #originalDelete[hidden]'));
  await page.evaluate(()=>{document.querySelector('#record').value='';document.querySelector('#modal').style.display='flex';});
  await page.waitForTimeout(40);assert(await page.locator('[data-ui-delete-slot]').evaluate(n=>!n.children.length)&&await page.locator('#originalDelete').isHidden(),'create must not inherit a previous record deletion action');
  checks.push({width,id:'original delete action relocation, callback, close/create restoration',passed:true});
 }
 return{passed:true,nativeCases:checks.length,checks,geometry,sourceInjection:true,externalOrServerWrites:0};
}
