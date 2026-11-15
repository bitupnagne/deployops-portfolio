const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/server-agent.js'), 'utf8');
const start = source.indexOf('  function readinessText(');
const end = source.indexOf('  const esc =', start);
const context = {};
vm.runInNewContext(source.slice(start, end) + '\nthis.readiness = readinessText;', context);

test('native WEB readiness does not imply Docker is required', () => {
  const text = context.readiness({role:'WEB',ready:true,checks:{docker:{ready:false},nginx:{ready:true},helpers:{ready:true}}});
  assert.match(text, /^배포 준비됨/);
  assert.match(text, /Docker 미준비 \(nginx 기반 WEB 배포에는 불필요\)/);
  assert.match(text, /nginx 실행·설정 정상/);
  assert.doesNotMatch(text, /Docker 확인 필요/);
});
test('WAS and unknown roles retain Docker prerequisite warning', () => {
  for (const role of ['WAS', undefined]) {
    assert.match(context.readiness({role,ready:false,checks:{docker:{ready:false}}}), /준비 확인 필요 · Docker 확인 필요/);
  }
});
test('WEB with Docker still reports its version', () => {
  assert.match(context.readiness({role:'WEB',ready:true,checks:{docker:{ready:true,version:'28.5.1'}}}), /Docker 28\.5\.1/);
});
test('WEB nginx failure is not hidden by optional Docker wording', () => {
  const text = context.readiness({role:'WEB',ready:false,checks:{docker:{ready:false},nginx:{ready:false},helpers:{ready:false}}});
  assert.match(text, /^준비 확인 필요/);
  assert.match(text, /nginx 실행 또는 설정 확인 필요/);
  assert.match(text, /제어 기능 설치 확인 필요/);
});
test('missing readiness still requests a connection check', () => {
  assert.equal(context.readiness(null), '연결·준비 검사를 실행해 주세요.');
});
