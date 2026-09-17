'use strict';
const $ = id => document.getElementById(id);
let selectedId = null;
let savedDefinition = '';
const example = {
  name: '我的第一条工作流',
  nodes: [
    { id: 'summary', type: 'TEXT', dependsOn: ['greeting'], text: '流程完成：${greeting.output}' },
    { id: 'greeting', type: 'TEXT', dependsOn: [], text: '你好，${input.name}！' }
  ]
};
function notice(message, error = false) { $('notice').hidden = false; $('notice').className = 'notice' + (error ? ' error' : ''); $('notice').textContent = message; }
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
  const value = response.status === 204 ? null : await response.json();
  if (!response.ok) throw new Error(value?.message || '请求失败（' + response.status + '）');
  return value;
}
function element(tag, text, className) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
function empty(container, text) { container.replaceChildren(element('div', text, 'empty')); }
function status(value) { return element('span', value, 'status ' + (value || '').toLowerCase()); }
function showRun(run) {
  $('run-status').textContent = run.status; $('run-status').className = 'status ' + run.status.toLowerCase();
  $('run-meta').textContent = run.id + ' · ' + new Date(run.startedAt).toLocaleString();
  $('nodes').replaceChildren();
  for (const result of run.nodes) {
    const card = element('div', null, 'item'); const head = element('div', null, 'item-head');
    head.append(element('span', result.id, 'item-title'), status(result.status)); card.append(head);
    card.append(element('div', (result.durationMs ?? 0) + ' ms', 'meta'));
    card.append(element('pre', result.error || result.output || (result.status === 'SKIPPED' ? '前序失败，未执行' : '无文本输出')));
    $('nodes').append(card);
  }
}
async function history(id) {
  const values = await api('/api/workflows/' + encodeURIComponent(id) + '/runs');
  if (!values.length) return empty($('history'), '暂无运行记录');
  $('history').replaceChildren();
  for (const run of values) {
    const card = element('div', null, 'item'); const head = element('div', null, 'item-head');
    const button = element('button', new Date(run.startedAt).toLocaleString(), 'link-button');
    button.addEventListener('click', () => showRun(run)); head.append(button, status(run.status));
    card.append(head, element('div', run.id, 'meta')); $('history').append(card);
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
      const definition = { name: value.name, nodes: value.nodes };
      selectedId = value.id; savedDefinition = JSON.stringify(definition);
      $('definition').value = JSON.stringify(definition, null, 2); await history(value.id);
      $('run-status').textContent = 'READY'; $('run-status').className = 'status';
      $('run-meta').textContent = '已选择：' + value.name;
      empty($('nodes'), '运行工作流，或点击历史记录查看执行轨迹。');
      notice('已载入工作流：' + value.name);
    }));
    card.append(button, element('div', workflow.id, 'meta')); $('workflows').append(card);
  }
}
async function action(work) {
  const buttons = [...document.querySelectorAll('button')]; buttons.forEach(button => button.disabled = true);
  try { await work(); } catch (error) { notice(error.message, true); }
  finally { buttons.forEach(button => button.disabled = false); }
}
$('example').addEventListener('click', () => { $('definition').value = JSON.stringify(example, null, 2); selectedId = null; savedDefinition = ''; notice('已载入离线示例，节点定义顺序故意与执行顺序不同。'); });
$('validate').addEventListener('click', () => action(async () => {
  const value = await api('/api/workflows/validate', { method: 'POST', body: $('definition').value });
  notice('校验通过。执行顺序：' + value.order.join(' → '));
}));
$('run').addEventListener('click', () => action(async () => {
  const definition = JSON.parse($('definition').value); const inputs = JSON.parse($('inputs').value);
  await api('/api/workflows/validate', { method: 'POST', body: $('definition').value });
  if (!selectedId || savedDefinition !== JSON.stringify(definition)) {
    const workflow = await api('/api/workflows', { method: 'POST', body: $('definition').value });
    selectedId = workflow.id; savedDefinition = JSON.stringify(definition);
  }
  const run = await api('/api/workflows/' + encodeURIComponent(selectedId) + '/runs', { method: 'POST', body: JSON.stringify({ inputs }) });
  showRun(run); await refresh(); await history(selectedId);
  notice(run.status === 'SUCCEEDED' ? '运行完成，结果已保存。' : '运行失败，失败记录已保存。', run.status !== 'SUCCEEDED');
}));
$('refresh').addEventListener('click', () => action(refresh));
$('definition').value = JSON.stringify(example, null, 2);
action(refresh);
