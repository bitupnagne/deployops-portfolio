const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/pages/request-detail.html'), 'utf8');
const source = html.slice(html.indexOf('  function createExecutionMonitor('), html.indexOf('  function execBindSource('));
const factory = vm.runInNewContext(source + '\ncreateExecutionMonitor');
const settle = () => new Promise(resolve => setImmediate(resolve));

function harness(awaitAcceptance = false) {
  const timers = new Map(), streams = [], statuses = [], notices = [], logs = [], done = [], reads = [];
  let id = 0, hidden = false, current = true, cleanup = 0, handler = async () => ({ status: 'TEST_DEPLOYING' });
  const io = {
    awaitAcceptance,
    current: () => current, hidden: () => hidden,
    timer(fn, delay) { timers.set(++id, { fn, delay }); return id; },
    clearTimer: key => timers.delete(key), abortController: () => new AbortController(),
    source() {},
    open() {
      const stream = { events: {}, closed: false, close() { this.closed = true; },
        addEventListener(name, callback) { this.events[name] = callback; } };
      streams.push(stream); return stream;
    },
    read(signal) { reads.push(signal); return handler(signal); },
    status: value => statuses.push(value), notice: message => notices.push(message),
    log: message => logs.push(message), clearLogs: () => { logs.length = 0; },
    done: status => done.push(status), cleanup: () => cleanup++
  };
  const monitor = factory(io);
  return { monitor, streams, statuses, notices, logs, done, reads, timers,
    set read(value) { handler = value; }, set hidden(value) { hidden = value; }, set current(value) { current = value; },
    get cleanup() { return cleanup; },
    async tick(delay) {
      const entry = [...timers].filter(([, item]) => item.delay === delay).at(-1);
      assert.ok(entry, 'expected scheduled delay ' + delay);
      timers.delete(entry[0]); entry[1].fn(); await settle();
    }
  };
}

test('lost SSE terminal event is recovered from GET after HTTP504 failure; no execution callback', async () => {
  const h = harness();
  h.read = async () => ({ status: 'TEST_FAILED' });
  h.streams[0].onerror();
  await settle();
  assert.deepEqual(h.done, ['TEST_FAILED']);
  assert.equal(h.statuses.at(-1), 'TEST_FAILED');
  assert.equal(h.streams.length, 1);
  assert.equal(h.timers.size, 0);
  assert.equal(h.cleanup, 1);
});

test('silent SSE connection still polls status and reaches terminal without refresh', async () => {
  const h = harness();
  await h.tick(2000);
  assert.equal(h.statuses.at(-1), 'TEST_DEPLOYING');
  h.read = async () => ({ status: 'TEST_DEPLOYED' });
  await h.tick(10000);
  assert.deepEqual(h.done, ['TEST_DEPLOYED']);
  assert.equal(h.streams[0].closed, true);
});

test('done without final status reconciles DB rather than declaring completion', async () => {
  const h = harness();
  h.streams[0].events.done();
  await settle();
  assert.equal(h.done.length, 0);
  h.read = async () => ({ status: 'TEST_FAILED' });
  await h.tick(2000);
  assert.deepEqual(h.done, ['TEST_FAILED']);
});

test('reconnect replaces replay once, does not duplicate existing log lines, ignores stale stream events', async () => {
  const h = harness(), first = h.streams[0];
  first.events.log({ data: 'image sent' });
  first.events.log({ data: 'health retry' });
  first.onerror(); await settle();
  await h.tick(1000);
  const second = h.streams[1];
  first.events.status({ data: 'TEST_FAILED' });
  first.events.log({ data: 'stale' });
  second.events.log({ data: 'image sent' });
  second.events.log({ data: 'health retry' });
  second.events.log({ data: 'HTTP504' });
  assert.deepEqual(h.logs, ['image sent', 'health retry', 'HTTP504']);
  assert.equal(h.done.length, 0);
  h.monitor.stop();
});

test('SSE retries are bounded but successful GET monitoring continues until final state', async () => {
  const h = harness();
  for (const delay of [1000, 2000, 4000, 8000, 16000, 30000]) {
    h.streams.at(-1).onerror(); await settle(); await h.tick(delay);
  }
  h.streams.at(-1).onerror(); await settle();
  assert.equal(h.streams.length, 7);
  h.read = async () => ({ status: 'PROD_FAILED' });
  await h.monitor.query();
  assert.deepEqual(h.done, ['PROD_FAILED']);
});

test('hidden pauses streams and polling, visible resumes GET, stop ignores late response', async () => {
  const h = harness();
  let resolve;
  h.read = signal => new Promise(res => { resolve = res; });
  h.monitor.query();
  h.hidden = true; h.monitor.visibility();
  assert.equal(h.streams[0].closed, true);
  assert.equal(h.reads[0].aborted, true);
  resolve({ status: 'TEST_FAILED' }); await settle();
  assert.equal(h.done.length, 0);
  h.read = async () => ({ status: 'TEST_DEPLOYING' });
  h.hidden = false; h.monitor.visibility(); await settle();
  assert.equal(h.streams.length, 2);
  h.current = false; h.monitor.visibility();
  assert.equal(h.cleanup, 1);
  assert.equal(h.timers.size, 0);
});

test('six failed GET attempts stop automatic activity and preserve manual reconnect guidance', async () => {
  const h = harness();
  h.read = async () => { throw new Error('offline'); };
  for (let i = 0; i < 6; i++) await h.monitor.query();
  assert.equal(h.done.length, 0);
  assert.equal(h.cleanup, 1);
  assert.equal(h.timers.size, 0);
  assert.match(h.notices.at(-1), /다시 연결/);
});

test('late GET cannot overwrite a newer terminal SSE status', async () => {
  const h = harness();
  let resolve;
  h.read = () => new Promise(res => { resolve = res; });
  h.monitor.query();
  h.streams[0].events.status({ data: 'TEST_FAILED' });
  resolve({ status: 'TEST_DEPLOYING' }); await settle();
  assert.deepEqual(h.done, ['TEST_FAILED']);
  assert.equal(h.statuses.at(-1), 'TEST_FAILED');
});

test('retry and traffic recovery ignore old failure until POST acceptance, then reconcile a fresh GET', async () => {
  const h = harness(true);
  h.read = async () => ({ status: 'TEST_FAILED' });
  await h.monitor.query();
  h.streams[0].events.status({ data: 'TEST_FAILED' });
  assert.equal(h.done.length, 0);
  assert.equal(h.streams[0].closed, false);
  h.read = async () => ({ status: 'TEST_DEPLOYING' });
  h.monitor.accept('TEST_DEPLOYING'); await settle();
  assert.equal(h.statuses.at(-1), 'TEST_DEPLOYING');
  h.read = async () => ({ status: 'TEST_FAILED' });
  await h.monitor.query();
  assert.deepEqual(h.done, ['TEST_FAILED']);
});

test('GET issued before POST acceptance cannot complete the new execution with old failure', async () => {
  const h = harness(true);
  let resolveOld;
  h.read = () => new Promise(resolve => { resolveOld = resolve; });
  h.monitor.query();
  h.read = async () => ({ status: 'PROD_DEPLOYING' });
  h.monitor.accept('PROD_DEPLOYING');
  resolveOld({ status: 'PROD_FAILED' }); await settle();
  assert.equal(h.done.length, 0);
  assert.equal(h.statuses.at(-1), 'PROD_DEPLOYING');
  h.monitor.stop();
});

test('running SSE proves acceptance when POST response is lost, then terminal SSE completes once', async () => {
  const h = harness(true);
  h.read = async () => ({ status: 'PROD_FAILED' });
  await h.monitor.query(); // old failed attempt before the retry starts
  assert.equal(h.done.length, 0);
  h.streams[0].events.status({ data: 'PROD_DEPLOYING' });
  h.streams[0].events.status({ data: 'COMPLETED' });
  h.streams[0].events.status({ data: 'COMPLETED' });
  assert.deepEqual(h.done, ['COMPLETED']);
  assert.equal(h.statuses.at(-1), 'COMPLETED');
  assert.equal(h.cleanup, 1);
  assert.equal(h.timers.size, 0);
});

test('lost POST response and terminal-only snapshots stop after bounded queries without claiming acceptance', async () => {
  const h = harness(true);
  h.read = async () => ({ status: 'TEST_FAILED' });
  await h.tick(2000);
  for (let i = 0; i < 5; i++) {
    assert.equal(h.cleanup, 0);
    h.streams[0].events.status({ data: 'TEST_FAILED' }); // old terminal SSE cannot reset the bound
    await h.tick(10000);
  }
  assert.equal(h.reads.length, 6);
  assert.equal(h.done.length, 0);
  assert.equal(h.statuses.length, 0);
  assert.equal(h.cleanup, 1);
  assert.equal(h.streams[0].closed, true);
  assert.equal(h.timers.size, 0);
  assert.match(h.notices.at(-1), /실행을 다시 누르지 말고.*다시 연결/);
  h.monitor.accept('TEST_DEPLOYING'); // a late acknowledgement cannot revive disposed timers
  assert.equal(h.timers.size, 0);
});
