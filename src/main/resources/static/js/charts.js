/* =====================================================
   DeployOps — charts.js
   의존성 0 인 인라인 SVG 차트.

   왜 라이브러리를 쓰지 않나
     1) 폐쇄망이다. 외부 CDN 을 쓰지 않는 것이 이 시스템의 원칙이고(CSP default-src 'self'),
        로컬 번들로 넣으면 오프라인 설치 번들에 바이너리가 하나 더 붙는다.
     2) 대부분의 차트 라이브러리는 <canvas> 로 그린다. canvas 안의 내용은 보조기술에
        아무것도 노출되지 않는다 — 화면 전체를 WCAG AA 로 맞춰 놓고 대시보드만 구멍이 난다.
     3) 여기서 필요한 것은 꺾은선·막대·도넛 셋뿐이다.

   접근성 규칙 (이 파일의 모든 차트가 지킨다)
     - <svg role="img"> + <title> + <desc> — 스크린리더가 "무슨 그림인지" 읽는다
     - 그림 옆에 sr-only 표를 함께 낸다 — 값을 하나씩 읽어야 하는 사용자를 위한 것.
       요약문만으로는 "6월 3일에 몇 건이었나" 에 답할 수 없다.
     - 색만으로 구분하지 않는다. 실패는 색 + 빗금(pattern) 으로 이중 표기한다.
===================================================== */

(function (global) {
  'use strict';

  const NS = 'http://www.w3.org/2000/svg';
  let uid = 0;
  const nextId = p => `${p}-${++uid}`;

  const el = (name, attrs) => {
    const n = document.createElementNS(NS, name);
    for (const k in attrs) {
      if (attrs[k] !== null && attrs[k] !== undefined) n.setAttribute(k, attrs[k]);
    }
    return n;
  };

  const esc = s => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  /* 계열 색 — styles.css 토큰과 같은 값. 차트만 다른 초록을 쓰면 화면이 두 벌로 보인다. */
  const COLOR = {
    succeeded: '#059669',
    failed: '#dc2626',
    rolledBack: '#d97706',
    inProgress: '#94a3b8',
    cancelled: '#6366f1',
    axis: '#cbd5e1',
    text: '#64748b',
  };
  const LABEL = {
    succeeded: '성공',
    failed: '실패',
    rolledBack: '롤백',
    inProgress: '진행/대기',
    cancelled: '철회',
  };

  /** 실패 계열에 씌우는 빗금 — 색각 이상에서도 초록/빨강이 구분되게 한다. */
  function hatchPattern(defs, id, color) {
    const p = el('pattern', {
      id, width: 5, height: 5, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)',
    });
    p.appendChild(el('rect', { width: 5, height: 5, fill: color }));
    p.appendChild(el('rect', { width: 2, height: 5, fill: 'rgba(255,255,255,.45)' }));
    defs.appendChild(p);
    return `url(#${id})`;
  }

  /** 그림 옆에 붙는, 눈에는 안 보이지만 읽히는 표. */
  function srTable(caption, headers, rows) {
    // ★ sr-only 를 <table> 에 직접 걸면 안 된다.
    //   display:table 은 height:1px 과 overflow:hidden 을 무시해 실제 높이를 그대로 차지한다.
    //   position:absolute 라 그 높이가 문서 스크롤 영역까지 늘리는데 —
    //   실제로 대시보드에서 페이지가 262px 스크롤되며 사이드바 아래에 빈 공간이 생겼다.
    //   블록 요소로 감싸면 그 요소가 규칙을 지켜 준다.
    const wrap = document.createElement('div');
    wrap.className = 'sr-only';
    const t = document.createElement('table');
    t.innerHTML =
      `<caption>${esc(caption)}</caption><thead><tr>` +
      headers.map(h => `<th scope="col">${esc(h)}</th>`).join('') +
      '</tr></thead><tbody>' +
      rows.map(r => '<tr>' + r.map((c, i) =>
        i === 0 ? `<th scope="row">${esc(c)}</th>` : `<td>${esc(c)}</td>`).join('') + '</tr>').join('') +
      '</tbody>';
    wrap.appendChild(t);
    return wrap;
  }

  function mountFigure(host, svg, table) {
    host.innerHTML = '';
    host.appendChild(svg);
    if (table) host.appendChild(table);
  }

  /* ─────────────────────────────────────────
     누적 막대 — 일자별 배포 추이
  ───────────────────────────────────────── */
  function stackedBars(host, points, opts) {
    opts = opts || {};
    const keys = ['succeeded', 'failed', 'rolledBack', 'inProgress', 'cancelled'];
    const W = 720, H = 220, padL = 34, padR = 8, padT = 12, padB = 26;
    const plotW = W - padL - padR, plotH = H - padT - padB;

    const totals = points.map(p => keys.reduce((s, k) => s + (p[k] || 0), 0));
    const max = Math.max(1, ...totals);
    // 눈금은 1·2·5 배수로 올림 — 7 같은 축 최대값은 읽기 나쁘다.
    const niceMax = (() => {
      const mag = Math.pow(10, Math.floor(Math.log10(max)));
      for (const m of [1, 2, 5, 10]) if (max <= m * mag) return m * mag;
      return 10 * mag;
    })();

    const svg = el('svg', {
      viewBox: `0 0 ${W} ${H}`, role: 'img', class: 'chart-svg',
      'aria-labelledby': '', preserveAspectRatio: 'xMidYMid meet',
    });
    const tid = nextId('ct'), did = nextId('cd');
    svg.setAttribute('aria-labelledby', `${tid} ${did}`);

    const title = el('title', { id: tid });
    title.textContent = opts.title || '일자별 배포 추이';
    const desc = el('desc', { id: did });
    const sum = keys.reduce((o, k) => (o[k] = points.reduce((s, p) => s + (p[k] || 0), 0), o), {});
    desc.textContent =
      `${points.length}일간 총 ${totals.reduce((a, b) => a + b, 0)}건. ` +
      `성공 ${sum.succeeded}, 실패 ${sum.failed}, 롤백 ${sum.rolledBack}, 진행/대기 ${sum.inProgress}, 철회 ${sum.cancelled}.`;
    svg.appendChild(title);
    svg.appendChild(desc);

    const defs = el('defs', {});
    svg.appendChild(defs);
    const fill = {
      succeeded: COLOR.succeeded,
      failed: hatchPattern(defs, nextId('hatch'), COLOR.failed),
      rolledBack: COLOR.rolledBack,
      inProgress: COLOR.inProgress,
      cancelled: COLOR.cancelled,
    };

    // y 축 눈금 3줄
    for (let i = 0; i <= 2; i++) {
      const v = Math.round(niceMax * (i / 2));
      const y = padT + plotH - (v / niceMax) * plotH;
      svg.appendChild(el('line', {
        x1: padL, y1: y, x2: W - padR, y2: y, stroke: COLOR.axis,
        'stroke-width': 1, 'stroke-dasharray': i === 0 ? null : '2 3',
      }));
      const t = el('text', { x: padL - 6, y: y + 4, 'text-anchor': 'end', fill: COLOR.text, 'font-size': 10 });
      t.textContent = v;
      svg.appendChild(t);
    }

    const slot = plotW / Math.max(points.length, 1);
    const barW = Math.max(3, Math.min(26, slot * 0.62));

    points.forEach((p, i) => {
      const cx = padL + slot * i + slot / 2;
      let acc = 0;
      keys.forEach(k => {
        const v = p[k] || 0;
        if (!v) return;
        const h = (v / niceMax) * plotH;
        const y = padT + plotH - h - acc;
        const r = el('rect', {
          x: cx - barW / 2, y, width: barW, height: h, fill: fill[k], rx: 2,
        });
        const rt = el('title', {});
        rt.textContent = `${p.date} ${LABEL[k]} ${v}건`;
        r.appendChild(rt);
        svg.appendChild(r);
        acc += h;
      });

      // x 축 라벨은 겹치지 않을 만큼만 — 날짜가 뭉개지면 없느니만 못하다.
      const every = Math.ceil(points.length / 7);
      if (i % every === 0 || i === points.length - 1) {
        const t = el('text', {
          x: cx, y: H - 8, 'text-anchor': 'middle', fill: COLOR.text, 'font-size': 10,
        });
        t.textContent = p.date.slice(5); // MM-DD
        svg.appendChild(t);
      }
    });

    mountFigure(host, svg, srTable(
      opts.title || '일자별 배포 추이',
      ['날짜', '성공', '실패', '롤백', '진행/대기', '철회'],
      points.map(p => [p.date, p.succeeded || 0, p.failed || 0, p.rolledBack || 0, p.inProgress || 0, p.cancelled || 0])
    ));
  }

  /* ─────────────────────────────────────────
     도넛 — 결과 분포 + 가운데 성공률
  ───────────────────────────────────────── */
  function donut(host, outcome, opts) {
    opts = opts || {};
    const keys = ['succeeded', 'failed', 'rolledBack', 'inProgress', 'cancelled'];
    const vals = keys.map(k => outcome[k] || 0);
    const total = vals.reduce((a, b) => a + b, 0);

    const S = 180, cx = S / 2, cy = S / 2, R = 74, r = 50;
    const svg = el('svg', { viewBox: `0 0 ${S} ${S}`, role: 'img', class: 'chart-svg donut' });
    const tid = nextId('ct'), did = nextId('cd');
    svg.setAttribute('aria-labelledby', `${tid} ${did}`);
    const title = el('title', { id: tid });
    title.textContent = opts.title || '배포 결과 분포';
    const desc = el('desc', { id: did });
    svg.appendChild(title);
    svg.appendChild(desc);

    const defs = el('defs', {});
    svg.appendChild(defs);
    const fill = {
      succeeded: COLOR.succeeded,
      failed: hatchPattern(defs, nextId('hatch'), COLOR.failed),
      rolledBack: COLOR.rolledBack,
      inProgress: COLOR.inProgress,
      cancelled: COLOR.cancelled,
    };

    if (total === 0) {
      svg.appendChild(el('circle', { cx, cy, r: (R + r) / 2, fill: 'none', stroke: '#e2e8f0', 'stroke-width': R - r }));
      const t = el('text', { x: cx, y: cy + 4, 'text-anchor': 'middle', fill: COLOR.text, 'font-size': 12 });
      t.textContent = '데이터 없음';
      svg.appendChild(t);
      desc.textContent = '집계 기간에 배포가 없습니다.';
      mountFigure(host, svg, null);
      return;
    }

    let a0 = -Math.PI / 2;
    keys.forEach((k, i) => {
      const v = vals[i];
      if (!v) return;
      const a1 = a0 + (v / total) * Math.PI * 2;
      // 한 조각이 360도를 다 차지하면 시작=끝이 되어 호가 사라진다 — 원으로 그린다.
      if (v === total) {
        svg.appendChild(el('circle', {
          cx, cy, r: (R + r) / 2, fill: 'none', stroke: fill[k], 'stroke-width': R - r,
        }));
      } else {
        const p = el('path', { d: arc(cx, cy, R, r, a0, a1), fill: fill[k] });
        const pt = el('title', {});
        pt.textContent = `${LABEL[k]} ${v}건`;
        p.appendChild(pt);
        svg.appendChild(p);
      }
      a0 = a1;
    });

    const rate = outcome.successRate;
    const big = el('text', {
      x: cx, y: cy + 2, 'text-anchor': 'middle', fill: '#0f172a',
      'font-size': 26, 'font-weight': 800,
    });
    big.textContent = (rate == null || rate < 0) ? '–' : rate + '%';
    svg.appendChild(big);
    const cap = el('text', { x: cx, y: cy + 20, 'text-anchor': 'middle', fill: COLOR.text, 'font-size': 10 });
    cap.textContent = '성공률';
    svg.appendChild(cap);

    desc.textContent =
      (rate == null || rate < 0 ? '완료된 배포가 없어 성공률을 낼 수 없습니다. ' : `성공률 ${rate}%. `) +
      keys.map((k, i) => `${LABEL[k]} ${vals[i]}건`).join(', ') + '.';

    mountFigure(host, svg, srTable(
      opts.title || '배포 결과 분포',
      ['구분', '건수'],
      keys.map((k, i) => [LABEL[k], vals[i]])
    ));
  }

  /** 도넛 조각 path — 바깥 R, 안쪽 r 의 링 섹터. */
  function arc(cx, cy, R, r, a0, a1) {
    const large = (a1 - a0) > Math.PI ? 1 : 0;
    const p = (rad, a) => [cx + rad * Math.cos(a), cy + rad * Math.sin(a)];
    const [x0, y0] = p(R, a0), [x1, y1] = p(R, a1);
    const [x2, y2] = p(r, a1), [x3, y3] = p(r, a0);
    return `M ${x0} ${y0} A ${R} ${R} 0 ${large} 1 ${x1} ${y1} ` +
           `L ${x2} ${y2} A ${r} ${r} 0 ${large} 0 ${x3} ${y3} Z`;
  }

  /* ─────────────────────────────────────────
     가로 막대 — 환경별(TEST/PROD) 비교
  ───────────────────────────────────────── */
  function envBars(host, slices, opts) {
    opts = opts || {};
    if (!slices || !slices.length) {
      host.innerHTML = '<p class="muted" style="text-align:center;padding:24px 0;margin:0">집계 기간에 배포가 없습니다.</p>';
      return;
    }
    const keys = ['succeeded', 'failed', 'rolledBack', 'inProgress', 'cancelled'];
    const rowH = 34, W = 360, padL = 54, padR = 44;
    const H = slices.length * rowH + 10;
    const plotW = W - padL - padR;
    const max = Math.max(1, ...slices.map(s => keys.reduce((a, k) => a + (s[k] || 0), 0)));

    const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', class: 'chart-svg' });
    const tid = nextId('ct'), did = nextId('cd');
    svg.setAttribute('aria-labelledby', `${tid} ${did}`);
    const title = el('title', { id: tid });
    title.textContent = opts.title || '환경별 배포';
    const desc = el('desc', { id: did });
    svg.appendChild(title);
    svg.appendChild(desc);

    const defs = el('defs', {});
    svg.appendChild(defs);
    const fill = {
      succeeded: COLOR.succeeded,
      failed: hatchPattern(defs, nextId('hatch'), COLOR.failed),
      rolledBack: COLOR.rolledBack,
      inProgress: COLOR.inProgress,
      cancelled: COLOR.cancelled,
    };

    slices.forEach((s, i) => {
      const y = i * rowH + 8;
      const lbl = el('text', { x: 0, y: y + 14, fill: '#0f172a', 'font-size': 12, 'font-weight': 700 });
      lbl.textContent = s.environment;
      svg.appendChild(lbl);

      let x = padL;
      const rowTotal = keys.reduce((a, k) => a + (s[k] || 0), 0);
      keys.forEach(k => {
        const v = s[k] || 0;
        if (!v) return;
        const w = (v / max) * plotW;
        const rect = el('rect', { x, y, width: w, height: 18, fill: fill[k], rx: 2 });
        const rt = el('title', {});
        rt.textContent = `${s.environment} ${LABEL[k]} ${v}건`;
        rect.appendChild(rt);
        svg.appendChild(rect);
        x += w;
      });
      const tot = el('text', { x: x + 6, y: y + 14, fill: COLOR.text, 'font-size': 11 });
      tot.textContent = rowTotal + '건';
      svg.appendChild(tot);
    });

    desc.textContent = slices.map(s =>
      `${s.environment} 총 ${keys.reduce((a, k) => a + (s[k] || 0), 0)}건(실패 ${s.failed || 0})`).join(', ') + '.';

    mountFigure(host, svg, srTable(
      opts.title || '환경별 배포',
      ['환경', '성공', '실패', '롤백', '진행/대기', '철회'],
      slices.map(s => [s.environment, s.succeeded || 0, s.failed || 0, s.rolledBack || 0, s.inProgress || 0, s.cancelled || 0])
    ));
  }

  /** 범례 — 색 옆에 반드시 글자를 둔다. 색만으로는 구분되지 않는다. */
  function legend(host) {
    host.innerHTML = ['succeeded', 'failed', 'rolledBack', 'inProgress', 'cancelled'].map(k =>
      `<span class="chart-legend-item"><i class="chart-swatch ${k}" aria-hidden="true"></i>${LABEL[k]}</span>`
    ).join('');
  }

  global.DeployCharts = { stackedBars, donut, envBars, legend, COLOR, LABEL };
})(window);
