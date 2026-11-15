const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/project-detail.html'), 'utf8');
const script = html.slice(html.indexOf('  function stripCiTerminalFormatting('), html.indexOf('  function operateCiPipeline('));

async function load(response, networkError = false) {
  const trace = { style: {}, textContent: '' };
  const context = vm.createContext({
    document: { getElementById: id => id === 'project-data' ? { dataset: { id: '6' } } : trace },
    fetch: async () => {
      if (networkError) throw new Error('offline');
      return { json: async () => response };
    }
  });
  vm.runInContext(script, context);
  context.loadCiTrace(15, 20);
  await new Promise(resolve => setImmediate(resolve));
  return trace;
}

test('successful empty trace explains absence without claiming the job succeeded', async () => {
  for (const data of [{ trace: '' }, {}, { trace: '', truncated: true }]) {
    const trace = await load({ success: true, data });
    assert.equal(trace.style.display, '');
    assert.match(trace.textContent, /표시할 로그가 없습니다/);
    assert.match(trace.textContent, /작업 상태를 확인해 주세요/);
    assert.doesNotMatch(trace.textContent, /앞부분 생략/);
  }
});

test('nonempty traces retain exact content and truncation marker', async () => {
  const log = 'build failed <error>\n';
  assert.equal((await load({ success: true, data: { trace: log } })).textContent, log);
  assert.equal((await load({ success: true, data: { trace: log, truncated: true } })).textContent,
    '[앞부분 생략]\n' + log);
});

test('API and network errors retain their distinct guidance', async () => {
  assert.equal((await load({ success: false, message: '접근 권한이 없습니다.' })).textContent,
    '접근 권한이 없습니다.');
  assert.equal((await load(null, true)).textContent, '로그 조회 네트워크 오류');
});


test('ANSI CSI coloring and erase controls disappear while plain build text remains', async () => {
  const raw = '\x1b[0K\x1b[32mBUILD SUCCESS\x1b[0;m\n\x9b31mwarning\x9b0m';
  assert.equal((await load({ success: true, data: { trace: raw } })).textContent, 'BUILD SUCCESS\nwarning');
});

test('OSC title and hyperlink payloads are removed for BEL and ST terminators', async () => {
  const raw = '\x1b]0;private title\x07before \x1b]8;;https://example.invalid\x1b\\label\x1b]8;;\x1b\\ after\x9d0;title\x9c';
  assert.equal((await load({ success: true, data: { trace: raw } })).textContent, 'before label after');
});

test('GitLab section transport metadata is removed while the heading and output survive', async () => {
  const raw = '\x1b[0Ksection_start:123:build[collapsed=true]\r\x1b[0KCompile\noutput\n\x1b[0Ksection_end:125:build\r\x1b[0K\n';
  assert.equal((await load({ success: true, data: { trace: raw, truncated: true } })).textContent, '[앞부분 생략]\nCompile\noutput\n\n');
});

test('sanitized trace remains inert text rather than HTML', async () => {
  const value = '\x1b[31m<img src=x onerror=alert(1)>\x1b[0m';
  const trace = await load({ success: true, data: { trace: value } });
  assert.equal(trace.textContent, '<img src=x onerror=alert(1)>');
  assert.equal(trace.innerHTML, undefined);
});

test('unterminated OSC is suppressed without changing preceding plain text', async () => {
  assert.equal((await load({ success: true, data: { trace: 'safe\x1b]0;unfinished' } })).textContent, 'safe');
});


test('timestamp-prefixed GitLab section rows disappear without removing timestamped business logs', async () => {
  const ordinary = '2026-10-09T01:11:27.123456Z 00O BUILD SUCCESS\n';
  const literal = '2026-10-09T01:11:27.123456Z 00O user message: section_start:1791508287:prepare_executor\n';
  const notMetadata = '2026-10-09T01:11:27.123456Z 00O section_start:1791508287:prepare_executor literal business suffix\n';
  const raw = '2026-10-09T01:11:27.123456Z 00O \x1b[0Ksection_start:1791508287:prepare_executor[collapsed=true]\r\x1b[0KPreparing executor\n'
    + ordinary + literal + notMetadata
    + '2026-10-09T01:11:28Z 00O \x1b[0Ksection_end:1791508288:prepare_executor\r\x1b[0K\n';
  assert.equal((await load({ success: true, data: { trace: raw } })).textContent,
    'Preparing executor\n' + ordinary + literal + notMetadata + '\n');
});


test('captured GitLab plus and space stream prefixes strip only exact section metadata', async () => {
  for (const timestamp of ['', '2026-10-09T01:11:27.123456Z ']) {
    for (const channel of ['00O', '00E']) {
      for (const separator of [' ', '+']) {
        const prefix = timestamp + channel + separator;
        const raw = prefix + '\x1b[0Ksection_start:1791508287:prepare_executor\r\x1b[0KPreparing executor\n'
          + prefix + 'normal build output\n'
          + prefix + 'literal section_start:1791508287:prepare_executor\n'
          + prefix + 'section_start:1791508287:prepare_executor user text\n'
          + prefix + '\x1b[0Ksection_end:1791508288:prepare_executor\r\x1b[0K\n';
        assert.equal((await load({ success: true, data: { trace: raw } })).textContent,
          'Preparing executor\n' + prefix + 'normal build output\n'
          + prefix + 'literal section_start:1791508287:prepare_executor\n'
          + prefix + 'section_start:1791508287:prepare_executor user text\n\n');
      }
    }
  }
});
