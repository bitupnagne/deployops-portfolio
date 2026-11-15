/* =====================================================
   DeployOps — topology.js
   배포 파이프라인 구조도. 의존성 0.

   가로 한 줄로 읽는다: 배포망 → TEST → PROD.
   흐름은 왼쪽에서 오른쪽으로 가는 것이라, 세로로 쌓으면 "흐름" 이 아니라 "목록" 이 된다.
   좁은 화면에서는 가로 스크롤로 넘긴다 — 줄바꿈하면 순서가 깨진다.

   파이프 색이 곧 판정이다
     초록 정상 · 노랑 붙긴 하는데 최근 실패 있음 · 빨강 끊김 · 회색 미설정/확인 중
   색만으로 말하지 않는다. 선 모양(실선/점선)과 상태 글자를 함께 낸다.
===================================================== */

(function (global) {
  'use strict';

  const esc = s => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  const LINK = {
    OK:           { cls: 'ok',      mark: '',  text: '정상' },
    WARN:         { cls: 'warn',    mark: '!', text: '간헐 오류' },
    BROKEN:       { cls: 'broken',  mark: '✕', text: '연결 끊김' },
    UNCONFIGURED: { cls: 'unset',   mark: '?', text: '미설정' },
    UNKNOWN:      { cls: 'unknown', mark: '',  text: '확인 중' },
  };
  const linkOf = s => LINK[s] || LINK.UNKNOWN;
  const transportOf = s => s.controlMode === 'AGENT' ? '에이전트' : '에이전트 전환 필요';

  const ICON = {
    gitlab: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M12 21 3 14l1.5-6L7 14h10l2.5-6L21 14z"/></svg>',
    nexus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></svg>',
    deployops: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="4" width="18" height="6" rx="1.5"/><rect x="3" y="14" width="18" height="6" rx="1.5"/><path d="M7 7h.01M7 17h.01"/></svg>',
    WAS: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/></svg>',
    WEB: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 3 2.5 15 0 18M12 3c-2.5 3-2.5 15 0 18"/></svg>',
  };
  const iconFor = k => ICON[k] || ICON.WAS;

  const pill = (t, c) => '<span class="tp-pill ' + (c || '') + '">' + esc(t) + '</span>';

  /**
   * 가로 파이프 한 칸. 색 + 선모양 + 글자 삼중 표기.
   *
   * @param onClickIds 값이 있으면 표식(! · ✕)이 버튼이 되어 그 서버들의 원인을 연다.
   *   판정이 걸린 바로 그 지점에서 원인으로 이어지는 것이 자연스럽다 —
   *   서버 카드마다 버튼을 두면 판정 주체가 둘로 갈린다.
   */
  function pipe(label, state, detail, onClickIds, projectId) {
    const l = state === 'WARN' && (label === 'CI 빌드 → push' || label === '이미지 전달')
      ? { ...LINK.WARN, text: '권한 확인 필요' }
      : linkOf(state);
    const ids = (onClickIds || []).filter(Boolean);
    const clickable = /^\d+$/.test(String(projectId)) && ids.length && (state === 'WARN' || state === 'BROKEN');
    const mark = !l.mark ? ''
      : clickable
        ? '<button type="button" class="tp-pipe-mark tp-pipe-mark-btn"'
          + ' onclick="smShowWarn(' + projectId + ',' + JSON.stringify(ids).replace(/"/g, '&quot;') + ')"'
          + ' title="원인 보기" aria-label="' + esc(l.text) + ' 원인 보기">' + l.mark + '</button>'
        : '<span class="tp-pipe-mark">' + l.mark + '</span>';
    return '<div class="tp-pipe ' + l.cls + '"' + (detail ? ' title="' + esc(detail) + '"' : '') + '>'
      + '<span class="tp-pipe-label">' + esc(label) + '</span>'
      + '<span class="tp-pipe-line">' + mark + '</span>'
      + '<span class="tp-pipe-state">' + esc(l.text) + '</span>'
      + '</div>';
  }

  /** 프로젝트 기술 스택 칩 — 무엇으로 빌드해 무엇으로 나가는지. */
  function stackChips(d) {
    const BUILD = { MAVEN: 'Java · Maven', GRADLE: 'Java · Gradle', NPM: 'Node · npm',
                    STATIC: '정적 파일', NONE: '빌드 없음' };
    const PIPE = { DOCKER: 'Docker 이미지', STATIC: '정적 번들' };
    const chips = [];
    if (d.buildTool) chips.push(pill(BUILD[d.buildTool] || d.buildTool, 'stack'));
    if (d.pipelineType) chips.push(pill(PIPE[d.pipelineType] || d.pipelineType, 'stack'));
    (d.components || []).forEach(c => {
      const t = { CONTAINER: '컨테이너', STATIC_BUNDLE: '정적 번들', PROXY_RELOAD: '프록시' }[c.artifactType];
      if (t) chips.push(pill(c.code + ' · ' + t, 'stack'));
    });
    return chips.length ? '<div class="tp-stack">' + chips.join('') + '</div>' : '';
  }

  function buildNode(n, health) {
    const h = (health && health[n.id]) || {};
    const state = n.self ? 'OK'
      : (n.configured === false ? 'UNCONFIGURED' : (h.state || 'UNKNOWN'));
    const l = linkOf(state);
    const ports = [];
    if (n.port) ports.push(pill(n.port + ' 포트'));
    if (n.id === 'nexus' && n.registryPort) ports.push(pill('Docker Registry ' + n.registryPort, 'accent'));

    const label = n.id === 'nexus' ? 'Nexus 관리' : n.label;
    const role = n.self ? '현재 인스턴스 · 승인 · 실행'
      : n.id === 'nexus' ? '관리 포트 TCP (레지스트리 별도 확인)' : n.role;
    const probe = n.self ? '현재 DeployOps 응답 중'
      : n.id === 'nexus' ? '관리 포트 TCP: ' + l.text : '';

    return '<div class="tp-node ' + l.cls + '">'
      + '<span class="tp-node-icon" aria-hidden="true">' + iconFor(n.id) + '</span>'
      + '<div class="tp-node-body">'
      +   '<b>' + esc(label) + '</b>'
      +   '<span class="tp-node-role">' + esc(role) + '</span>'
      +   (probe ? '<span class="tp-node-role">' + esc(probe) + '</span>' : '')
      +   (n.host ? '<span class="tp-node-host mono">' + esc(n.host) + '</span>' : '')
      +   (ports.length ? '<div class="tp-pills">' + ports.join('') + '</div>' : '')
      + '</div>'
      + (state === 'OK' ? '' : '<span class="tp-node-flag ' + l.cls + '">' + esc(l.text) + '</span>')
      + '</div>';
  }

  /**
   * 서버 한 대 — 상태 두 축 + 제어 버튼까지 이 안에 있다.
   *
   * 구조도와 제어를 따로 두면 "어느 것이 문제인지" 를 구조도에서 보고
   * "그걸 어떻게 할지" 는 다른 곳에서 찾아야 한다. 눈이 오가는 만큼 실수가 는다.
   */
  function serverNode(s, health, svc, opts) {
    const h = (health || []).find(x => x.serverId === s.serverId) || {};
    const linkState = h.state || (s.reachable === 'NORMAL' ? 'OK'
      : s.reachable === 'ERROR' ? 'BROKEN' : 'UNKNOWN');
    const l = linkOf(linkState);

    const v = (svc || []).find(x => x.serverId === s.serverId);
    // running / down / missing / unknown
    const run = !v || v.statusKnown === false ? 'unknown' : (v.running ? 'running' : (v.exists ? 'down' : 'missing'));
    const runText = v && v.statusLabel || { running: '실행 중', down: '정지됨', missing: '미배포', unknown: '확인 중' }[run];

    const projectId = opts && opts.projectId;
    const scoped = /^\d+$/.test(String(projectId));
    const targetKey = scoped ? projectId + '-' + s.serverId : String(s.serverId);
    const busy = !!(opts && opts.busy);

    // 기동/정지는 현재 상태의 반대만 열어 둔다 — 눌러도 아무 일 없을 버튼을 열어두지 않는다.
    const supported = scoped && !!v && v.controlSupported !== false;
    const canStart = supported && run === 'down';
    const canStop = supported && run === 'running';
    const canRestart = supported && (run === 'running' || run === 'down');
    const lock = busy ? ' title="배포가 진행 중이라 잠겨 있습니다."' : '';

    const unit = s.unit > 0 ? (s.unit + '호기') : '호기 미지정';
    return '<div class="tp-srv ' + l.cls + ' run-' + run + '"'
      + ' id="tp-srv-' + targetKey + '">'
      + '<div class="tp-srv-head">'
      +   '<span class="tp-srv-icon" aria-hidden="true">' + iconFor(s.serverType) + '</span>'
      +   pill(unit, 'unit')
      +   '<b>' + esc(s.serverName) + '</b>'
      +   (s.serverType ? '<span class="tp-srv-type">' + esc(s.serverType) + '</span>' : '')
      + '</div>'
      + '<p class="tp-node-host mono">' + esc(s.ipAddress) + '</p>'
      + '<div class="tp-pills">'
      +   pill(transportOf(s))
      +   (s.servicePort ? pill('서비스 ' + s.servicePort, 'accent') : '')
      // 등록일 — 컬럼이 생기기 전(2026-07-31)에 등록된 서버는 비어 있다.
      //   그런 행을 마이그레이션 시각으로 채우면 전부 같은 날짜가 되어 오히려 틀린 정보가 된다.
      +   pill(s.createdAt ? ('등록 ' + String(s.createdAt).slice(0, 10)) : '등록일 미상', 'date')
      + '</div>'
      + '<div class="tp-srv-axes">'
      // 상태만 적는다. "무슨 오류인지" 는 파이프의 ! 표식이 맡는다 —
      //   노드마다 버튼을 두면 카드가 지저분해지고, 판정 주체가 둘로 갈린다.
      +   '<span class="tp-axis"><i class="tp-dot ' + l.cls + '" aria-hidden="true"></i>서버 <b>' + esc(l.text) + '</b></span>'
      +   '<span class="tp-axis"><i class="tp-dot run-' + run + '" aria-hidden="true"></i>서비스 <b>' + esc(runText) + '</b></span>'
      + '</div>'
      + (v && v.controlSupported === false ? '<p class="form-hint">' + esc(v.controlReason || '이 배포 유형은 기동·정지 대상이 아닙니다.') + '</p>' : '')
      + '<div class="tp-srv-actions" data-actions="' + targetKey + '"' + lock + '>'
      +   btn('start', '기동', !canStart || busy, s.serverId, projectId)
      +   btn('stop', '정지', !canStop || busy, s.serverId, projectId)
      +   btn('restart', '재기동', !canRestart || busy, s.serverId, projectId)
      +   '<button class="btn btn-ghost sm" type="button"' + (scoped ? '' : ' disabled') + ' onclick="smShowLog(' + (scoped ? projectId : 'null') + ',' + s.serverId + ')">로그</button>'
      + '</div>'
      + '</div>';
  }

  function btn(action, label, disabled, serverId, projectId) {
    return '<button class="btn btn-ghost sm' + (action === 'stop' ? ' danger' : '') + '" type="button"'
      + (disabled ? ' disabled' : '')
      + ' onclick="smControl(' + (/^\d+$/.test(String(projectId)) ? projectId : 'null') + ',' + serverId + ',\'' + action + '\')">' + esc(label) + '</button>';
  }

  /** 그림을 못 보는 사용자를 위한 같은 내용의 목록. */
  function srSummary(d, health, svc) {
    const items = [];
    (d.build || []).forEach(n => {
      const h = (health && health[n.id]) || {};
      const st = n.self ? 'OK' : (n.configured === false ? 'UNCONFIGURED' : h.state);
      if (n.id === 'nexus') {
        items.push(`Nexus 관리 포트 TCP ${n.host ? n.host + (n.port ? ':' + n.port : '') : ''} — ${linkOf(st).text}`.trim());
        const registry = (health && health.registry) || {};
        const registryDetail = registry.detail ? ` — ${registry.detail}` : '';
        items.push(`Docker Registry — ${linkOf(registry.state).text}${registryDetail}`);
      } else if (n.self) {
        items.push('배포시스템(현재 DeployOps 인스턴스) — 현재 DeployOps 응답 중');
      } else {
        items.push(`${n.label}(${n.role}) ${n.host || '주소 미설정'} — ${linkOf(st).text}`);
      }
    });
    (d.environments || []).forEach(z => (z.groups || []).forEach(g => (g.servers || []).forEach(s => {
      const h = ((health && health.servers) || []).find(x => x.serverId === s.serverId) || {};
      const v = (svc || []).find(x => x.serverId === s.serverId);
      items.push(`${z.environment} / ${g.groupName} / ${s.unit > 0 ? s.unit + '호기' : '호기 미지정'} `
        + `${s.serverName} ${s.ipAddress} ${transportOf(s)}`
        + (s.servicePort ? ` 서비스 ${s.servicePort}` : '')
        + ` — 서버 ${linkOf(h.state || (s.reachable === 'NORMAL' ? 'OK' : 'UNKNOWN')).text}`
        + `, 서비스 ${v && v.statusLabel || (!v ? '확인 중' : (v.running ? '실행 중' : (v.exists ? '정지됨' : '미배포')))}`);
    })));
    return '<ul class="sr-only">' + items.map(t => '<li>' + esc(t) + '</li>').join('') + '</ul>';
  }

  /**
   * @param host   붙일 요소
   * @param d      structure() 응답
   * @param health health() 응답 (없으면 "확인 중")
   * @param svc    services() 응답 배열 (없으면 "확인 중")
   * @param opts   { busy: 배포 진행 중이면 true }
   */
  function render(host, d, health, svc, opts) {
    opts = opts || {};
    const byId = {};
    (d.build || []).forEach(n => { byId[n.id] = n; });

    // 환경 충돌(같은 컨테이너를 가리키는 서버들)에 걸린 서버 id
    // 환경 충돌은 배너 한 곳에서만 말한다. 노드마다 같은 문구를 반복하면 화면만 시끄럽고,
    //   실제로 사고를 막아 주는 것은 정지 확인 대화상자다.
    const nodeOpts = { busy: opts.busy, projectId: opts.projectId };

    const regState = (health && health.registry && health.registry.state) || 'UNKNOWN';

    // ── 한 줄로 가로 배치 ──
    let html = '<div class="tp-scroll"><div class="tp-track" role="img" aria-label="배포 파이프라인 구조도">';

    html += '<section class="tp-zone build">'
      + '<h4 class="tp-zone-head">배포망 <span>빌드 · 산출물 · 실행 주체</span></h4>'
      + stackChips(d)
      + '<div class="tp-zone-body">'
      +   buildNode(byId.gitlab || {}, health)
      +   pipe('CI 빌드 → push', regState, (health && health.registry && health.registry.detail) || '')
      +   buildNode(byId.nexus || {}, health)
      +   pipe('이미지 전달', regState, (health && health.registry && health.registry.detail) || '')
      +   buildNode(byId.deployops || {}, health)
      + '</div>'
      + '</section>';

    (d.environments || []).forEach(zone => {
      const envCls = zone.environment === 'PROD' ? 'prod' : 'test';
      // 환경으로 넘어가는 파이프 — 그 환경 서버들의 최악 상태를 색으로 삼는다.
      const states = [];
      (zone.groups || []).forEach(g => (g.servers || []).forEach(s => {
        const h = ((health && health.servers) || []).find(x => x.serverId === s.serverId);
        states.push(h ? h.state : (s.reachable === 'NORMAL' ? 'OK' : 'UNKNOWN'));
      }));
      const worst = states.indexOf('BROKEN') >= 0 ? 'BROKEN'
        : states.indexOf('WARN') >= 0 ? 'WARN'
        : states.indexOf('OK') >= 0 ? 'OK' : 'UNKNOWN';

      // 표식을 누르면 이 구간에서 문제인 서버들의 원인을 연다.
      const troubled = [];
      (zone.groups || []).forEach(g => (g.servers || []).forEach(sv => {
        const h = ((health && health.servers) || []).find(x => x.serverId === sv.serverId);
        if (h && (h.state === 'WARN' || h.state === 'BROKEN')) troubled.push(sv.serverId);
      }));
      const servers = (zone.groups || []).flatMap(g => g.servers || []);
      const modes = new Set(servers.map(s => s.controlMode === 'AGENT' ? 'AGENT' : 'SSH'));
      const transport = modes.size > 1 ? '에이전트 전환 필요 서버 포함' : modes.has('AGENT') ? '에이전트 배포' : '에이전트 전환 필요';
      html += pipe(transport, worst, '', troubled, opts.projectId);
      html += '<section class="tp-zone ' + envCls + '">'
        + '<h4 class="tp-zone-head">' + esc(zone.environment) + ' <span>배포 대상</span></h4>'
        + '<div class="tp-zone-body col">';

      (zone.groups || []).forEach(g => {
        const broken = (g.servers || []).filter(s => {
          const h = ((health && health.servers) || []).find(x => x.serverId === s.serverId);
          return h ? h.state === 'BROKEN' : s.reachable === 'ERROR';
        });
        html += '<div class="tp-group' + (broken.length ? ' has-broken' : '') + '">'
          + '<div class="tp-group-head">'
          +   '<b>' + esc(g.groupName) + '</b>'
          +   pill('간격 ' + (g.rollingIntervalSec || 0) + '초')
          +   pill((g.servers || []).length + '대')
          +   (broken.length ? '<span class="tp-node-flag broken">' + broken.length + '대 끊김</span>' : '')
          + '</div>'
          + '<div class="tp-srv-row">'
          +   ((g.servers || []).map(s => serverNode(s, health && health.servers, svc, nodeOpts))
                .join('<span class="tp-srv-sep" aria-hidden="true"></span>')
              || '<p class="muted" style="margin:0;padding:10px">이 그룹에 서버가 없습니다.</p>')
          + '</div>'
          + '</div>';
      });
      html += '</div></section>';
    });

    html += '</div></div>';
    html += srSummary(d, health, svc);

    if (d.components && d.components.length) {
      html += '<div class="tp-comp">'
        + '<p class="tp-comp-title">배포 컴포넌트</p>'
        + '<div style="overflow-x:auto"><table class="tp-comp-table"><thead><tr>'
        + '<th scope="col">코드</th><th scope="col">역할</th><th scope="col">산출물</th>'
        + '<th scope="col">이미지</th><th scope="col">포트</th><th scope="col">헬스체크</th>'
        + '</tr></thead><tbody>'
        + d.components.map(c => '<tr>'
            + '<td><b>' + esc(c.code) + '</b></td><td>' + esc(c.role || '-') + '</td>'
            + '<td>' + esc(c.artifactType || '-') + '</td>'
            + '<td class="mono">' + esc(c.imageRepository || '-') + '</td>'
            + '<td>' + esc(c.healthCheckPort || '-') + '</td>'
            + '<td class="mono">' + esc(c.healthCheckPath || '-') + '</td>'
            + '</tr>').join('')
        + '</tbody></table></div></div>';
    }

    host.innerHTML = html;
  }

  global.DeployTopology = { render, LINK };
})(window);
