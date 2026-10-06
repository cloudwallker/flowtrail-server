'use strict';
const $ = id => document.getElementById(id);
const { createState, acceptEvent, isTerminal } = window.FlowTrailState;
let selectedId = null;
let savedDefinition = '';
let active = null;
let generation = 0;
const templates = {
  hello: {
    definition: { name: '离线文本流程', nodes: [
      { id: 'summary', type: 'TEXT', dependsOn: ['greeting'], text: '流程完成：${greeting.output}' },
      { id: 'greeting', type: 'TEXT', dependsOn: [], text: '你好，${input.name}！' }
    ] }, inputs: { name: '开发者' }
  },
  summary: {
    definition: { name: '文档摘要（mock）', nodes: [
      { id: 'summary', type: 'LLM', dependsOn: [], modelRef: 'mock-demo', systemPrompt: '归纳用户提供的文档，给出简洁摘要。', userPrompt: '${input.document}' }
    ] }, inputs: { document: 'FlowTrail 使用有界并行调度执行 DAG，并保存每个节点的检查点与事件。' }
  },
  analysis: {
    definition: { name: '接口数据解读（mock）', nodes: [
      { id: 'metrics', type: 'HTTP', dependsOn: [], url: '${input.metricsUrl}', method: 'GET' },
      { id: 'analysis', type: 'LLM', dependsOn: ['metrics'], modelRef: 'mock-demo', systemPrompt: '解释指标并指出需要关注的变化。', userPrompt: '${metrics.output}' }
    ] }, inputs: { metricsUrl: 'http://127.0.0.1:18082/metrics' }
  },
  report: {
    definition: { name: '并行生成业务报告（mock）', nodes: [
      { id: 'project', type: 'TEXT', dependsOn: [], text: '${input.project}' },
      { id: 'metrics', type: 'HTTP', dependsOn: ['project'], url: '${input.metricsUrl}', method: 'GET' },
      { id: 'summary', type: 'LLM', dependsOn: ['metrics'], modelRef: 'mock-demo', systemPrompt: '根据声明的项目与指标生成业务摘要。', userPrompt: '项目：${project.output}\n指标：${metrics.output}' },
      { id: 'risks', type: 'LLM', dependsOn: ['metrics'], modelRef: 'mock-demo', systemPrompt: '根据声明的项目与指标分析风险。', userPrompt: '项目：${project.output}\n指标：${metrics.output}' },
      { id: 'report', type: 'TEXT', dependsOn: ['summary', 'risks'], text: '# 业务报告\n\n## 摘要\n${summary.output}\n\n## 风险\n${risks.output}' },
      { id: 'save', type: 'HTTP', dependsOn: ['report'], url: 'http://127.0.0.1:18082/reports', method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: '${report.output}', idempotency: { supported: true, lookupUrl: 'http://127.0.0.1:18082/reports/by-key/{key}' } }
    ] }, inputs: { project: '面向 Java 开发者的可恢复工作流演示', metricsUrl: 'http://127.0.0.1:18082/metrics' }
  }
};
function notice(message, error = false) {
  $('notice').hidden = false; $('notice').className = 'notice' + (error ? ' error' : ''); $('notice').textContent = message;
}
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
  const value = response.status === 204 ? null : await response.json();
  if (!response.ok) throw new Error(value?.message || '请求失败（' + response.status + '）');
  return value;
}
function element(tag, text, className) {
  const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node;
}
function empty(container, text) { container.replaceChildren(element('div', text, 'empty')); }
function badge(value) { return element('span', value, 'status ' + (value || '').toLowerCase()); }
function showRun(run) {
  $('run-status').textContent = run.status; $('run-status').className = 'status ' + run.status.toLowerCase();
  $('run-meta').textContent = run.id + ' · ' + (run.startedAt ? new Date(run.startedAt).toLocaleString() : '等待执行');
  $('resume').disabled = !['FAILED', 'INTERRUPTED', 'MANUAL_REVIEW'].includes(run.status);
  $('nodes').replaceChildren();
  for (const result of run.nodes || []) {
    const card = element('div', null, 'item'); const head = element('div', null, 'item-head');
    head.append(element('span', result.id, 'item-title'), badge(result.status)); card.append(head);
    card.append(element('div', (result.durationMs ?? 0) + ' ms' + (result.attemptId != null ? ' · attempt ' + result.attemptId : ''), 'meta'));
    card.append(element('pre', result.error || result.output || (result.status === 'SKIPPED' ? '前序失败，未执行' : '等待节点输出')));
    if (Array.isArray(result.attempts) && result.attempts.length) {
      const details = element('details'); details.append(element('summary', '查看 ' + result.attempts.length + ' 次尝试'));
      for (const attempt of result.attempts) details.append(element('pre', JSON.stringify(attempt, null, 2)));
      card.append(details);
    }
    $('nodes').append(card);
  }
}
function showEvents(state) {
  $('cursor').textContent = 'seq ' + state.cursor;
  $('events').replaceChildren();
  for (const event of state.events) {
    const line = element('div', null, 'event');
    line.append(element('span', '#' + event.seq, 'event-seq'), element('span', event.type + (event.nodeId ? ' · ' + event.nodeId : '') + (event.attemptId != null ? ' / ' + event.attemptId : '')));
    $('events').append(line);
  }
  $('events').scrollTop = $('events').scrollHeight;
  $('attempts').replaceChildren();
  for (const attempt of state.attempts.values()) {
    if (!attempt.text) continue;
    const card = element('div', null, 'item');
    const head = element('div', null, 'item-head');
    head.append(element('span', attempt.nodeId + ' · attempt ' + attempt.attemptId, 'item-title'), badge(attempt.status));
    card.append(head, element('pre', attempt.text)); $('attempts').append(card);
  }
  if (!$('attempts').childElementCount) empty($('attempts'), '尚无模型流式输出');
}
function stopMonitor() {
  generation++;
  if (active) { active.source?.close(); clearTimeout(active.retry); clearInterval(active.poll); }
  active = null;
  $('reconnect').disabled = true; $('resume').disabled = true; $('connection').textContent = '未连接';
}
async function refreshRun(monitor) {
  const run = await api('/api/runs/' + encodeURIComponent(monitor.runId));
  if (active !== monitor) return;
  monitor.run = run; showRun(run);
  if (isTerminal(run.status)) {
    clearInterval(monitor.poll);
    await history(run.workflowId);
  }
}
function connect(monitor) {
  if (active !== monitor) return;
  monitor.source?.close(); clearTimeout(monitor.retry);
  $('connection').textContent = '连接中 · 从 seq ' + monitor.state.cursor + ' 回放';
  const source = new EventSource('/api/runs/' + encodeURIComponent(monitor.runId) + '/events?after=' + monitor.state.cursor);
  monitor.source = source;
  source.onopen = () => { if (active === monitor) $('connection').textContent = '事件流已连接'; };
  const receive = event => {
    if (active !== monitor) return;
    try {
      const value = JSON.parse(event.data);
      if (!acceptEvent(monitor.state, value)) return;
      showEvents(monitor.state);
      if (value.type !== 'LLM_DELTA') refreshRun(monitor).catch(error => notice(error.message, true));
    } catch (error) {
      source.close(); $('connection').textContent = '补齐事件中';
      monitor.retry = setTimeout(() => connect(monitor), 1000);
    }
  };
  source.addEventListener('workflow', receive); source.onmessage = receive;
  source.onerror = async () => {
    if (active !== monitor || monitor.source !== source) return;
    $('connection').textContent = '连接中断，核对事件游标';
    try {
      await refreshRun(monitor);
      const remaining = await api('/api/runs/' + encodeURIComponent(monitor.runId) + '/events/history?after=' + monitor.state.cursor);
      if (active !== monitor || monitor.source !== source) return;
      // A resumed run contains historical terminal events. Finish only at the current persisted tail.
      if (isTerminal(monitor.run.status) && remaining.length === 0) {
        source.close(); $('connection').textContent = '事件回放结束 · seq ' + monitor.state.cursor;
      } else $('connection').textContent = '连接中断，自动重连并补齐事件';
    } catch (error) {
      if (active === monitor && monitor.source === source) $('connection').textContent = '连接中断，自动重连并补齐事件';
    }
  };
}
async function watchRun(runId) {
  stopMonitor();
  const monitor = { runId, state: createState(runId), source: null, run: null };
  active = monitor; $('reconnect').disabled = false; showEvents(monitor.state);
  await refreshRun(monitor);
  if (active !== monitor) return;
  connect(monitor);
  if (!isTerminal(monitor.run.status)) monitor.poll = setInterval(() => refreshRun(monitor).catch(() => {}), 2000);
}
async function history(id) {
  const values = await api('/api/workflows/' + encodeURIComponent(id) + '/runs');
  if (id !== selectedId) return;
  if (!values.length) return empty($('history'), '暂无运行记录');
  $('history').replaceChildren();
  for (const run of values) {
    const card = element('div', null, 'item'); const head = element('div', null, 'item-head');
    const button = element('button', run.startedAt ? new Date(run.startedAt).toLocaleString() : run.id, 'link-button');
    button.addEventListener('click', () => action(() => watchRun(run.id)));
    head.append(button, badge(run.status)); card.append(head, element('div', run.id, 'meta')); $('history').append(card);
  }
}
async function refresh() {
  const values = await api('/api/workflows');
  if (!values.length) return empty($('workflows'), '尚未保存工作流');
  $('workflows').replaceChildren();
  for (const workflow of values) {
    const card = element('div', null, 'item'); const button = element('button', workflow.name, 'link-button');
    button.addEventListener('click', () => action(async () => {
      const value = await api('/api/workflows/' + encodeURIComponent(workflow.id));
      stopMonitor();
      const definition = { name: value.name, nodes: value.nodes };
      selectedId = value.id; savedDefinition = JSON.stringify(definition);
      $('definition').value = JSON.stringify(definition, null, 2); await history(value.id);
      $('run-status').textContent = 'READY'; $('run-status').className = 'status';
      $('run-meta').textContent = '已选择：' + value.name;
      empty($('nodes'), '运行工作流，或点击历史记录回放执行轨迹。'); notice('已载入：' + value.name);
    }));
    card.append(button, element('div', workflow.id, 'meta')); $('workflows').append(card);
  }
}
async function action(work) {
  $('operation-status').hidden = false;
  document.querySelector('main').setAttribute('aria-busy', 'true');
  const buttons = [...document.querySelectorAll('button')];
  const previouslyDisabled = new Set(buttons.filter(button => button.disabled));
  buttons.forEach(button => button.disabled = true);
  try { await work(); } catch (error) { notice(error.message, true); }
  finally {
    $('operation-status').hidden = true;
    document.querySelector('main').setAttribute('aria-busy', 'false');
    buttons.forEach(button => button.disabled = previouslyDisabled.has(button));
    $('reconnect').disabled = !active;
    $('resume').disabled = !active?.run || !['FAILED', 'INTERRUPTED', 'MANUAL_REVIEW'].includes(active.run.status);
  }
}
function loadTemplate() {
  const template = templates[$('template').value]; stopMonitor();
  $('definition').value = JSON.stringify(template.definition, null, 2);
  $('inputs').value = JSON.stringify(template.inputs, null, 2);
  selectedId = null; savedDefinition = ''; empty($('history'), '保存模板后可查看历史');
  notice('已载入：' + template.definition.name);
}
$('example').addEventListener('click', loadTemplate);
$('validate').addEventListener('click', () => action(async () => {
  const value = await api('/api/workflows/validate', { method: 'POST', body: $('definition').value });
  notice('校验通过。拓扑顺序：' + value.order.join(' → '));
}));
$('run').addEventListener('click', () => action(async () => {
  const definition = JSON.parse($('definition').value); const inputs = JSON.parse($('inputs').value);
  await api('/api/workflows/validate', { method: 'POST', body: $('definition').value });
  if (!selectedId || savedDefinition !== JSON.stringify(definition)) {
    const workflow = await api('/api/workflows', { method: 'POST', body: $('definition').value });
    selectedId = workflow.id; savedDefinition = JSON.stringify(definition);
  }
  const run = await api('/api/workflows/' + encodeURIComponent(selectedId) + '/runs', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ inputs }) });
  await watchRun(run.id); await refresh(); await history(selectedId);
  notice('运行已创建，后台执行中。可切换页面后从历史记录查看。');
}));
$('reconnect').addEventListener('click', () => { if (active) connect(active); });
$('resume').addEventListener('click', () => action(async () => {
  if (!active) return;
  const id = active.runId;
  await api('/api/runs/' + encodeURIComponent(id) + '/resume', { method: 'POST' });
  await watchRun(id); notice('恢复请求已接受，继续检查运行状态与外部操作核查结果。');
}));
$('refresh').addEventListener('click', () => action(refresh));
window.addEventListener('beforeunload', stopMonitor);
loadTemplate(); action(refresh);
