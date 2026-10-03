(function (scope) {
  'use strict';
  function createState(runId) {
    return { runId, cursor: 0, events: [], attempts: new Map() };
  }
  function isTerminal(status) {
    return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW'].includes(status);
  }
  function acceptEvent(state, event) {
    if (event.runId !== state.runId || !Number.isSafeInteger(event.seq) || event.seq <= state.cursor) return false;
    if (event.seq !== state.cursor + 1) throw new Error('Event sequence gap; reconnect from the last committed cursor.');
    state.cursor = event.seq;
    state.events.push(event);
    if (state.events.length > 150) state.events.shift();
    if (event.nodeId && event.attemptId != null) {
      const key = event.nodeId + ':' + event.attemptId;
      const attempt = state.attempts.get(key) || { nodeId: event.nodeId, attemptId: event.attemptId, text: '', status: 'RUNNING' };
      if (event.type === 'LLM_DELTA') attempt.text += event.payload?.text || '';
      if (event.type.startsWith('NODE_')) attempt.status = event.type.substring(5);
      // The backend bounds each node output. Bound the browser view as well.
      if (attempt.text.length > 262144) attempt.text = attempt.text.substring(0, 262144);
      state.attempts.set(key, attempt);
    }
    return true;
  }
  const api = { createState, acceptEvent, isTerminal };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else scope.FlowTrailState = api;
})(typeof window === 'undefined' ? globalThis : window);
