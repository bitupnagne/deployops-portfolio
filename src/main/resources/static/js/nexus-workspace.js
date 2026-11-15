(() => {
  'use strict';
  const root = document.querySelector('.nexus-ops');
  if (!root || root.dataset.workspaceBound === 'true' || root.dataset.workspaceState === 'error') return;
  const script = document.currentScript, content = document.getElementById('content');
  if (script && script.deployPageNavigation !== undefined &&
      (script.deployPageContent !== content || !content || content.deployPageNavigation !== script.deployPageNavigation || !content.contains(root))) return;
  root.dataset.workspaceBound = 'true';
  const tabs = [...root.querySelectorAll('[data-nx-tab]')];
  const panels = [...root.querySelectorAll('[data-nx-panel]')];
  const status = root.querySelector('.nx-workspace-state');
  if (!tabs.length) {
    root.dataset.workspaceState = 'empty';
    root.setAttribute('aria-busy', 'false');
    root.querySelector('[data-nx-workspace-message]').textContent = '사용할 수 있는 Nexus 운영 작업이 없습니다.';
    root.dispatchEvent(new Event('nexus:workspace-ready'));
    return;
  }
  function select(key, focusPanel = false, updateUrl = true) {
    const selected = tabs.find(tab => tab.dataset.nxTab === key) || tabs[0];
    if (!selected) return;
    key = selected.dataset.nxTab;
    for (const tab of tabs) {
      const active = tab === selected;
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
    }
    for (const panel of panels) panel.hidden = panel.dataset.nxPanel !== key;
    if (updateUrl) {
      const url = new URL(location.href);
      url.hash = selected.getAttribute('aria-controls');
      history.replaceState(history.state, '', url);
    }
    if (focusPanel) panels.find(panel => panel.dataset.nxPanel === key)?.focus({preventScroll:true});
    window.DeployChoiceUi?.update(selected.closest('.ds-section-tabs'));
  }
  for (const tab of tabs) {
    tab.addEventListener('click', () => select(tab.dataset.nxTab));
    tab.addEventListener('keydown', event => {
      let index = tabs.indexOf(tab);
      if (event.key === 'ArrowRight') index = (index + 1) % tabs.length;
      else if (event.key === 'ArrowLeft') index = (index + tabs.length - 1) % tabs.length;
      else if (event.key === 'Home') index = 0;
      else if (event.key === 'End') index = tabs.length - 1;
      else return;
      event.preventDefault();select(tabs[index].dataset.nxTab);tabs[index].focus({preventScroll:true});
    });
  }
  for (const button of root.querySelectorAll('[data-nx-go]')) {
    button.addEventListener('click', () => select(button.dataset.nxGo, true));
  }
  function fromLocation() {
    const requested = tabs.find(tab => '#'+tab.getAttribute('aria-controls') === location.hash);
    const dependencies = new URL(location.href).searchParams.has('dependencyProjectId');
    select(requested?.dataset.nxTab || (dependencies ? 'dependencies' : tabs[0]?.dataset.nxTab), false, false);
  }
  function cleanup() {
    window.removeEventListener('hashchange', fromLocation);
    window.removeEventListener('deploy:page-leave', cleanup);
    window.removeEventListener('pagehide', cleanup);
  }
  window.addEventListener('hashchange', fromLocation);
  window.addEventListener('deploy:page-leave', cleanup);
  window.addEventListener('pagehide', cleanup);
  fromLocation();
  for (const tab of tabs) tab.disabled = false;
  if (status) status.hidden = true;
  root.dataset.workspaceState = 'ready';
  root.setAttribute('aria-busy', 'false');
  root.dispatchEvent(new Event('nexus:workspace-ready'));
})();
