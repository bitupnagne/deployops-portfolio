/* 조회 표면의 노드는 유지하고 요청 소유자만 로딩 표시를 해제한다. */
(function () {
  'use strict';
  const owners = new WeakMap();
  const active = new Set();
  function markup(count) {
    return '<span class="deploy-skeleton" aria-hidden="true">' +
      '<span class="deploy-skeleton-bar"></span>'.repeat(Math.max(2, Math.min(40, count || 4))) + '</span>';
  }
  function start(target) {
    if (!target || !target.isConnected) return function () {};
    const previous = owners.get(target);
    if (previous) previous();
    const busy = target.getAttribute('aria-busy');
    const position = target.style.position;
    const overlay = document.createElement('div');
    const children = Array.from(target.children).map(node => [node, node.hasAttribute('inert')]);
    let stopped = false;
    overlay.className = 'deploy-loading-mask';
    overlay.style.opacity = '0';
    overlay.style.transition = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 'none' : 'opacity .12s ease';
    overlay.innerHTML = markup(Math.ceil((target.clientHeight || 200) / 44));
    overlay.setAttribute('aria-hidden', 'true');
    if (getComputedStyle(target).position === 'static') target.style.position = 'relative';
    target.setAttribute('aria-busy', 'true');
    children.forEach(([node]) => node.setAttribute('inert', ''));
    target.appendChild(overlay);
    const align = () => {
      overlay.style.left = (target.scrollLeft || 0) + 'px';
      overlay.style.top = (target.scrollTop || 0) + 'px';
      if (target.clientWidth) overlay.style.width = target.clientWidth + 'px';
      if (target.clientHeight) overlay.style.height = target.clientHeight + 'px';
    };
    align();
    target.addEventListener?.('scroll', align, { passive: true });
    const reveal = setTimeout(() => { if (!stopped) overlay.style.opacity = '1'; }, 120);
    function stop() {
      if (stopped || owners.get(target) !== stop) return;
      stopped = true;
      clearTimeout(reveal);
      target.removeEventListener?.('scroll', align);
      overlay.remove();
      if (busy === null) target.removeAttribute('aria-busy'); else target.setAttribute('aria-busy', busy);
      children.forEach(([node, inert]) => { if (!inert) node.removeAttribute('inert'); });
      target.style.position = position;
      owners.delete(target); active.delete(stop);
    }
    owners.set(target, stop); active.add(stop);
    return stop;
  }
  window.DeployLoading = { start, markup };
  window.addEventListener('pagehide', () => Array.from(active).forEach(stop => stop()));

  // 기존 화면이 내보내는 상태 노드만 변환한다. 실패/빈 결과/저장 완료는 건드리지 않는다.
  const loadingText = /^(?:[가-힣A-Za-z0-9 ·/()_-]+)?(?:불러오는\s*중|로딩(?:\s*중)?|로드\s*중|조회(?:하는)?\s*중|읽는\s*중|준비(?:하는)?\s*중|Loading)(?:입니다)?[.\u2026\s]*$/i;
  const completedText = /^(?:저장소 원문을|저장된 실행 로그를|파일 로그를|이미지 Digest를) (?:불러왔습니다|조회했습니다)\.$/;
  const statusOwners = new Map();
  function scan(root) {
    if (!root || !root.querySelectorAll) return;
    for (const [host, before] of statusOwners) {
      if (!host.isConnected || !host.querySelector('[data-deploy-loading]')) {
        if (host.getAttribute('aria-busy') === 'true') {
          if (before === null) host.removeAttribute('aria-busy'); else host.setAttribute('aria-busy', before);
        }
        statusOwners.delete(host);
      }
    }
    const selector = 'p,td,option,pre,.form-hint,.muted,.tr-empty,.nx-empty,.pb-empty,.cs-txt,.cs-opt-txt,[role="status"],[id$="-status"],[id$="-msg"],[id$="-hint"],[id$="-result"],[data-outbox="page"],#nexus-ops-storage,div[style*="text-align:center"],div[style*="color:var(--muted)"]';
    const candidates = Array.from(root.querySelectorAll(selector));
    if (root.matches?.(selector)) candidates.unshift(root);
    candidates.forEach(node => {
      if (node.children.length || node.closest('script,style,.deploy-skeleton,.deploy-loading-mask')) return;
      const value = node.textContent.trim();
      if (completedText.test(value)) { node.textContent = ''; return; }
      if (!loadingText.test(value)) return;
      if (node.tagName === 'OPTION') {
        node.textContent = '';
        return;
      }
      if (!statusOwners.has(node)) statusOwners.set(node, node.getAttribute('aria-busy'));
      node.setAttribute('aria-busy', 'true');
      node.innerHTML = '<span data-deploy-loading="" aria-hidden="true">' + markup(3) + '</span>';
    });
  }
  let queued = false;
  const pending = new Set();
  function refresh(root) {
    pending.add(root || document);
    if (queued) return;
    queued = true;
    queueMicrotask(() => { queued = false; const roots = Array.from(pending); pending.clear(); roots.forEach(scan); });
  }
  new MutationObserver(changes => changes.forEach(change => refresh(change.target.nodeType === 1 ? change.target : change.target.parentElement)))
    .observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  refresh();
})();
