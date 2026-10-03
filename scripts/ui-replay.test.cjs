const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const stateApi = require('../src/main/resources/static/run-state.js');

function browserFixture(currentRun, pendingEvents = []) {
  const elements = new Map();
  const node = () => ({ textContent: '', className: '', disabled: false, hidden: false, childElementCount: 0,
    append() { this.childElementCount++; }, replaceChildren() { this.childElementCount = 0; }, addEventListener() {} });
  const sources = [];
  const sandbox = {
    window: { FlowTrailState: stateApi, addEventListener() {} },
    document: { getElementById(id) { if (!elements.has(id)) elements.set(id, node()); return elements.get(id); }, createElement: node, querySelectorAll() { return []; } },
    EventSource: class { constructor(url) { this.url = url; this.closed = false; sources.push(this); } close() { this.closed = true; } addEventListener(name, callback) { this.receive = callback; } },
    fetch: async path => ({ ok: true, status: 200, json: async () => path.includes('/events/history') ? pendingEvents : path.includes('/workflows/') ? [] : currentRun }),
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {}, console
  };
  vm.createContext(sandbox);
  const code = fs.readFileSync(require.resolve('../src/main/resources/static/app.js'), 'utf8').replace(/loadTemplate\(\); action\(refresh\);\s*$/, '');
  vm.runInContext(code, sandbox);
  return { sandbox, sources, async watch() { await vm.runInContext("watchRun('run-a')", sandbox); return sources.at(-1); } };
}

test('resumed run replays past an old terminal event into the new attempt', async () => {
  const fixture = browserFixture({ id: 'run-a', workflowId: 'workflow-a', status: 'RUNNING', nodes: [] });
  const source = await fixture.watch();
  source.receive({ data: JSON.stringify({ runId: 'run-a', seq: 1, type: 'RUN_MANUAL_REVIEW', payload: {} }) });
  assert.equal(source.closed, false, 'Historical terminal event must not stop replay');
  source.receive({ data: JSON.stringify({ runId: 'run-a', seq: 2, type: 'RUN_QUEUED', payload: {} }) });
  source.receive({ data: JSON.stringify({ runId: 'run-a', seq: 3, nodeId: 'summary', attemptId: 2, type: 'LLM_DELTA', payload: { text: 'new output' } }) });
  assert.equal(vm.runInContext('active.state.cursor', fixture.sandbox), 3);
  assert.equal(vm.runInContext("active.state.attempts.get('summary:2').text", fixture.sandbox), 'new output');
});

test('EOF closes only after a terminal snapshot and no remaining committed events', async () => {
  const fixture = browserFixture({ id: 'run-a', workflowId: 'workflow-a', status: 'SUCCEEDED', nodes: [] });
  const source = await fixture.watch();
  await source.onerror();
  assert.equal(source.closed, true);
});

test('interrupted replay with a terminal snapshot reconnects for remaining events', async () => {
  const fixture = browserFixture({ id: 'run-a', workflowId: 'workflow-a', status: 'SUCCEEDED', nodes: [] }, [{ seq: 2 }]);
  const source = await fixture.watch();
  await source.onerror();
  assert.equal(source.closed, false);
});
