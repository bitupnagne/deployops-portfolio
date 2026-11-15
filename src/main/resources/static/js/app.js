/* =====================================================
   DeployOps — app.js
   화면 이동 + 최소 인터랙션
===================================================== */

/* ─── 페이지 메타 (브레드크럼 텍스트) ─── */
const PAGE_META = {
  'dashboard':      { label: '대시보드' },
  'requests':       { label: '배포 관리 > 배포 요청 목록' },
  'request-create': { label: '배포 관리 > 새 배포 요청' },
  'request-detail': { label: '배포 관리 > 배포 요청 상세' },
  'projects':       { label: '인프라 관리 > 프로젝트' },
  'project-detail': { label: '인프라 관리 > 프로젝트 상세' },
  'server-groups':  { label: '인프라 관리 > 서버 그룹' },
  'servers':        { label: '인프라 관리 > 서버 관리' },
  'infrastructure': { label: '인프라 관리 > 인프라 운영' },
  'nginx-policies': { label: '인프라 관리 > Nginx 정책' },
  'nexus-operations': { label: '인프라 관리 > Nexus 운영' },
  'users':          { label: '시스템 > 사용자 / 역할' },
  'teams':          { label: '시스템 > 팀 관리' },
  'policies':       { label: '시스템 > 승인 정책' },
  'integration':    { label: '시스템 > 연동 설정' },
  'exec-policy':    { label: '시스템 > 실행 정책' },
  'audit':          { label: '시스템 > 감사 로그' },
  'profile':        { label: '내 프로필' },
  'mr-approvals':   { label: '배포 관리 > MR 승인 대기' },
  'server-map':     { label: '인프라 관리 > 서버 구조 · 제어' },
  'ai-settings':    { label: '시스템 > AI 설정' },
  'security':       { label: '시스템 > 보안 설정' },
};
/* ※ PageViewController.PAGE_LABELS 와 같은 값이어야 한다.
     서버는 최초 렌더(주소 직접 진입·새로고침), 여기는 SPA 전환을 담당한다.
     PageBreadcrumbTest 가 두 목록의 짝을 검사한다. */

/* ─── 현재 페이지 ─── */
let currentPage = typeof location !== 'undefined' ? (location.pathname.match(/^\/deploy\/([a-z-]+)/)?.[1] || 'dashboard') : 'dashboard';

function syncNavigationCurrent(pageId) {
  const parent = { 'project-detail': 'projects', 'request-detail': 'requests', servers: 'server-groups', 'nginx-policies': 'server-groups' };
  const selectedPage = parent[pageId] || pageId;
  document.querySelectorAll('.nav-item').forEach(item => {
    const active = item.dataset.page === selectedPage;
    item.classList.toggle('active', active);
    if (active) item.setAttribute('aria-current', 'page');
    else item.removeAttribute('aria-current');
  });
  const profile = document.querySelector('.sidebar-user');
  if (profile) {
    if (pageId === 'profile') profile.setAttribute('aria-current', 'page');
    else profile.removeAttribute('aria-current');
  }
}

function restoreQueryFilters(content, incoming) {
  content.querySelectorAll('.filter-bar input[id],.filter-bar select[id],.filter-bar textarea[id]').forEach(field => {
    const fresh = incoming.getElementById(field.id);
    if (!fresh || fresh.tagName !== field.tagName || ['file', 'password'].includes(field.type)) return;
    if (field.type === 'checkbox' || field.type === 'radio') field.checked = fresh.checked;
    else field.value = fresh.value;
    if (field.tagName === 'SELECT') window.DeploySelect?.sync(field);
  });
}
let pageNavigation = 0;
let pageRequest = null;
function reusePageControls(current, next) {
  const before = Array.from(current.querySelectorAll('button,a'));
  const after = Array.from(next.querySelectorAll('button,a'));
  if (before.length !== after.length || before.some((node, index) => node.tagName !== after[index].tagName)) return;
  before.forEach((node, index) => {
    Array.from(node.attributes).forEach(attribute => node.removeAttribute(attribute.name));
    Array.from(after[index].attributes).forEach(attribute => node.setAttribute(attribute.name, attribute.value));
    node.innerHTML = after[index].innerHTML;
    after[index].replaceWith(node);
  });
}

// 서버가 렌더한 fragment의 스크립트는 외부 파일도 문서 순서대로 실행한다.
async function runPageScripts(content, navigation) {
  for (const oldScript of content.querySelectorAll('script')) {
    if (navigation !== pageNavigation || !oldScript.isConnected) return;
    const newScript = document.createElement('script');
    newScript.deployPageContent = content;
    newScript.deployPageNavigation = navigation;
    for (const attribute of oldScript.attributes) {
      newScript.setAttribute(attribute.name, attribute.value);
    }
    newScript.textContent = oldScript.textContent;
    if (oldScript.hasAttribute('src') || oldScript.type === 'module') {
      newScript.async = false;
      await new Promise((resolve, reject) => {
        newScript.onload = resolve;
        newScript.onerror = () => reject(new Error('페이지 스크립트를 불러오지 못했습니다.'));
        oldScript.replaceWith(newScript);
      });
    } else {
      oldScript.replaceWith(newScript);
    }
  }
}

/* ─────────────────────────────────────────
   페이지 로더 (Assistant 로딩 바)
───────────────────────────────────────── */
let _loaderTimer = null;
let _loaderShowTimer = null;
function showPageLoader(on) {
  // 기존 호출 계약은 유지하되 화면 전체를 가리는 로더는 사용하지 않는다.
  const loader = document.getElementById('page-loader');
  if (loader) loader.classList.remove('visible');
}

/* 비밀번호/토큰 입력 표시 토글 (여러 화면 공통) */
function togglePassword(id, btn) {
  const input = document.getElementById(id);
  if (!input) return;
  if (input.type === 'password') {
    input.type = 'text';
    if (btn) btn.textContent = '숨김';
  } else {
    input.type = 'password';
    if (btn) btn.textContent = '표시';
  }
}

/* ─────────────────────────────────────────
   navigate(pageId, id, params)
   모든 화면 전환은 이 함수를 통해서만 수행
   - id: 상세 페이지용 리소스 ID (선택)
   - params: 추가 쿼리 파라미터 객체 (선택)
───────────────────────────────────────── */
async function navigate(pageId, id, params, opts) {
  if (!pageId) return;
  if (pageId === 'nginx-policies') pageId = 'server-groups';
  opts = opts || {};
  const navigation = ++pageNavigation;
  if (pageRequest) pageRequest.abort();
  const request = typeof AbortController === 'function' ? new AbortController() : null;
  pageRequest = request;
  const oldContent = document.getElementById('content');
  const samePage = currentPage === pageId && typeof location !== 'undefined' && location.pathname === '/deploy/' + pageId;
  const queryKeys = { audit: ['actor', 'from', 'to', 'page'], requests: ['status', 'projectId', 'from', 'to', 'page'] }[pageId];
  const queryPage = samePage && params && id == null && queryKeys
    && Object.keys(params).every(key => queryKeys.includes(key))
    && [...new URLSearchParams(location.search).keys()].every(key => queryKeys.includes(key));
  let oldScroll = oldContent ? oldContent.scrollTop : 0;
  let oldWindowY = window.scrollY || 0;
  const oldFocus = document.activeElement;
  const selectedTabs = samePage && oldContent ? Array.from(oldContent.querySelectorAll('.tab-btn.active'))
    .map(node => ({ tab: node.dataset.tab, group: node.dataset.group })) : [];
  const surfaces = oldContent ? Array.from(oldContent.querySelectorAll('.table-wrap'))
    .filter(node => !node.closest?.('.modal-overlay') && (!node.getClientRects || node.getClientRects().length)) : [];
  const finishLoading = (surfaces.length ? surfaces : (oldContent ? [oldContent] : []))
    .map(node => window.DeployLoading ? window.DeployLoading.start(node) : function () {});
  let contentChanged = false;

  /* 1. 로딩 시작 */
  showPageLoader(true);

  const commitMetadata = () => {
  /* 2. 사이드바 active 업데이트 — 응답이 성공한 뒤에만 반영한다. */
  syncNavigationCurrent(pageId);

  /* 3. 브레드크럼 업데이트 */
  const meta = PAGE_META[pageId] || { label: pageId };
  const breadcrumb = document.getElementById('breadcrumb');
  if (breadcrumb) {
    const parts = meta.label.split(' > ');
    if (parts.length === 1) {
      breadcrumb.innerHTML = `<span>${parts[0]}</span>`;
    } else {
      breadcrumb.innerHTML =
        `<span class="bc-parent">${parts[0]}</span>` +
        `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>` +
        `<span>${parts[1]}</span>`;
    }
  }
  };

  /* 4. 페이지 콘텐츠 fetch — Spring Boot Thymeleaf Fragment */
  try {
    // URL 쿼리 파라미터 구성
    const qs = new URLSearchParams();
    if (id != null) qs.set('id', id);
    if (params) Object.entries(params).forEach(([k, v]) => { if (v !== null && v !== undefined && v !== '') qs.set(k, v); });
    const queryStr = qs.toString() ? '?' + qs.toString() : '';

    const res = await fetch(`/deploy/${pageId}${queryStr}`, {
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      signal: request ? request.signal : undefined
    });
    if (navigation !== pageNavigation) return;
    // Spring auth interceptors can redirect an SPA fragment request to login/profile.
    // Fetch follows that redirect; rendering its full-page response as a fragment
    // would leave the requested URL (and active menu) inconsistent with the screen.
    if (res.redirected && res.url) {
      window.location.assign(res.url);
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const html = await res.text();
    if (navigation !== pageNavigation) return;
    const content = document.getElementById('content');
    if (content) {
      if (samePage) { oldScroll = content.scrollTop; oldWindowY = window.scrollY || 0; }
      let partial = false;
      if (queryPage && typeof DOMParser === 'function') {
        const fragment = new DOMParser().parseFromString(html, 'text/html');
        const currentTables = Array.from(content.querySelectorAll('.table-wrap')).filter(node => !node.closest('.modal-overlay'));
        const nextTables = Array.from(fragment.querySelectorAll('.table-wrap')).filter(node => !node.closest('.modal-overlay'));
        if (currentTables.length && currentTables.length === nextTables.length) {
          // 검색·페이지 이동은 필터, 탭, 모달, 편집 중 입력을 그대로 둔다.
          finishLoading.forEach(stop => stop());
          currentTables.forEach((node, index) => {
            const left = node.scrollLeft;
            if (node.clientHeight) node.style.minHeight = Math.max(parseFloat(node.style.minHeight) || 0, node.clientHeight) + 'px';
            node.replaceChildren(...Array.from(nextTables[index].childNodes));
            node.scrollLeft = left;
            Array.from(node.children).forEach(child => child.classList?.add('deploy-result-enter'));
          });
          const beforeFooters = content.querySelectorAll('.table-footer');
          const afterFooters = fragment.querySelectorAll('.table-footer');
          if (beforeFooters.length === afterFooters.length) beforeFooters.forEach((node, index) => {
            reusePageControls(node, afterFooters[index]);
            node.replaceChildren(...Array.from(afterFooters[index].childNodes));
          });
          if (opts.fromPopState) restoreQueryFilters(content, fragment);
          partial = true;
        }
      }
      if (!partial) {
      window.dispatchEvent(new Event('deploy:page-leave'));
      contentChanged = true;
      content.deployPageNavigation = navigation;
      content.innerHTML = html;
      Array.from(content.children || []).forEach(child => child.classList?.add('deploy-result-enter'));
      content.scrollTop = samePage ? oldScroll : 0;
      // 페이지 내 script 태그 재실행
      await runPageScripts(content, navigation);
      if (navigation !== pageNavigation) return;
      selectedTabs.forEach(previous => {
        const button = Array.from(content.querySelectorAll('.tab-btn')).find(node => node.dataset.tab === previous.tab && node.dataset.group === previous.group);
        if (button && typeof switchTab === 'function') switchTab(button, false);
      });
      }
      if (samePage) {
        content.scrollTop = oldScroll;
        if (window.scrollTo) window.scrollTo(0, oldWindowY);
        if (oldFocus && (!document.activeElement || document.activeElement === document.body || document.activeElement === oldFocus)) {
          const nextFocus = oldFocus.isConnected ? oldFocus : Array.from(content.querySelectorAll('button,a,input,select,textarea'))
            .find(node => !node.disabled && node.tagName === oldFocus.tagName
              && (oldFocus.id ? node.id === oldFocus.id : node.textContent === oldFocus.textContent));
          nextFocus?.focus?.({ preventScroll: true });
        }
      }
      // 새로 들어온 클릭 전용 요소(.tr-link 등)에 키보드 접근을 다시 부여한다.
      // DOMContentLoaded 는 최초 1회뿐이라 여기서 하지 않으면 화면을 옮긴 뒤부터 탭이 안 먹는다.
      if (typeof makeClickablesFocusable === 'function') makeClickablesFocusable(content);
      // 새로 들어온 화면의 모달에도 같은 이유로 열림/닫힘 포커스 관리를 다시 걸어준다.
      if (typeof wireModalFocus === 'function') wireModalFocus(content);
    }
    commitMetadata();
    currentPage = pageId;

    /* 5. 주소를 화면과 맞춘다.
       그동안 SPA 이동이 URL 을 바꾸지 않아, 대시보드에서 들어온 뒤 화면 안에서
       location.reload() 를 부르면 대시보드로 되돌아갔다(서버 그룹 저장/삭제가 그랬다).
       새로고침·뒤로가기·주소 복사도 이제 보고 있는 화면을 가리킨다. */
    if (!opts.fromPopState && typeof history !== 'undefined' && history.pushState) {
      const sameProject = pageId === 'project-detail' && samePage && String(id) === new URLSearchParams(location.search).get('id');
      const url = '/deploy/' + pageId + (qs.toString() ? '?' + qs.toString() : '') + (sameProject ? location.hash : '');
      if (url !== location.pathname + location.search + location.hash) {
        history.pushState({ pageId, id: id ?? null, params: params ?? null }, '', url);
      }
    }
    restoreProjectTabFromLocation();
  } catch (err) {
    if (navigation !== pageNavigation || err.name === 'AbortError') return;
    console.error(`[DeployOps] 페이지 로드 실패: /deploy/${pageId}`, err);
    const content = document.getElementById('content');
    if (content) {
      if (contentChanged) {
        window.dispatchEvent(new Event('deploy:page-leave'));
        content.innerHTML = '<div class="page-header"><h1 class="page-title">페이지를 불러올 수 없습니다</h1></div><div class="section-card"><p class="form-hint">페이지 초기화에 실패했습니다. 메뉴를 다시 선택해 주세요.</p></div>';
      }
      // 실패 시 기존 조회 결과/입력을 지우지 않는다. 재시도는 같은 필터 버튼으로 한다.
      if (typeof showToast === 'function') showToast('페이지를 불러오지 못했습니다. 다시 시도해 주세요.', 'error');
    }
  } finally {
    finishLoading.forEach(stop => stop());
    if (navigation === pageNavigation) pageRequest = null;
    if (navigation === pageNavigation) showPageLoader(false);
  }

  /* 6. 모바일: 네비게이션 후 사이드바 닫기 */
  const sidebar = document.getElementById('sidebar');
  const overlay = document.getElementById('sidebar-overlay');
  if (sidebar && window.innerWidth <= 900 && sidebar.classList.contains('mobile-open')) {
    sidebar.classList.remove('mobile-open');
    overlay.classList.remove('visible');
  }
}

function restoreProjectTabFromLocation() {
  const group = document.querySelector('.project-tabs');
  if (!group || location.pathname !== '/deploy/project-detail') return;
  const tabs = [...group.querySelectorAll('[role="tab"][data-tab]')];
  const selected = tabs.find(tab => '#' + tab.getAttribute('aria-controls') === location.hash)
    || tabs.find(tab => tab.classList.contains('active')) || tabs[0];
  if (!selected) return;
  if (!selected.classList.contains('active')) selected.click(); // Existing tab handlers load their own read-only panel data.
  else switchTab(selected, false);
}

/* 뒤로/앞으로 — 주소가 화면을 따라가므로 브라우저 버튼도 동작해야 한다.
   fromPopState 로 알려 pushState 를 다시 쌓지 않게 한다(누르면 누를수록 이력이 늘어난다). */
window.addEventListener('popstate', function (e) {
  const st = e.state;
  if (st && st.pageId) {
    navigate(st.pageId, st.id, st.params, { fromPopState: true });
    return;
  }
  const m = location.pathname.match(/^\/deploy\/([a-z-]+)/);
  if (m) {
    const params = new URLSearchParams(location.search);
    const id = params.get('id'); params.delete('id');
    navigate(m[1], id, Object.fromEntries(params), { fromPopState: true });
  }
});

/* ─────────────────────────────────────────
   사이드바 토글 (햄버거)
   - 모바일(≤900px): 드로어 방식 (오버레이)
   - 데스크톱: collapsed 아이콘 모드
───────────────────────────────────────── */
function toggleSidebar() {
  const sidebar  = document.getElementById('sidebar');
  const overlay  = document.getElementById('sidebar-overlay');
  const isMobile = window.innerWidth <= 900;

  if (isMobile) {
    sidebar.classList.toggle('mobile-open');
    overlay.classList.toggle('visible');
  } else {
    sidebar.classList.toggle('collapsed');
  }
}

/* 모바일: 창 크기 바뀌면 상태 초기화 */
window.addEventListener('resize', function() {
  if (window.innerWidth > 900) {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebar-overlay');
    sidebar.classList.remove('mobile-open');
    overlay.classList.remove('visible');
  }
});

/* ─────────────────────────────────────────
   탭 전환 (공통)
   <button class="tab-btn" data-tab="tabId" data-group="groupId">
   <div class="tab-panel" id="tab-tabId">
───────────────────────────────────────── */
function switchTab(btn, updateLocation = true) {
  const tabId  = btn.dataset.tab;
  const group  = btn.dataset.group || btn.closest('.tab-bar')?.dataset.group;
  const panel  = document.getElementById(`tab-${tabId}`);
  if (!panel) return;

  /* 버튼 active — role="tab" 인 곳은 aria-selected 도 같이 맞춘다(project-detail.html 참고). */
  const siblingBtns = group
    ? document.querySelectorAll(`.tab-btn[data-group="${group}"]`)
    : btn.closest('.tab-bar')?.querySelectorAll('.tab-btn') || [];
  siblingBtns.forEach(b => { b.classList.remove('active'); if (b.getAttribute('role') === 'tab') { b.setAttribute('aria-selected', 'false'); b.tabIndex = -1; } });
  btn.classList.add('active');
  if (btn.getAttribute('role') === 'tab') { btn.setAttribute('aria-selected', 'true'); btn.tabIndex = 0; }

  /* 패널 active */
  const siblingPanels = panel.closest('.tab-panels')?.querySelectorAll('.tab-panel') ||
                        document.querySelectorAll(`.tab-panel[data-group="${group}"]`);
  siblingPanels.forEach(p => p.classList.remove('active'));
  panel.classList.add('active');
  if (updateLocation && btn.closest('.project-tabs') && location.pathname === '/deploy/project-detail') {
    const url = new URL(location.href);
    url.hash = panel.id;
    if (url.href !== location.href) history.replaceState(history.state, '', url);
  }
  window.DeployChoiceUi?.update(btn.closest('.ds-section-tabs'));
}

document.addEventListener('keydown', event => {
  const tab = event.target.closest?.('.project-tabs [role="tab"]');
  if (!tab || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
  const tabs = [...tab.closest('.project-tabs').querySelectorAll('[role="tab"]')];
  let index = tabs.indexOf(tab);
  if (event.key === 'ArrowLeft') index = (index - 1 + tabs.length) % tabs.length;
  if (event.key === 'ArrowRight') index = (index + 1) % tabs.length;
  if (event.key === 'Home') index = 0;
  if (event.key === 'End') index = tabs.length - 1;
  event.preventDefault();
  tabs[index].focus({ preventScroll: true });
  tabs[index].click();
});
window.addEventListener('hashchange', restoreProjectTabFromLocation);
document.addEventListener('DOMContentLoaded', () => {
  syncNavigationCurrent(location.pathname.match(/^\/deploy\/([a-z-]+)/)?.[1] || 'dashboard');
  restoreProjectTabFromLocation();
});

/* ─────────────────────────────────────────
   CSRF 헬퍼
───────────────────────────────────────── */
function getCsrf() {
  return {
    token:  document.querySelector('meta[name="_csrf"]').content,
    header: document.querySelector('meta[name="_csrf_header"]').content
  };
}

/** fetch에 CSRF 헤더를 붙여주는 헬퍼
 *  usage: csrfFetch('/api/v1/foo', { method: 'POST', ... })
 */
function csrfFetch(url, options = {}) {
  const { token, header } = getCsrf();
  options.headers = Object.assign({ [header]: token }, options.headers || {});
  return fetch(url, options);
}

/* ─────────────────────────────────────────
   운영 실행 페이지 — 실행 패널 스크롤
───────────────────────────────────────── */
function hideExecPanel() {
  /* 운영 실행 페이지 상단으로 스크롤 복귀 */
  const content = document.getElementById('content');
  if (content) content.scrollTop = 0;
}

/* ═══════════════════════════════════════════
   MODAL — 공통 모달 시스템
   ─────────────────────────────────────────
   사용법:
     openAlert('제목', '내용', () => { 확인 후 콜백 });
     openConfirm('제목', '내용', () => { 예 콜백 }, () => { 아니요 콜백 });
     closeModal('modal-alert' | 'modal-confirm');
═══════════════════════════════════════════ */
// 공통 확인/알림만 키보드 포커스를 가둔다. 페이지별 모달은 기존 닫기 계약을 유지한다.
const commonModalFocus = [];
function usableModalFocus(element) {
  return !!element && element.isConnected && !element.disabled
    && !element.matches(':disabled') && element.getAttribute('aria-disabled') !== 'true'
    && element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden';
}
function commonModalButtons(modal) {
  return Array.from(modal.querySelectorAll('button')).filter(button =>
    usableModalFocus(button) && button.tabIndex >= 0);
}
function focusCommonModal(state) {
  const buttons = commonModalButtons(state.modal);
  const target = buttons.includes(state.initial) ? state.initial : buttons[0];
  if (target) target.focus({ preventScroll: true });
  else {
    const box = state.modal.querySelector('[role="dialog"]');
    box.setAttribute('tabindex', '-1');
    box.focus({ preventScroll: true });
  }
}
function openCommonModal(modalId, initialId) {
  const modal = document.getElementById(modalId);
  const previous = commonModalFocus.find(state => state.modal === modal);
  const invokingElement = previous && modal.contains(document.activeElement)
    ? previous.invokingElement : document.activeElement;
  closeModal(modalId);
  const state = { modal, initial: document.getElementById(initialId), invokingElement, closed: false };
  state.onKey = function(e) {
    if (commonModalFocus[commonModalFocus.length - 1] !== state) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopImmediatePropagation();
      closeModal(modalId);
    } else if (e.key === 'Tab') {
      const buttons = commonModalButtons(modal);
      e.preventDefault();
      if (!buttons.length) { focusCommonModal(state); return; }
      const current = buttons.indexOf(document.activeElement);
      const next = current < 0 ? (e.shiftKey ? buttons.length - 1 : 0)
        : (current + (e.shiftKey ? -1 : 1) + buttons.length) % buttons.length;
      buttons[next].focus({ preventScroll: true });
    }
  };
  state.onFocus = function(e) {
    if (commonModalFocus[commonModalFocus.length - 1] === state && !modal.contains(e.target)) {
      focusCommonModal(state);
    }
  };
  commonModalFocus.push(state);
  modal.classList.add('open');
  document.addEventListener('keydown', state.onKey, true);
  document.addEventListener('focusin', state.onFocus, true);
  focusCommonModal(state);
  return state;
}
function openAlert(title, content, onConfirm) {
  document.getElementById('modal-alert-title').textContent   = title;
  document.getElementById('modal-alert-content').textContent = content;

  const confirmBtn = document.getElementById('modal-alert-confirm');
  const state = openCommonModal('modal-alert', 'modal-alert-confirm');
  confirmBtn.onclick = function() {
    if (state.closed) return;
    closeModal('modal-alert');
    if (typeof onConfirm === 'function') onConfirm();
  };

}

function openConfirm(title, content, onYes, onNo) {
  document.getElementById('modal-confirm-title').textContent   = title;
  document.getElementById('modal-confirm-content').textContent = content;

  const yesBtn = document.getElementById('modal-confirm-yes');
  const noBtn  = document.getElementById('modal-confirm-no');

  const state = openCommonModal('modal-confirm', 'modal-confirm-no');
  yesBtn.onclick = function() {
    if (state.closed) return;
    closeModal('modal-confirm');
    if (typeof onYes === 'function') onYes();
  };
  noBtn.onclick = function() {
    if (state.closed) return;
    closeModal('modal-confirm');
    if (typeof onNo === 'function') onNo();
  };

}

function closeModal(modalId) {
  document.getElementById(modalId)?.classList.remove('open');
  const index = commonModalFocus.findIndex(state => state.modal.id === modalId);
  if (index < 0) return;
  const wasTop = index === commonModalFocus.length - 1;
  const state = commonModalFocus.splice(index, 1)[0];
  state.closed = true;
  document.removeEventListener('keydown', state.onKey, true);
  document.removeEventListener('focusin', state.onFocus, true);
  state.modal.querySelectorAll('button[id]').forEach(button => { button.onclick = null; });
  if (wasTop && usableModalFocus(state.invokingElement)) {
    state.invokingElement.focus({ preventScroll: true });
  } else if (wasTop && commonModalFocus.length) {
    focusCommonModal(commonModalFocus[commonModalFocus.length - 1]);
  }
}

/* ─────────────────────────────────────────
   삭제를 막는 것들 펼쳐 보기

   삭제가 409 로 막히면 지금까지는 문장 하나가 전부였다 —
   "'PROD' 그룹을 배포 대상으로 쓰는 프로젝트가 있어 삭제할 수 없습니다: A, B".
   맞는 말이지만 거기서 할 수 있는 일이 없다. A 를 찾아 화면을 옮겨 다니다
   B 를 잊고, 이름이 겹치면 어느 쪽인지도 모른다.

   그래서 무엇이 걸려 있는지 항목으로 펼치고, 각 항목에서 그 화면으로 바로 보낸다.
   조치하고 돌아오면 "다시 삭제 시도" 로 이어서 할 수 있다.
───────────────────────────────────────── */
function showBlockers(type, id, retry) {
  const body = document.getElementById('modal-refs-body');
  const retryBtn = document.getElementById('modal-refs-retry');
  if (!body) return;

  document.getElementById('modal-refs').classList.add('open');
  body.innerHTML = '<p class="muted" style="margin:0">무엇이 걸려 있는지 확인하는 중…</p>';
  retryBtn.style.display = 'none';

  fetch('/api/v1/references/' + encodeURIComponent(type) + '/' + encodeURIComponent(id))
    .then(r => r.json())
    .then(res => {
      const d = res && res.data;
      if (!d) {
        body.innerHTML = '<p class="muted" style="margin:0">'
          + escapeHtml((res && res.message) || '확인하지 못했습니다.') + '</p>';
        return;
      }
      document.getElementById('modal-refs-title').textContent =
        d.deletable ? '지금은 막는 것이 없습니다' : '삭제할 수 없습니다';
      body.innerHTML = renderBlockers(d);

      // 조치 후 이어서 지울 수 있게 한다 — 목록으로 돌아가 다시 찾게 하지 않는다.
      if (typeof retry === 'function') {
        retryBtn.style.display = '';
        retryBtn.textContent = d.deletable ? '삭제하기' : '다시 삭제 시도';
        retryBtn.onclick = function () { closeModal('modal-refs'); retry(); };
      }
    })
    .catch(() => {
      body.innerHTML = '<p class="muted" style="margin:0">확인 중 오류가 발생했습니다.</p>';
    });
}

function renderBlockers(d) {
  const jo = objectParticle(d.name);
  const head = '<p class="refs-lead">'
    + '<b>' + escapeHtml(d.name) + '</b>' + jo + ' 지우려면 아래를 먼저 정리해야 합니다.</p>';

  if (d.deletable) {
    return '<p class="refs-lead"><b>' + escapeHtml(d.name)
      + '</b>' + jo + ' 막는 것이 이제 없습니다. 아래 버튼으로 삭제할 수 있습니다.</p>';
  }

  return head + (d.blockers || []).map(b => {
    const shown = b.items || [];
    // 목록은 앞부분만 내려온다. 감춘 개수를 밝히지 않으면 다 처리한 줄 알고 다시 시도한다.
    const rest = (b.total || shown.length) - shown.length;
    const rows = shown.map(it => {
      const label = '<span class="refs-item-name">' + escapeHtml(it.name) + '</span>'
        + (it.detail ? '<span class="refs-item-detail">' + escapeHtml(it.detail) + '</span>' : '');
      // 갈 화면이 없거나 사람이 치울 것이 아니면 링크로 만들지 않는다 —
      // 감사 기록에 "가서 지우세요" 로 읽히면 안 된다.
      if (!b.page || !b.resolvable) {
        return '<li class="refs-item plain">' + label + '</li>';
      }
      return '<li class="refs-item"><button type="button" class="refs-go"'
        + ' onclick="closeModal(\'modal-refs\');navigate(\'' + b.page + '\',' + it.id + ')">'
        + label
        + '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"'
        + ' aria-hidden="true"><polyline points="9 18 15 12 9 6"/></svg></button></li>';
    }).join('');

    return '<section class="refs-group' + (b.resolvable ? '' : ' keep') + '">'
      + '<h4 class="refs-title">' + escapeHtml(b.title)
      + '<span class="refs-count">' + (b.total || shown.length) + '건</span></h4>'
      + '<p class="refs-why">' + escapeHtml(b.why) + '</p>'
      + '<p class="refs-how"><b>' + (b.resolvable ? '조치' : '주의') + '</b> '
      + escapeHtml(b.howTo) + '</p>'
      + '<ul class="refs-list">' + rows + '</ul>'
      + (rest > 0 ? '<p class="refs-more">외 ' + rest + '건이 더 있습니다.</p>' : '')
      + '</section>';
  }).join('');
}

/**
 * 목적격 조사 — 받침이 있으면 "을", 없으면 "를".
 *
 * 이름을 문장에 끼워 넣는 자리라 "PROD-WAS 그룹 을(를) 지우려면" 처럼 두면
 * 기계가 쓴 티가 난다. 한글 음절의 받침은 유니코드로 계산할 수 있다 —
 * 가(0xAC00)부터 28개 단위로 종성이 돌아가므로 나머지가 0이면 받침이 없다.
 * (서버 쪽 OperatorApiController.objectParticle 과 같은 규칙)
 */
function objectParticle(word) {
  if (!word) return '을';
  const last = word.charCodeAt(word.length - 1);
  // 한글이 아니면 판단할 수 없다 — 영문 이름이 흔하므로 "을" 로 둔다.
  if (last < 0xAC00 || last > 0xD7A3) return '을';
  return (last - 0xAC00) % 28 === 0 ? '를' : '을';
}

/** 화면에 글자를 넣기 전에 태그로 읽히지 않게 막는다. */
function escapeHtml(t) {
  return String(t == null ? '' : t)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * 삭제 응답을 한 자리에서 처리한다.
 *
 * <p>막혔으면(409) 무엇이 걸려 있는지 바로 펼친다. 토스트만 띄우면 사용자는
 * 그 문장을 읽고 나서 스스로 어디를 봐야 할지 찾아야 한다.
 */
function handleDeleteResult(data, opts) {
  if (data && data.success) {
    showToast(data.message || '삭제했습니다.', 'success');
    if (typeof opts.onDone === 'function') opts.onDone();
    return true;
  }
  const msg = (data && data.message) || '삭제하지 못했습니다.';
  showToast(msg, 'error');
  // 참조 때문에 막힌 경우에만 펼친다. 권한 없음·대상 없음까지 열면
  // "무엇이 막고 있나" 목록이 비어 나와 오히려 헷갈린다.
  //   409 CONFLICT          서비스가 미리 막은 것(서버·그룹·정책)
  //   CONSTRAINT_VIOLATION  DB FK 가 막은 것
  const code = (data && data.errorCode) || '';
  const blocked = code === 'CONSTRAINT_VIOLATION' || code.indexOf('409') === 0;
  if (blocked && opts.type && opts.id != null) showBlockers(opts.type, opts.id, opts.retry);
  return false;
}

/* ESC 키로 열린 모달 닫기 */
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') {
    closeModal('modal-alert');
    closeModal('modal-confirm');
  }
});

/* ESC 로 .modal-overlay 계열(페이지별 추가/편집 모달 — project/user/server/team/policy/group 등)도 닫는다.
   각 모달을 각자 다른 함수 이름으로 닫아서(closeUserModal 등) 여기서 전부 알 필요는 없다 —
   이미 있는 우측 상단 × 버튼(.modal-close)을 그대로 눌러 원래 정리 로직(폼 리셋, 인터벌 해제 등)을
   그대로 태운다. role="dialog" 를 붙인 이상 Escape 로 나갈 방법이 있어야 한다. */
document.addEventListener('keydown', function(e) {
  if (e.key !== 'Escape') return;
  document.querySelectorAll('.modal-overlay').forEach(function(ov) {
    if (getComputedStyle(ov).display === 'none') return;
    const closeBtn = ov.querySelector('.modal-close');
    if (closeBtn) closeBtn.click();
  });
});

/* ─────────────────────────────────────────
   모달 (공통 — 간단 알림용)
───────────────────────────────────────── */
function showToast(msg, type = 'info') {
  const existing = document.getElementById('toast-container');
  if (!existing) {
    const wrap = document.createElement('div');
    wrap.id = 'toast-container';
    document.body.appendChild(wrap);
  }
  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.textContent = msg;
  document.getElementById('toast-container').appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('show'));
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}

/* ─────────────────────────────────────────
   이벤트 위임 — 모든 data-page 클릭 처리
───────────────────────────────────────── */
document.addEventListener('click', function(e) {
  /* data-page 라우팅 */
  const navTarget = e.target.closest('[data-page]');
  if (navTarget) {
    const pageId = navTarget.dataset.page;
    if (pageId) {
      /*
        사이드바 항목은 실제 href(/deploy/{pageId})를 갖는다.
        href 가 있어야 앵커가 키보드 포커스를 받고 스크린리더가 링크로 읽으며,
        가운데클릭·Ctrl+클릭으로 새 탭을 열 수 있다.

        보조키가 눌린 클릭은 브라우저에 넘긴다 — 새 탭으로 열려는 의도다.
        평범한 좌클릭만 가로채 SPA 전환으로 처리한다.
      */
      if (e.defaultPrevented) return;
      if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
      if (navTarget.tagName === 'A' && navTarget.getAttribute('href')) {
        e.preventDefault();
      }
      navigate(pageId);
      return;
    }
  }

  const link = e.target.closest('a[href]');
  if (link && !e.defaultPrevented && e.button === 0 && !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey
      && !link.hasAttribute('download') && (!link.target || link.target === '_self')) {
    const url = new URL(link.href, window.location.href);
    const route = url.pathname.match(/^\/deploy\/([a-z-]+)$/);
    if (route && url.origin === window.location.origin && !url.hash) {
      e.preventDefault();
      const params = new URLSearchParams(url.search), id = params.get('id'); params.delete('id');
      navigate(route[1], id, Object.fromEntries(params));
      return;
    }
  }

  /* 탭 버튼 */
  const tabBtn = e.target.closest('.tab-btn');
  if (tabBtn) {
    switchTab(tabBtn);
    return;
  }

  /* tr-link 행 클릭 — 내부 버튼/링크 클릭은 위에서 이미 처리됨 */
  const tr = e.target.closest('tr.tr-link');
  if (tr && !e.target.closest('button')) {
    const pageId = tr.dataset.page;
    if (pageId) navigate(pageId);
    return;
  }
});

document.addEventListener('submit', function (event) {
  const form = event.target;
  if (event.defaultPrevented || !form || (form.method || '').toLowerCase() !== 'get'
      || (form.target && form.target !== '_self')) return;
  const url = new URL(form.action, window.location.href);
  const route = url.pathname.match(/^\/deploy\/([a-z-]+)$/);
  if (!route || url.origin !== window.location.origin) return;
  event.preventDefault();
  const params = new URLSearchParams();
  new FormData(form).forEach((value, key) => { if (typeof value === 'string' && value) params.append(key, value); });
  const id = params.get('id'); params.delete('id');
  navigate(route[1], id, Object.fromEntries(params));
});

/* ─────────────────────────────────────────
   클릭만 되는 요소를 키보드로도 쓸 수 있게

   행 전체를 누르면 상세로 가는 표(.tr-link)와 사이드바 Assistant 위젯은 <tr>·<div> 라
   기본적으로 포커스를 받지 못한다. 마우스 없이는 도달할 수 없는 기능이 된다.

   앵커로 바꾸는 것이 정석이지만 <tr> 안에 <a> 를 씌울 수는 없다(표 구조가 깨진다).
   그래서 tabindex 로 탭 순서에 넣고 Enter/Space 를 클릭과 같게 처리한다.
   role="link" 은 "누르면 이동한다"를 스크린리더에 알린다.

   Space 는 기본 동작이 스크롤이라 눌린 그 자리에서 화면이 밀린다 — 막아야 한다.
───────────────────────────────────────── */
function makeClickablesFocusable(root) {
  (root || document).querySelectorAll(
    '.tr-link:not([tabindex]), .kpi-card:not([tabindex]), .approval-item:not([tabindex]), ' +
    'a.section-link:not([href]):not([tabindex]), [data-page]:not(a):not(button):not([tabindex])'
  ).forEach(el => {
    el.setAttribute('tabindex', '0');
    if (!el.hasAttribute('role')) el.setAttribute('role', 'link');
  });
}

document.addEventListener('keydown', function(e) {
  if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Spacebar') return;
  const el = e.target;
  if (!el || typeof el.matches !== 'function') return;
  if (!el.matches('.tr-link[tabindex], .kpi-card[tabindex], .approval-item[tabindex], ' +
                   'a.section-link[tabindex], [data-page][tabindex]')) return;
  e.preventDefault();
  el.click();
});

document.addEventListener('DOMContentLoaded', () => makeClickablesFocusable());

/* ─────────────────────────────────────────
   .modal-overlay 계열 모달 — 열릴 때 포커스를 안으로, 닫히면 원래 자리로.
   role="dialog" 를 붙인 이상 포커스가 뒤 화면에 남아있으면 스크린리더 사용자는
   대화상자가 열린 줄도 모른 채 가려진 배경을 계속 읽게 된다.

   페이지마다 서로 다른 함수로 모달을 여닫아서(openXxx/closeXxx, 통일된 진입점 없음)
   그 함수들은 손대지 않고 .modal-overlay 의 style 변화만 각각 감시한다.
   특정 입력칸을 짚지 않고 모달 컨테이너(.modal/.modal-box) 자체를 포커스한다 —
   필드를 잘못 짚어 원치 않는 동작(드롭다운이 열리는 등)을 건드리는 것보다 안전하다. */
function wireModalFocus(root) {
  (root || document).querySelectorAll('.modal-overlay:not([data-focus-wired])').forEach(ov => {
    ov.setAttribute('data-focus-wired', '1');
    let lastFocused = null;
    new MutationObserver(() => {
      const box = ov.querySelector('.modal, .modal-box');
      if (getComputedStyle(ov).display !== 'none') {
        lastFocused = (document.activeElement && document.activeElement !== document.body) ? document.activeElement : null;
        if (box) {
          if (!box.hasAttribute('tabindex')) box.setAttribute('tabindex', '-1');
          setTimeout(() => box.focus({ preventScroll: true }), 0);
        }
      } else if (lastFocused) {
        lastFocused.focus({ preventScroll: true });
        lastFocused = null;
      }
    }).observe(ov, { attributes: true, attributeFilter: ['style'] });
  });
}
document.addEventListener('DOMContentLoaded', () => wireModalFocus());

/* ─────────────────────────────────────────
   키보드 단축키 (ESC → 실행 패널 스크롤 복귀)
───────────────────────────────────────── */
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') {
    hideExecPanel();
  }
});

/* ═══════════════════════════════════════════
   LOGIN — 여기 있던 코드는 지웠다 (2026-08-03)

   시안 단계의 화면 전용 로그인이 그대로 남아 있었다. 아이디로 사용자를 찾고
   비밀번호를 자바스크립트에서 비교한 뒤 로그인 화면을 숨기는 방식이었고,
   시연용 계정 네 개의 비밀번호가 이 파일에 평문으로 들어 있었다.

   실제 인증은 Spring Security 폼 로그인(login.html 의 POST /login)이 한다.
   지운 코드가 찾던 login-id · login-pw · login-btn(실제로는 class 다) 요소는
   어느 화면에도 없어 전부 null 을 집었고, 진입점인 doLogin/quickLogin 을
   부르는 곳도 없었다. 즉 동작한 적 없는 코드가 자격 증명만 흘리고 있었다.

   되살릴 일이 있어도 여기서 하면 안 된다 — 비밀번호 비교는 서버 몫이다.
═══════════════════════════════════════════ */

/* ═══════════════════════════════════════════
   AI 설정
═══════════════════════════════════════════ */

/* 사이드바 AI 위젯을 갱신하던 updateAIWidget 은 지웠다 (2026-08-03).
   위젯 자체를 사이드바에서 걷어내면서 이 함수가 집던 #sidebar-ai-widget 과
   .sidebar-assistant-sub 가 사라져, 남아 있어도 첫 줄에서 그냥 돌아 나올 뿐이었다.
   AI 사용 여부는 서버가 렌더링 시점에 Assistant FAB 노출로 반영한다. */

/* ═══════════════════════════════════════════
   ASSISTANT 챗봇 (읽기 전용 · FAB)
   답변은 서버가 실시간 시스템 데이터를 근거로 LLM에게 생성시킴
═══════════════════════════════════════════ */
let _assistantHistory = [];
let _assistantLoaded = false;

function toggleAssistantChat() {
  const c = document.getElementById('assistant-chat');
  if (!c) return;
  c.classList.toggle('open');
  if (c.classList.contains('open')) {
    _dcRestoreSize(c);
    _dcLoadHistory();
    setTimeout(() => document.getElementById('assistant-chat-text')?.focus(), 50);
  }
}

/* 최초 열 때 서버에 저장된 이전 대화를 불러와 렌더 */
function _dcLoadHistory() {
  if (_assistantLoaded) return;
  _assistantLoaded = true;
  fetch('/api/v1/ai/chat/history')
    .then(r => r.json())
    .then(res => {
      const list = (res && res.data) || [];
      if (!list.length) return;
      const body = document.getElementById('assistant-chat-body');
      if (body) body.innerHTML = '';
      _assistantHistory = [];
      list.forEach(m => {
        const role = m.role === 'user' ? 'user' : 'bot';
        _dcAppend(role, m.content);
        _assistantHistory.push({ role: role, text: m.content });
      });
    })
    .catch(() => {});
}

function _dcRestoreSize(c) {
  try {
    const s = JSON.parse(localStorage.getItem('assistant_chat_size') || 'null');
    if (s && s.w && s.h) { c.style.width = s.w + 'px'; c.style.height = s.h + 'px'; }
  } catch (e) {}
}

/* 좌상단 핸들 드래그로 크기 조절(우하단 고정이라 좌/상으로 커짐) + 크기 기억 */
function dcResizeStart(e) {
  e.preventDefault();
  const c = document.getElementById('assistant-chat');
  const startX = e.clientX, startY = e.clientY;
  const rect = c.getBoundingClientRect();
  const startW = rect.width, startH = rect.height;
  const minW = 320, minH = 380;
  const maxW = window.innerWidth - 32, maxH = window.innerHeight - 100;
  function onMove(ev) {
    const w = Math.max(minW, Math.min(maxW, startW + (startX - ev.clientX)));
    const h = Math.max(minH, Math.min(maxH, startH + (startY - ev.clientY)));
    c.style.width = w + 'px';
    c.style.height = h + 'px';
  }
  function onUp() {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    const r = c.getBoundingClientRect();
    try { localStorage.setItem('assistant_chat_size', JSON.stringify({ w: Math.round(r.width), h: Math.round(r.height) })); } catch (e) {}
  }
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

function assistantChatKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendAssistantChat(); }
}

/* 입력창 자동 높이 */
function dcAutoGrow(el) {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 96) + 'px';
}

/* HTML 이스케이프 (XSS 방지) */
function _dcEsc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/* 가벼운 안전 마크다운 → HTML: 코드블록/인라인코드/굵게/기울임/제목/목록/문단 */
function dcMarkdown(src) {
  const blocks = [];
  // 1) 코드펜스 추출 → 고유 토큰 플레이스홀더(@@CB<n>@@)
  let t = String(src).replace(/```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)```/g, (m, lang, code) => {
    blocks.push({ lang: lang || '', code: code.replace(/\n+$/, '') });
    return '@@CB' + (blocks.length - 1) + '@@';
  });
  // 2) 나머지 이스케이프(XSS 방지)
  t = _dcEsc(t);
  // 3) 인라인 코드
  t = t.replace(/`([^`\n]+)`/g, (m, c) => '<code class="dc-ic">' + c + '</code>');
  // 4) 굵게 / 기울임
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/(^|[^*])\*(?!\s)([^*\n]+?)\*(?!\*)/g, '$1<em>$2</em>');
  // 5) 제목(#) → 소제목
  t = t.replace(/^\s{0,3}#{1,6}\s+(.+)$/gm, '<div class="dc-h">$1</div>');
  // 6) 블록(목록/문단)
  t = _dcBlocks(t);
  // 7) 코드블록 복원
  t = t.replace(/@@CB(\d+)@@/g, (m, i) => {
    const b = blocks[+i];
    if (!b) return '';
    return '<div class="dc-cb"><div class="dc-cb-top"><span>' + _dcEsc(b.lang || 'text') +
      '</span><button type="button" class="dc-copy" onclick="dcCopyCode(this)">복사</button></div>' +
      '<pre><code>' + _dcEsc(b.code) + '</code></pre></div>';
  });
  return t;
}

function _dcListMatch(s) {
  const m = s.match(/^(\s*)([-*]|\d+[.)])\s+(.*)$/);
  if (!m) return null;
  return { indent: m[1].replace(/\t/g, '  ').length, ordered: /\d/.test(m[2]), content: m[3] };
}

/* 들여쓰기 기반 중첩 리스트(연속 번호 유지) */
function _dcRenderList(items, start) {
  const base = items[start].indent;
  const ordered = items[start].ordered;
  let html = ordered ? '<ol class="dc-ol">' : '<ul class="dc-ul">';
  let i = start;
  while (i < items.length && items[i].indent >= base) {
    if (items[i].indent > base) {
      const sub = _dcRenderList(items, i);
      html = html.replace(/<\/li>$/, sub.html + '</li>');
      i = sub.next;
    } else {
      html += '<li>' + items[i].content + '</li>';
      i++;
    }
  }
  html += ordered ? '</ol>' : '</ul>';
  return { html: html, next: i };
}

function _dcBlocks(text) {
  const lines = text.split('\n');
  const isPH = s => /^@@CB\d+@@$/.test(s.trim());
  const isH = s => /^<div class="dc-h">/.test(s);
  let html = '', i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (isPH(line)) { html += line.trim(); i++; continue; }
    if (isH(line)) { html += line; i++; continue; }
    if (line.trim() === '') { i++; continue; }
    if (_dcListMatch(line)) {
      const items = [];
      while (i < lines.length) {
        const lm = _dcListMatch(lines[i]);
        if (lm) { items.push(lm); i++; continue; }
        // 항목 사이 빈 줄: 다음 비어있지 않은 줄도 리스트면 같은 리스트로 이어감(번호 연속 유지)
        if (lines[i].trim() === '') {
          let j = i + 1;
          while (j < lines.length && lines[j].trim() === '') j++;
          if (j < lines.length && _dcListMatch(lines[j])) { i = j; continue; }
        }
        break;
      }
      html += _dcRenderList(items, 0).html;
      continue;
    }
    const para = [];
    while (i < lines.length && lines[i].trim() !== '' && !_dcListMatch(lines[i]) && !isH(lines[i]) && !isPH(lines[i])) {
      para.push(lines[i]); i++;
    }
    html += '<p>' + para.join('<br>') + '</p>';
  }
  return html;
}
function dcCopyCode(btn) {
  const code = btn.closest('.dc-cb')?.querySelector('code');
  if (!code || !navigator.clipboard) return;
  navigator.clipboard.writeText(code.innerText).then(() => {
    const old = btn.textContent;
    btn.textContent = '복사됨'; btn.classList.add('done');
    setTimeout(() => { btn.textContent = old; btn.classList.remove('done'); }, 1400);
  });
}

/* 메시지 추가 — bot은 마크다운 렌더, user는 escape 후 표시 */
function _dcAppend(role, text) {
  const body = document.getElementById('assistant-chat-body');
  const row = document.createElement('div');
  row.className = 'dc-row ' + (role === 'user' ? 'user' : 'bot');
  if (role === 'bot') {
    row.innerHTML =
      '<div class="dc-ava"><img src="/img/assistant-mark.svg" alt=""></div>' +
      '<div class="dc-bubble bot">' + dcMarkdown(text) + '</div>';
  } else {
    const bubble = document.createElement('div');
    bubble.className = 'dc-bubble user';
    bubble.textContent = text;
    row.appendChild(bubble);
  }
  body.appendChild(row);
  body.scrollTop = body.scrollHeight;
  return row;
}

/* 타이핑 인디케이터 */
function _dcTyping() {
  const body = document.getElementById('assistant-chat-body');
  const row = document.createElement('div');
  row.className = 'dc-row bot';
  row.innerHTML =
    '<div class="dc-ava"><img src="/img/assistant-mark.svg" alt=""></div>' +
    '<div class="dc-bubble bot dc-typing"><span></span><span></span><span></span></div>';
  body.appendChild(row);
  body.scrollTop = body.scrollHeight;
  return row;
}

function sendAssistantChat() {
  const ta = document.getElementById('assistant-chat-text');
  const msg = (ta.value || '').trim();
  if (!msg) return;
  ta.value = ''; dcAutoGrow(ta);
  _dcAppend('user', msg);
  const btn = document.getElementById('assistant-send');
  btn.disabled = true;
  const typing = _dcTyping();

  const csrf = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfH = document.querySelector('meta[name="_csrf_header"]')?.content;
  const history = _assistantHistory.slice(-6)
    .map(m => (m.role === 'user' ? '사용자: ' : 'Assistant: ') + m.text).join('\n');

  fetch('/api/v1/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [csrfH]: csrf },
    body: JSON.stringify({ message: msg, history: history })
  })
    .then(r => r.json())
    .then(d => {
      typing.remove();
      const ans = d.success ? (d.data || '(빈 응답)') : ('⚠️ ' + (d.message || '응답에 실패했습니다.'));
      _dcAppend('bot', ans);
      if (d.success) {
        _assistantHistory.push({ role: 'user', text: msg });
        _assistantHistory.push({ role: 'bot', text: ans });
      }
    })
    .catch(() => { typing.remove(); _dcAppend('bot', '요청에 실패했습니다.'); })
    .finally(() => { btn.disabled = false; });
}

/* ── 헬퍼 ── */
function _setVal(id, value) {
  const el = document.getElementById(id);
  if (!el) return;
  if (el.tagName === 'SELECT') {
    for (const opt of el.options) {
      if (opt.value === value || opt.textContent.startsWith(value)) {
        el.value = opt.value;
        break;
      }
    }
  } else {
    el.value = value;
  }
}

/* ─────────────────────────────────────────
   페이지 초기화
───────────────────────────────────────── */
document.addEventListener('DOMContentLoaded', function() {
  /* 앱 영역은 숨기고 로그인 화면만 보여준다 (enterApp() 이 navigate 호출) */

  /* 토스트 스타일 동적 삽입 */
  const style = document.createElement('style');
  style.textContent = `
    #toast-container {
      position: fixed; bottom: 24px; right: 24px;
      display: flex; flex-direction: column; gap: 8px; z-index: 9999;
    }
    .toast {
      padding: 10px 18px; border-radius: 8px; font-size: 13px; font-weight: 500;
      background: #1e293b; color: #f1f5f9;
      box-shadow: 0 4px 16px rgba(0,0,0,.25);
      opacity: 0; transform: translateY(8px);
      transition: opacity .25s, transform .25s;
    }
    .toast.show { opacity: 1; transform: translateY(0); }
    .toast.toast-success { background: #059669; }
    .toast.toast-error   { background: #dc2626; }
    .toast.toast-warn    { background: #d97706; }
    .bc-parent { opacity: .55; }
    .tab-panel { display: none; }
    .tab-panel.active { display: block; }
    .exec-row.selected { background: rgba(37,99,235,.06); }
    .btn.loading { opacity: .6; cursor: not-allowed; }
    #log-panel .log-line { display: block; line-height: 1.6; white-space: pre; }
    tr.tr-link { cursor: pointer; }
    tr.tr-link:hover td { background: rgba(37,99,235,.04); }
  `;
  document.head.appendChild(style);
});

/* ─────────────────────────────────────────
   잘못 입력한 칸 짚어 주기

   "요청 형식이 올바르지 않습니다" 만 띄우면 어느 칸이 문제인지 알 수 없다.
   해당 칸을 붉게 물들이고 한 번 흔들어 눈이 그리로 가게 한다.
   흔들림은 애니메이션이라 prefers-reduced-motion 이면 전역 규칙이 멈춘다 —
   그때도 붉은 테두리는 남으므로 정보가 사라지지는 않는다.
───────────────────────────────────────── */
function markInvalid(el, message) {
  if (!el) return;
  // 커스텀 드롭다운은 진짜 입력칸이 숨어 있다 — 눈에 보이는 버튼을 짚어야 한다.
  const target = el.closest('.cs') ? el.closest('.cs').querySelector('.cs-btn') : el;
  target.classList.remove('is-invalid', 'shake');
  void target.offsetWidth;                 // 애니메이션 재시작을 위해 리플로우를 한 번 강제한다
  target.classList.add('is-invalid', 'shake');
  target.setAttribute('aria-invalid', 'true');
  if (message) target.setAttribute('title', message);
  target.addEventListener('animationend', () => target.classList.remove('shake'), { once: true });
  // 다시 건드리면 표시를 지운다 — 고치는 중에도 붉게 남아 있으면 잔소리로 느껴진다.
  const clear = () => {
    target.classList.remove('is-invalid');
    target.removeAttribute('aria-invalid');
    target.removeAttribute('title');
  };
  el.addEventListener('input', clear, { once: true });
  el.addEventListener('change', clear, { once: true });
  try { target.focus({ preventScroll: false }); } catch (e) { /* 포커스 불가 요소는 그냥 둔다 */ }
}

/** 서버가 돌려준 fieldErrors 를 화면 칸에 붙인다. 못 찾으면 토스트만 띄운다. */
function applyFieldErrors(res, idMap) {
  const errs = (res && res.fieldErrors) || [];
  let marked = false;
  errs.forEach(fe => {
    const id = (idMap && idMap[fe.field]) || fe.field;
    const el = document.getElementById(id);
    if (el) { markInvalid(el, fe.message); marked = true; }
  });
  return marked;
}

/* 선택 표시의 좌표와 스크롤은 해당 그룹 안에서만 바꾼다. */
(function () {
  'use strict';
  if (window.DeployChoiceUi) return;
  const selector = '.ds-section-tabs,.ds-segmented';
  const pending = new Set(), timers = new WeakMap(), layouts = new WeakMap();
  let frame = 0;
  function paint(group) {
    if (!group?.isConnected || !group.matches(selector) || !group.getClientRects().length) return;
    const selected = [...group.children].find(el => el.matches('button,a') &&
      el.matches('.active,[aria-selected="true"],[aria-pressed="true"],[aria-current="page"]'));
    if (!selected || !selected.getClientRects().length) return;
    const bounds = group.getBoundingClientRect(), item = selected.getBoundingClientRect();
    const left = bounds.left + group.clientLeft, right = left + group.clientWidth;
    let next = group.scrollLeft;
    if (item.left < left) next += item.left - left;
    else if (item.right > right) next += item.right - right;
    if (Math.abs(next - group.scrollLeft) > .5) group.scrollTo({left: next, top: group.scrollTop, behavior: 'instant'});
    const box = selected.getBoundingClientRect(), base = group.getBoundingClientRect();
    const pill = group.getAttribute('data-choice-kind') === 'segment';
    const x = box.left - base.left - group.clientLeft + group.scrollLeft;
    const y = box.top - base.top - group.clientTop + group.scrollTop + (pill ? 0 : box.height - 3);
    const height = pill ? box.height : 3;
    let indicator = group.querySelector(':scope > .ds-choice-indicator');
    const fresh = !indicator;
    if (fresh) {
      indicator = document.createElement('span');
      indicator.className = 'ds-choice-indicator';
      indicator.setAttribute('aria-hidden', 'true');
      group.appendChild(indicator);
    }
    const transform = `translate(${x}px, ${y}px)`, previous = layouts.get(indicator);
    const changed = !previous || Math.abs(previous.x-x)>.25 || Math.abs(previous.y-y)>.25 || Math.abs(previous.width-box.width)>.25 || Math.abs(previous.height-height)>.25;
    if (!fresh && changed) indicator.getBoundingClientRect(); // 재생성된 선택창에서도 이전 위치를 먼저 확정한다.
    indicator.style.transform = transform;
    indicator.style.width = box.width + 'px';
    indicator.style.height = height + 'px';
    layouts.set(indicator, {x,y,width:box.width,height});
    if (fresh) {
      indicator.getBoundingClientRect();
      indicator.dataset.ready = 'true';
    }
    if (pill && !fresh && changed) {
      clearTimeout(timers.get(group));
      const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      group.classList.toggle('ds-choice-moving', !reduce);
      if (!reduce) timers.set(group, setTimeout(() => group.classList.remove('ds-choice-moving'), 200));
    }
  }
  function update(root) {
    if (!root) return;
    if (root.matches?.(selector)) pending.add(root);
    root.querySelectorAll?.(selector).forEach(group => pending.add(group));
    if (!frame && pending.size) frame = requestAnimationFrame(() => {
      frame = 0;
      const groups = [...pending]; pending.clear(); groups.forEach(paint);
    });
  }
  window.DeployChoiceUi = {update};
  function init() {
    update(document);
    new MutationObserver(records => {
      for (const record of records) {
        const group = record.target.closest?.(selector);
        if (group) update(group);
        if (record.type === 'childList') record.addedNodes.forEach(node => {if (node.nodeType === 1) update(node);});
      }
    }).observe(document.body, {subtree:true, childList:true, attributes:true, attributeFilter:['class','aria-selected','aria-pressed','aria-current']});
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, {once:true});
  else init();
  window.addEventListener('resize', () => update(document));
})();
