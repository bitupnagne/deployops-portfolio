/* Explicit helper descriptions and existing edit-modal actions; business handlers remain authoritative. */
(() => {
  if (window.DeployUiInteractions) return;
  const helpStates = new WeakMap();
  const modalStates = new WeakMap();
  const deleteStates = new Map();
  let activeHelp = null;
  let helpResize = null;
  let sequence = 0;
  let scheduled = false;
  const isVisible = node => !!node?.isConnected && !node.hidden && node.getClientRects().length > 0;
  const closeHelp = (restoreFocus = true) => {
    const state = activeHelp;
    if (!state) return;
    activeHelp = null;
    helpResize?.disconnect();
    helpResize = null;
    state.panel.hidden = true;
    delete state.panel.__deployHelpTrigger;
    state.trigger.setAttribute('aria-expanded', 'false');
    if (state.home.isConnected) state.home.insertBefore(state.panel, state.next?.parentNode === state.home ? state.next : null); else state.panel.remove();
    if (state.originalStyle === null) state.panel.removeAttribute('style'); else state.panel.setAttribute('style', state.originalStyle);
    if (restoreFocus && isVisible(state.trigger) && !state.trigger.disabled) state.trigger.focus({ preventScroll: true });
  };
  const positionHelp = () => {
    if (!activeHelp) return;
    const { trigger, panel } = activeHelp;
    if (!isVisible(trigger)) { closeHelp(false); return; }
    const viewport = window.visualViewport;
    const vx = viewport?.offsetLeft || 0, vy = viewport?.offsetTop || 0;
    const vw = viewport?.width || innerWidth, vh = viewport?.height || innerHeight;
    panel.style.maxWidth = Math.max(0, vw - 24) + 'px';
    panel.style.maxHeight = Math.max(0, vh - 24) + 'px';
    let layer = 720;
    for (let node = trigger.parentElement; node; node = node.parentElement) {
      const z = Number(getComputedStyle(node).zIndex);
      if (Number.isFinite(z)) layer = Math.max(layer, z + 2);
    }
    panel.style.zIndex = String(layer);
    const anchor = trigger.getBoundingClientRect();
    if (anchor.bottom < vy || anchor.top > vy + vh) { closeHelp(false); return; }
    const size = panel.getBoundingClientRect();
    const left = Math.max(vx + 12, Math.min(anchor.right - size.width, vx + vw - size.width - 12));
    const below = anchor.bottom + 8;
    const top = below + size.height <= vy + vh - 12 ? below : anchor.top - size.height - 8;
    panel.style.left = left + 'px';
    panel.style.top = Math.max(vy + 12, Math.min(top, vy + vh - size.height - 12)) + 'px';
  };
  const prepareHelp = host => {
    if (helpStates.has(host)) return;
    const trigger = host.querySelector('[data-ui-help-toggle]');
    const panel = host.querySelector('[data-ui-help-panel]');
    if (!trigger || !panel) return;
    if (!panel.id) {
      let id;
      do { id = 'deploy-help-' + (++sequence); } while (document.getElementById(id));
      panel.id = id;
    }
    trigger.setAttribute('aria-controls', panel.id);
    trigger.setAttribute('aria-expanded', 'false');
    trigger.setAttribute('aria-haspopup', 'dialog');
    panel.setAttribute('role', 'dialog');
    panel.hidden = true;
    helpStates.set(host, { host, trigger, panel, home: panel.parentElement, next: panel.nextSibling, originalStyle: panel.getAttribute('style') });
  };
  const syncModal = overlay => {
    let state = modalStates.get(overlay);
    if (!state) {
      const record = overlay.querySelector(overlay.dataset.uiEditRecord || '[data-ui-edit-record-value]');
      const save = overlay.querySelector('[data-ui-edit-save]');
      const cancel = overlay.querySelector('[data-ui-edit-cancel]');
      const header = overlay.querySelector('.modal-header');
      if (!record || !save || !cancel || !header) return;
      const footer = save.closest('.modal-footer');
      const homes = [cancel, save].map(node => {
        const anchor = document.createComment('edit action home');
        node.before(anchor);
        return { node, anchor, form: node.form, formAttribute: node.getAttribute('form') };
      });
      const toolbar = document.createElement('div');
      toolbar.className = 'ds-edit-toolbar';
      toolbar.setAttribute('role', 'group');
      toolbar.setAttribute('aria-label', '편집 작업');
      state = { record, save, cancel, header, footer, footerHidden: footer?.hidden, homes, toolbar, editing: false };
      modalStates.set(overlay, state);
    }
    const editing = isVisible(overlay) && !!String(state.record.value || '').trim();
    if (editing === state.editing) return;
    state.editing = editing;
    if (editing) {
      const close = state.header.querySelector('.modal-close');
      if (close) close.before(state.toolbar); else state.header.appendChild(state.toolbar);
      state.homes.forEach(({ node, form }) => {
        if (form) {
          let id = form.getAttribute('id');
          if (!id) {
            do { id = 'deploy-edit-form-' + (++sequence); } while (document.getElementById(id));
            form.setAttribute('id', id);
          }
          node.setAttribute('form', id);
        }
        state.toolbar.appendChild(node);
      });
      state.save.classList.add('ds-edit-save');
      if (state.footer && !state.footer.textContent.trim() && !state.footer.querySelector('button,a,input:not([type="hidden"]),select,textarea')) {
        state.footer.dataset.uiEmptyEditFooter = '';
        state.footer.hidden = true;
      }
    } else {
      state.homes.forEach(({ node, anchor, formAttribute }) => {
        if (anchor.parentNode) anchor.after(node);
        if (formAttribute === null) node.removeAttribute('form'); else node.setAttribute('form', formAttribute);
      });
      state.toolbar.remove();
      state.save.classList.remove('ds-edit-save');
      if (state.footer) {
        state.footer.hidden = state.footerHidden;
        delete state.footer.dataset.uiEmptyEditFooter;
      }
    }
    overlay.classList.toggle('ds-edit-toolbar-active', editing);
  };
  const restoreDelete = overlay => {
    const state = deleteStates.get(overlay);
    if (!state) return;
    deleteStates.delete(overlay);
    state.button.hidden = true;
    if (state.home.isConnected) state.home.insertBefore(state.button, state.next?.parentNode === state.home ? state.next : null);
    else state.button.remove();
  };
  const placeDelete = (trigger, button) => {
    const overlay = document.querySelector(trigger.dataset.uiDeleteTarget || '');
    const record = overlay?.querySelector(overlay.dataset.uiEditRecord || '[data-ui-edit-record-value]');
    if (!overlay?.matches('[data-ui-edit-modal]') || !isVisible(overlay) || !String(record?.value || '').trim()) return;
    restoreDelete(overlay);
    let slot = overlay.querySelector('[data-ui-delete-slot]');
    if (!slot) {
      slot = document.createElement('div');
      slot.className = 'ds-edit-delete-slot';
      slot.setAttribute('data-ui-delete-slot', '');
      const dialog = overlay.querySelector(':scope > .modal');
      if (!dialog) return;
      dialog.appendChild(slot);
    }
    if (slot.closest('form')) return;
    const state = { button, home: button.parentNode, next: button.nextSibling };
    button.__uiDeleteContext = button.closest('tr,.grp');
    deleteStates.set(overlay, state);
    slot.appendChild(button);
    button.hidden = false;
  };
  document.addEventListener('click', event => {
    const trigger = event.target.closest('[data-ui-delete-target]');
    if (!trigger) return;
    const button = trigger.parentElement.querySelector('[data-ui-delete-source]');
    if (!button) return;
    // The original inline handler opens and fills the existing editor first.
    setTimeout(() => { if (trigger.isConnected && button.isConnected) placeDelete(trigger, button); }, 0);
  }, true);
  const scan = () => {
    scheduled = false;
    for (const [overlay] of deleteStates) {
      const record = overlay.querySelector(overlay.dataset.uiEditRecord || '[data-ui-edit-record-value]');
      if (!isVisible(overlay) || !String(record?.value || '').trim()) restoreDelete(overlay);
    }
    if (activeHelp && (!isVisible(activeHelp.trigger) || !activeHelp.home.isConnected)) closeHelp(false);
    document.querySelectorAll('[data-ui-help]').forEach(prepareHelp);
    document.querySelectorAll('[data-ui-edit-modal]').forEach(syncModal);
  };
  const schedule = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(scan);
  };
  document.addEventListener('click', event => {
    const trigger = event.target.closest('[data-ui-help-toggle]');
    if (trigger) {
      const host = trigger.closest('[data-ui-help]');
      if (!host) return;
      prepareHelp(host);
      const state = helpStates.get(host);
      if (!state) return;
      event.preventDefault();
      if (activeHelp === state) { closeHelp(); return; }
      closeHelp(false);
      activeHelp = state;
      document.body.appendChild(state.panel);
      state.panel.__deployHelpTrigger = state.trigger;
      state.panel.hidden = false;
      state.trigger.setAttribute('aria-expanded', 'true');
      positionHelp();
      if (typeof ResizeObserver === 'function') { helpResize = new ResizeObserver(positionHelp); helpResize.observe(state.panel); }
      state.panel.querySelector('[data-ui-help-close]')?.focus({ preventScroll: true });
    } else if (activeHelp?.panel.contains(event.target)) {
      if (event.target.closest('[data-ui-help-close]')) { event.preventDefault(); closeHelp(); }
    } else closeHelp(false);
  });
  window.addEventListener('keydown', event => {
    if (event.key === 'Escape' && activeHelp) {
      event.preventDefault();
      event.stopImmediatePropagation();
      closeHelp();
    }
  }, true);
  document.addEventListener('focusin', event => {
    if (activeHelp && event.target !== activeHelp.trigger && !activeHelp.panel.contains(event.target)) closeHelp(false);
  });
  window.addEventListener('resize', positionHelp);
  window.addEventListener('scroll', positionHelp, true);
  window.addEventListener('deploy:page-leave', () => { closeHelp(false); for (const [overlay] of deleteStates) restoreDelete(overlay); });
  window.visualViewport?.addEventListener('resize', positionHelp);
  window.visualViewport?.addEventListener('scroll', positionHelp);
  window.DeployUiInteractions = { scan, closeHelp };
  const init = () => {
    scan();
    new MutationObserver(records => {
      if (activeHelp && (!activeHelp.trigger.isConnected || !isVisible(activeHelp.trigger))) closeHelp(false);
      if (records.some(record => record.type === 'attributes'
        ? record.target.matches('[data-ui-edit-modal]')
        : [...record.addedNodes].some(node => node.nodeType === 1 && (node.matches('[data-ui-help],[data-ui-edit-modal]') || node.querySelector('[data-ui-help],[data-ui-edit-modal]'))))) schedule();
    }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style','class','hidden'] });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true }); else init();
})();
