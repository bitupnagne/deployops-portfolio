/* =====================================================
   DeployOps — md.js
   AI 답변을 읽을 수 있는 모양으로 그린다

   모델은 마크다운으로 답한다. **굵게**, ### 제목, - 목록, ```블록```.
   그런데 화면은 그동안 textContent + white-space:pre-wrap 로 넣고 있어서
   그 기호가 전부 글자 그대로 보였다 — 굵어야 할 곳에 별표 두 개가 찍히고,
   명령어 블록은 백틱 세 개에 둘러싸인 평문이 됐다.

   외부 라이브러리를 쓰지 않는다. 폐쇄망이라 CDN 을 못 받고, CSP 가 외부
   스크립트를 막는다. 필요한 문법만 직접 처리한다.

   ★ 안전 규칙 — 먼저 이스케이프하고, 그 다음에 서식을 입힌다.
     순서가 바뀌면 모델이 뱉은 <script> 가 그대로 실행된다. 로그 본문이
     프롬프트에 들어가므로 남이 심은 문자열이 모델 답변에 섞여 나올 수 있다.
===================================================== */

/** mdRender 가 빼 둔 코드블록. mdInline 이 남은 자리표시자를 되돌릴 때 본다. */
var _mdBlocks = [];

/** 코드블록 자리표시자. 모델 답변에 나올 리 없는 글자를 쓴다. */
var MD_PH_OPEN = '␄BLOCK';
var MD_PH_CLOSE = '␄';

/** 태그로 읽힐 수 있는 문자를 먼저 없앤다. 이 함수를 거치지 않은 글자는 절대 붙이지 않는다. */
function mdEscape(t) {
  return String(t == null ? '' : t)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * 마크다운 문자열을 HTML 로 바꾼다.
 *
 * 지원: 제목(#~####) · 굵게 · 기울임 · 인라인 코드 · 코드블록 · 순서/비순서 목록 ·
 *       수평선 · 인용. 마크다운 링크는 글자만 남긴다.
 */
function mdRender(src) {
  if (src == null || String(src).trim() === '') return '';

  // ── 1. 코드블록을 먼저 빼 둔다 ──
  // 안쪽은 서식 처리를 하면 안 된다(명령어에 *, _, # 이 흔하다).
  // 앞뒤에 줄바꿈을 붙여 자리표시자가 한 줄을 독차지하게 한다 —
  // 문단 중간에 끼면 아래 줄 단위 처리에서 못 알아본다.
  var blocks = [];
  _mdBlocks = blocks;
  var text = String(src).replace(/```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)```/g, function (m, lang, code) {
    blocks.push({ lang: lang || '', code: code.replace(/\n$/, '') });
    return '\n' + MD_PH_OPEN + (blocks.length - 1) + MD_PH_CLOSE + '\n';
  });

  text = mdEscape(text);

  // ── 2. 줄 단위 구조 ──
  var lines = text.split('\n');
  var out = [];
  var para = [];

  /*
    열려 있는 목록 스택. {tag:'ul'|'ol', indent:들여쓴 칸 수}

    스택 없이 "직전 목록 종류" 하나만 들고 있으면, 번호 목록 안에 글머리표가
    끼는 순간 부모를 닫아 버린다. 그러면 다음 번호 항목이 새 <ol> 로 시작해
    화면에 <b>1, 1, 1</b> 로 찍힌다 — 모델은 1, 2, 3 으로 썼는데도.
    실제로 배포 로그 분석 결과가 그렇게 나왔다.
  */
  var stack = [];

  function flushPara() {
    if (para.length) { out.push('<p>' + para.join('<br>') + '</p>'); para = []; }
  }
  /** 한 단계 닫기 — 열려 있던 <li> 부터 닫는다. */
  function popList() {
    var lv = stack.pop();
    if (lv.liOpen) { out.push('</li>'); }
    out.push('</' + lv.tag + '>');
  }
  function closeList() {
    while (stack.length) { popList(); }
  }

  /**
   * 이 항목의 들여쓰기·종류에 맞게 목록을 연다.
   *
   * <p>중첩 목록은 부모 {@code <li>} 안에 들어가야 한다. {@code <ol>} 바로 밑에
   * {@code <ul>} 을 두면 브라우저는 그려 주지만 보조기술이 항목 관계를 잘못 읽는다.
   * 그래서 {@code <li>} 를 바로 닫지 않고 다음 항목이 올 때까지 열어 둔다.
   */
  function openList(tag, indent) {
    // 더 얕게 들어온 항목은 그보다 깊은 목록을 모두 닫는다.
    while (stack.length && stack[stack.length - 1].indent > indent) {
      popList();
    }
    var top = stack[stack.length - 1];
    if (top && top.indent === indent && top.tag !== tag) {
      popList();                       // 같은 깊이인데 종류가 바뀌면 갈아 끼운다
      top = stack[stack.length - 1];
    }
    if (!top || top.indent < indent) {
      out.push('<' + tag + ' class="md-list">');
      stack.push({ tag: tag, indent: indent, liOpen: false });
    }
  }

  /** 같은 깊이의 앞 항목을 닫고 새 <li> 를 연다. */
  function pushItem(html) {
    var lv = stack[stack.length - 1];
    if (lv.liOpen) { out.push('</li>'); }
    out.push('<li>' + html);
    lv.liOpen = true;
  }

  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].replace(/\s+$/, '');

    if (!line.trim()) { flushPara(); closeList(); continue; }

    // 코드블록 자리표시자는 그 자체로 한 덩어리다
    var ph = line.match(/^␄BLOCK(\d+)␄$/);
    if (ph) {
      flushPara(); closeList();
      var b = blocks[Number(ph[1])];
      out.push('<pre class="md-code"'
        + (b.lang ? ' data-lang="' + mdEscape(b.lang) + '"' : '')
        + '><code>' + mdEscape(b.code) + '</code></pre>');
      continue;
    }

    // 수평선
    if (/^(-{3,}|\*{3,}|={3,}|─{3,})$/.test(line.trim())) {
      flushPara(); closeList();
      out.push('<hr class="md-hr">');
      continue;
    }

    // 제목 — # 을 h3 부터 매긴다. 카드 안에 들어가는 글이라 h1 은 과하다.
    var h = line.match(/^(#{1,4})\s+(.*)$/);
    if (h) {
      flushPara(); closeList();
      var level = Math.min(6, h[1].length + 2);
      out.push('<h' + level + ' class="md-h">' + mdInline(h[2]) + '</h' + level + '>');
      continue;
    }

    // 인용 (이미 이스케이프되어 > 가 &gt; 다)
    var q = line.match(/^&gt;\s?(.*)$/);
    if (q) {
      flushPara(); closeList();
      out.push('<blockquote class="md-quote">' + mdInline(q[1]) + '</blockquote>');
      continue;
    }

    // 목록 — 모델이 "1) " 도 자주 쓴다
    var ol = line.match(/^(\s*)(\d+)[.)]\s+(.*)$/);
    var ul = line.match(/^(\s*)[-*•]\s+(.*)$/);
    if (ol || ul) {
      flushPara();
      var indent = (ol ? ol[1] : ul[1]).replace(/\t/g, '  ').length;
      openList(ol ? 'ol' : 'ul', indent);
      pushItem(mdInline(ol ? ol[3] : ul[2]));
      continue;
    }

    closeList();
    para.push(mdInline(line));
  }
  flushPara(); closeList();
  return out.join('');
}

/** 한 줄 안의 서식. 입력은 이미 이스케이프된 문자열이어야 한다. */
function mdInline(s) {
  return s
    // 인라인 코드가 먼저다 — 코드 안의 별표를 굵게 처리하면 안 된다
    .replace(/`([^`]+)`/g, '<code class="md-inline">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    // 기울임 — 파일명의 밑줄(deploy_was_app)을 건드리지 않도록 * 만 본다
    .replace(/(^|[\s(])\*([^*\n]+)\*(?=[\s).,]|$)/g, '$1<em>$2</em>')
    // [글자](주소) 는 글자만 남긴다. 폐쇄망에서 모델이 지어낸 주소는 대개 존재하지 않는데
    // 눌러 볼 수 있게 두면 그 주소가 진짜라고 믿게 된다.
    // 주소 안의 괄호 한 겹까지 받아 준다 — javascript:alert(3) 같은 것이 닫는 괄호를
    // 남기면 화면에 "링크)" 로 찍힌다.
    .replace(/\[([^\]]+)\]\((?:[^()]|\([^()]*\))*\)/g, '$1')
    // 줄을 독차지하지 못한 코드블록은 인라인 코드로 되살린다 — 자리표시자가 그대로 보이면 안 된다.
    .replace(/␄BLOCK(\d+)␄/g, function (m, i) {
      var b = _mdBlocks[Number(i)];
      return b ? '<code class="md-inline">' + mdEscape(b.code) + '</code>' : '';
    });
}

/**
 * AI 답변을 요소에 그린다. 모든 AI 출력 지점이 이것만 쓴다.
 *
 * @param el   대상 요소
 * @param text 모델이 준 원문(마크다운)
 * @param opts {truncated:boolean} 답변이 중간에 잘렸으면 그 사실을 함께 알린다
 */
function mdInto(el, text, opts) {
  if (!el) return;
  el.classList.add('md');
  el.innerHTML = mdRender(text);
  if (opts && opts.truncated) {
    // 잘린 답을 그냥 두면 "AI 가 이상하게 답한다" 로 읽힌다 — 왜 끊겼는지 말해 준다.
    var note = document.createElement('p');
    note.className = 'md-cut';
    note.textContent = '답변이 길이 제한에 걸려 여기서 끊겼습니다. '
      + 'AI 설정에서 최대 토큰을 늘리면 끝까지 받을 수 있습니다.';
    el.appendChild(note);
  }
}
