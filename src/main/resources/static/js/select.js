/* =====================================================
   DeployOps — select.js
   커스텀 드롭다운. 의존성 0.

   ■ 왜 네이티브 <select> 를 남겨 두나

     펼쳐진 option 목록은 OS 가 그려서 CSS 가 닿지 않는다. 그래서 직접 그린다.
     그런데 화면 곳곳에서 select 값을 읽는 코드가 191곳이고(getElementById(...).value),
     onchange 핸들러도 그만큼 붙어 있다. select 를 걷어내면 그게 전부 깨진다.

     그래서 <b>대체하지 않고 덮는다</b> — 네이티브 select 는 값 보관소로 그대로 두고
     (탭 순서에서만 빼고 화면에서 숨긴다), 그 위에 우리가 그린 버튼과 목록을 얹는다.
     고르면 select.value 를 바꾸고 change 이벤트를 직접 쏜다. 기존 코드는 아무것도 모른다.

   ■ 목록을 body 로 띄우는 이유

     모달은 overflow:auto 다. 목록을 제자리에 그리면 모달 경계에서 잘린다.
     position:fixed 로 body 에 붙이고 좌표를 계산해 얹는다.

   ■ 접근성

     WAI-ARIA 콤보박스(listbox 팝업) 패턴을 따른다 —
     role=combobox/listbox/option · aria-expanded · aria-activedescendant · aria-selected.
     키보드: Enter/Space/↓ 열기, ↑↓ 이동, Home/End, 글자 입력으로 바로 찾기,
     Esc 닫기, Tab 닫고 다음으로. 네이티브 select 로 할 수 있던 것은 전부 된다.
===================================================== */

(function () {
  'use strict';

  var openOne = null;   // 지금 열려 있는 것(한 번에 하나만)
  var seq = 0;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /** 이 select 를 커스텀으로 바꿔도 되는가. */
  function enhanceable(sel) {
    return sel
      && sel.tagName === 'SELECT'
      && !sel.multiple                       // 다중 선택은 다른 패턴이다 — 손대지 않는다
      && !sel.dataset.csDone
      && !sel.hasAttribute('data-no-custom-select');
  }

  function build(sel) {
    sel.dataset.csDone = '1';
    var id = 'cs' + (++seq);

    var wrap = document.createElement('div');
    wrap.className = 'cs';
    // 원래 select 의 레이아웃 폭을 그대로 물려받아야 화면이 흔들리지 않는다.
    if (sel.style.width) wrap.style.width = sel.style.width;
    if (sel.style.minWidth) wrap.style.minWidth = sel.style.minWidth;

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'cs-btn ' + (sel.className || '');
    btn.id = id + '-btn';
    btn.setAttribute('role', 'combobox');
    btn.setAttribute('aria-haspopup', 'listbox');
    btn.setAttribute('aria-expanded', 'false');
    btn.setAttribute('aria-controls', id + '-list');
    if (sel.disabled) btn.disabled = true;

    // 라벨 연결 — <label for="선택id"> 가 이미 있으므로 그걸 그대로 가리킨다.
    var lbl = sel.id && document.querySelector('label[for="' + CSS.escape(sel.id) + '"]');
    if (lbl) {
      if (!lbl.id) lbl.id = id + '-label';
      btn.setAttribute('aria-labelledby', lbl.id + ' ' + id + '-btn');
      // 라벨을 눌렀을 때 커스텀 버튼으로 포커스가 가게 한다(네이티브는 숨겨져 있다).
      lbl.addEventListener('click', function (e) { e.preventDefault(); btn.focus(); });
    }

    var txt = document.createElement('span');
    txt.className = 'cs-txt';
    btn.appendChild(txt);

    var arrow = document.createElement('span');
    arrow.className = 'cs-arrow';
    arrow.setAttribute('aria-hidden', 'true');
    arrow.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"'
      + ' stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
    btn.appendChild(arrow);

    sel.parentNode.insertBefore(wrap, sel);
    wrap.appendChild(btn);
    wrap.appendChild(sel);            // 값 보관소로 안에 넣어 둔다
    sel.classList.add('cs-native');
    sel.setAttribute('tabindex', '-1');
    sel.setAttribute('aria-hidden', 'true');

    var list = document.createElement('div');
    list.className = 'cs-list';
    list.id = id + '-popup';
    list.hidden = true;

    var api = { sel: sel, wrap: wrap, btn: btn, txt: txt, list: list, id: id, active: -1, typed: '', typedAt: 0 };
    sel._cs = api;

    btn.addEventListener('keydown', function (e) { onKey(api, e); });
    btn.addEventListener('click', function () { toggle(api); });
    // 화면 코드가 select.value 를 직접 바꾸는 경우가 있다 — 그때도 버튼 글자를 맞춘다.
    sel.addEventListener('change', function () { syncText(api); });

    syncText(api);
    return api;
  }

  function options(api) {
    return Array.prototype.filter.call(api.sel.options, function (o) { return !o.disabled; });
  }

  function syncText(api) {
    var o = api.sel.selectedOptions && api.sel.selectedOptions[0];
    var label = o ? (o.textContent || '').trim() : '';
    api.txt.textContent = label;
    // 값이 비어 있는 옵션(— 선택하세요 —)은 자리표시자로 취급해 흐리게 둔다.
    api.wrap.classList.toggle('is-placeholder', !o || o.value === '');
  }

  function render(api) {
    var opts = Array.prototype.slice.call(api.sel.options);
    var searchable = opts.filter(function (o) { return !o.hidden && !o.disabled; }).length >= 10;
    api.list.innerHTML = (searchable ? '<div class="cs-search"><input type="search" role="combobox" aria-expanded="true" aria-autocomplete="list" autocomplete="off" placeholder="검색" aria-label="선택 항목 검색" aria-controls="' + api.id + '-list"></div>' : '')
      + '<div id="' + api.id + '-list" role="listbox">' + opts.map(function (o, i) {
      var on = o.selected;
      return '<div class="cs-opt' + (on ? ' on' : '') + (o.disabled ? ' off' : '') + '"'
        + ' id="' + api.id + '-o' + i + '"'
        + ' role="option" aria-selected="' + (on ? 'true' : 'false') + '"'
        + (o.disabled ? ' aria-disabled="true"' : '')
        + ' data-i="' + i + '"' + (o.hidden ? ' hidden' : '') + '>'
        + '<span class="cs-opt-txt">' + esc((o.textContent || '').trim()) + '</span>'
        + '<svg class="cs-opt-check" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
        + ' stroke-width="3" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>'
        + '</div>';
    }).join('') + '</div><p class="cs-empty" role="status" hidden>검색 결과가 없습니다.</p>';

    var search = api.list.querySelector('.cs-search input');
    if (search) {
      search.addEventListener('input', function () {
        var query = search.value.trim().toLocaleLowerCase();
        api.list.querySelectorAll('.cs-opt').forEach(function (el) {
          var option = api.sel.options[+el.dataset.i];
          el.hidden = option.hidden || (option.textContent || '').toLocaleLowerCase().indexOf(query) < 0;
        });
        var first = api.list.querySelector('.cs-opt:not([hidden]):not(.off)');
        api.list.querySelector('.cs-empty').hidden = !!api.list.querySelector('.cs-opt:not([hidden])');
        setActive(api, first ? +first.dataset.i : -1, false);
        place(api);
      });
      search.addEventListener('keydown', function (event) {
        if (['ArrowDown', 'ArrowUp', 'Enter', 'Escape', 'Tab'].indexOf(event.key) !== -1) onKey(api, event);
      });
    }

    api.list.querySelectorAll('.cs-opt').forEach(function (el) {
      el.addEventListener('mousedown', function (e) { e.preventDefault(); });  // 버튼 포커스 유지
      el.addEventListener('click', function () {
        var i = +el.dataset.i;
        if (api.sel.options[i].disabled) return;
        pick(api, i);
        close(api, true);
      });
      el.addEventListener('mousemove', function () { setActive(api, +el.dataset.i, false); });
    });
  }

  /** 목록 위치 — 아래가 좁으면 위로 띄운다. */
  function place(api) {
    var r = api.btn.getBoundingClientRect();
    var below = window.innerHeight - r.bottom - 8;
    var above = r.top - 8;
    var max = 260;
    var h = Math.min(max, api.list.scrollHeight + 2);

    api.list.style.width = r.width + 'px';
    api.list.style.left = r.left + 'px';
    if (below >= Math.min(h, 160) || below >= above) {
      api.list.style.top = (r.bottom + 4) + 'px';
      api.list.style.maxHeight = Math.min(max, below) + 'px';
      api.list.classList.remove('up');
    } else {
      api.list.style.top = 'auto';
      api.list.style.bottom = (window.innerHeight - r.top + 4) + 'px';
      api.list.style.maxHeight = Math.min(max, above) + 'px';
      api.list.classList.add('up');
    }
  }

  function open(api) {
    if (openOne && openOne !== api) close(openOne, false);
    if (api.btn.disabled) return;
    render(api);
    document.body.appendChild(api.list);
    api.list.hidden = false;
    api.list.style.bottom = '';
    place(api);
    api.btn.setAttribute('aria-expanded', 'true');
    api.wrap.classList.add('open');
    openOne = api;
    setActive(api, api.sel.selectedIndex >= 0 ? api.sel.selectedIndex : 0, true);
    var search = api.list.querySelector('.cs-search input');
    if (search) search.focus({ preventScroll: true });
  }

  function close(api, focusBtn) {
    api.list.hidden = true;
    if (api.list.parentNode) api.list.parentNode.removeChild(api.list);
    api.btn.setAttribute('aria-expanded', 'false');
    api.btn.removeAttribute('aria-activedescendant');
    api.wrap.classList.remove('open');
    if (openOne === api) openOne = null;
    if (focusBtn) api.btn.focus();
  }

  function toggle(api) { (openOne === api) ? close(api, true) : open(api); }

  function setActive(api, i, scroll) {
    var els = api.list.querySelectorAll('.cs-opt');
    if (!els.length) return;
    var selectable = Array.prototype.filter.call(els, function (el) { return !el.hidden && !el.classList.contains('off'); });
    if (!selectable.length) {
      api.active = -1;
      els.forEach(function (el) { el.classList.remove('active'); });
      api.btn.removeAttribute('aria-activedescendant');
      api.list.querySelector('.cs-search input')?.removeAttribute('aria-activedescendant');
      return;
    }
    var chosen = selectable.find(function (el) { return +el.dataset.i === i; });
    if (!chosen) chosen = selectable.find(function (el) { return +el.dataset.i >= i; }) || selectable[selectable.length - 1];
    i = +chosen.dataset.i;
    api.active = i;
    els.forEach(function (el, n) { el.classList.toggle('active', n === i); });
    api.btn.setAttribute('aria-activedescendant', els[i].id);
    api.list.querySelector('.cs-search input')?.setAttribute('aria-activedescendant', els[i].id);
    if (scroll !== false) els[i].scrollIntoView({ block: 'nearest' });
  }

  /** 실제 선택 — 네이티브 값을 바꾸고 change 를 쏜다. 기존 onchange 코드가 이걸로 동작한다. */
  function pick(api, i) {
    if (i < 0 || !api.sel.options[i] || api.sel.options[i].disabled || api.sel.options[i].hidden) return;
    if (api.sel.selectedIndex === i) { syncText(api); return; }
    api.sel.selectedIndex = i;
    syncText(api);
    api.sel.dispatchEvent(new Event('input', { bubbles: true }));
    api.sel.dispatchEvent(new Event('change', { bubbles: true }));
  }

  /** 닫힌 채로 방향키를 누르면 네이티브처럼 값이 바로 바뀐다. */
  function step(api, delta) {
    var n = api.sel.options.length;
    var i = api.sel.selectedIndex;
    for (var k = 0; k < n; k++) {
      i += delta;
      if (i < 0 || i >= n) return;
      if (!api.sel.options[i].disabled && !api.sel.options[i].hidden) { pick(api, i); return; }
    }
  }

  /** 글자를 입력하면 그걸로 시작하는 항목으로 간다(네이티브 select 와 같은 동작). */
  function typeAhead(api, ch) {
    var now = Date.now();
    api.typed = (now - api.typedAt < 900) ? (api.typed + ch) : ch;
    api.typedAt = now;
    var q = api.typed.toLowerCase();
    var opts = api.sel.options;
    for (var i = 0; i < opts.length; i++) {
      if (opts[i].disabled || opts[i].hidden) continue;
      if ((opts[i].textContent || '').trim().toLowerCase().indexOf(q) === 0) {
        if (openOne === api) setActive(api, i, true); else pick(api, i);
        return;
      }
    }
  }

  function onKey(api, e) {
    var isOpen = openOne === api;
    switch (e.key) {
      case 'Enter':
      case ' ':
        e.preventDefault();
        if (isOpen) { if (api.active >= 0) { pick(api, api.active); close(api, true); } } else open(api);
        return;
      case 'Escape':
        if (isOpen) { e.preventDefault(); e.stopPropagation(); close(api, true); }
        return;
      case 'Tab':
        if (isOpen) close(api, false);
        return;
      case 'ArrowDown':
        e.preventDefault();
        if (isOpen) setActive(api, api.active + 1, true); else open(api);
        return;
      case 'ArrowUp':
        e.preventDefault();
        if (isOpen) {
          var previous = Array.prototype.filter.call(api.list.querySelectorAll('.cs-opt'), function (el) { return !el.hidden && !el.classList.contains('off') && +el.dataset.i < api.active; }).pop();
          if (previous) setActive(api, +previous.dataset.i, true);
        } else step(api, -1);
        return;
      case 'Home':
        if (isOpen) { e.preventDefault(); setActive(api, 0, true); }
        return;
      case 'End':
        if (isOpen) { e.preventDefault(); setActive(api, api.sel.options.length - 1, true); }
        return;
      default:
        if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) typeAhead(api, e.key);
    }
  }

  /* ── 바깥 클릭 · 스크롤 · 리사이즈 ── */
  document.addEventListener('mousedown', function (e) {
    if (!openOne) return;
    if (openOne.wrap.contains(e.target) || openOne.list.contains(e.target)) return;
    close(openOne, false);
  });
  // 목록 내부의 스크롤은 선택 동작이다. 배경이 스크롤되어 버튼 위치가 바뀔 때만 닫는다.
  window.addEventListener('scroll', function (event) {
    if (!openOne) return;
    if (event.target === openOne.list
        || (event.target && event.target.nodeType && openOne.list.contains(event.target))) return;
    close(openOne, false);
  }, true);
  window.addEventListener('resize', function () { if (openOne) place(openOne); });

  /* ── 적용 ── */
  function scan(root) {
    (root || document).querySelectorAll('select').forEach(function (sel) {
      if (enhanceable(sel)) build(sel);
    });
  }

  // 화면은 SPA 로 갈아 끼워지고 모달도 나중에 그려진다 — 새로 들어오는 select 를 계속 잡는다.
  var mo = new MutationObserver(function (muts) {
    for (var i = 0; i < muts.length; i++) {
      var added = muts[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var n = added[j];
        if (n.nodeType !== 1) continue;
        if (n.tagName === 'SELECT') { if (enhanceable(n)) build(n); }
        else if (n.querySelectorAll) scan(n);
      }
    }
  });

  function start() {
    scan(document);
    mo.observe(document.body, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();

  function sync(select) {
    if (typeof select === 'string') select = document.getElementById(select);
    if (!select || !select._cs) return false;
    syncText(select._cs);
    return true;
  }

  window.DeploySelect = { scan: scan, sync: sync };
})();
