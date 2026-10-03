const test = require('node:test');
const assert = require('node:assert/strict');
const { createState, acceptEvent, isTerminal } = require('../src/main/resources/static/run-state.js');

test('replay duplicates do not append a model fragment twice', () => {
  const state = createState('run-a');
  const event = { runId: 'run-a', nodeId: 'summary', attemptId: 'a1', seq: 1, type: 'LLM_DELTA', payload: { text: 'hello' } };
  assert.equal(acceptEvent(state, event), true);
  assert.equal(acceptEvent(state, event), false);
  assert.equal(state.cursor, 1);
  assert.equal(state.attempts.get('summary:a1').text, 'hello');
});

test('new attempts keep their own answers and ignore another run', () => {
  const state = createState('run-a');
  acceptEvent(state, { runId: 'run-a', nodeId: 'n', attemptId: 'old', seq: 1, type: 'LLM_DELTA', payload: { text: 'interrupted' } });
  acceptEvent(state, { runId: 'run-a', nodeId: 'n', attemptId: 'new', seq: 2, type: 'LLM_DELTA', payload: { text: 'fresh' } });
  assert.equal(acceptEvent(state, { runId: 'run-b', seq: 3, type: 'RUN_SUCCEEDED' }), false);
  assert.equal(state.cursor, 2);
  assert.equal(state.attempts.get('n:new').text, 'fresh');
  assert.equal(state.attempts.get('n:old').text, 'interrupted');
});

test('out of sequence events require replay and do not advance the cursor', () => {
  const state = createState('run-a');
  assert.throws(() => acceptEvent(state, { runId: 'run-a', seq: 2, type: 'NODE_STARTED' }), /gap/);
  assert.equal(state.cursor, 0);
});

test('terminal status recognizes manual review and never ends on an active node', () => {
  assert.equal(isTerminal('MANUAL_REVIEW'), true);
  assert.equal(isTerminal('RUNNING'), false);
  assert.equal(isTerminal('QUEUED'), false);
});
