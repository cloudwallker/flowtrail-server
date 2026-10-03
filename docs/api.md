# FlowTrail Server 0.2 API

默认地址 `http://127.0.0.1:18081`。JSON 使用严格字段类型，拒绝未知字段和重复属性；时间为 ISO-8601 UTC。

| 接口 | 响应 |
| --- | --- |
| `GET /api/health` | 200，`{"status":"UP","service":"flowtrail-server"}` |
| `POST /api/workflows/validate` | 200，`valid` 和稳定拓扑 `order` |
| `POST /api/workflows` | 201，保存后的 Workflow |
| `GET /api/workflows` / `{id}` | 工作流列表 / 单个工作流 |
| `POST /api/workflows/{id}/runs` | 202，持久创建的 Run；`Location: /api/runs/{runId}` |
| `GET /api/runs/{id}` | 当前状态、节点输出和每次尝试 |
| `GET /api/workflows/{id}/runs` | 最近 50 个运行，开始时间降序 |
| `POST /api/runs/{id}/resume` | 202，显式恢复或幂等返回；有效租约为 409 |
| `GET /api/runs/{id}/events` | `text/event-stream`，持久化事件重放 |
| `GET /api/runs/{id}/events/history?after=0` | 游标之后最多 1000 个事件，继续分页需传最后 seq |

## 定义

```json
{
  "name": "summary-demo",
  "nodes": [
    {"id":"text","type":"TEXT","text":"${input.document}"},
    {"id":"summary","type":"LLM","dependsOn":["text"],"modelRef":"mock-demo","systemPrompt":"归纳业务文档","userPrompt":"${text.output}"}
  ]
}
```

1–30 个节点，id 匹配 `[A-Za-z][A-Za-z0-9_]{0,39}`，`dependsOn` 默认为空。引用仅允许 `${input.key}` 和 `${ancestor.output}`，插值只解释一遍。每个配置字符串最多 20000 字符，完成插值结果最多 262144 UTF-16 code unit。

| 节点 | 配置 |
| --- | --- |
| TEXT | 必需 `text` |
| HTTP | 必需 `url`；`method` 默认 GET，只接受 GET / POST；`headers` 字符串对象；POST 可带 `body`；`timeoutMs` 默认 3000、范围 100–30000 |
| LLM | 必需 `modelRef` 和 `userPrompt`；可选 `systemPrompt`；`modelRef` 为 `mock-demo` 或 `live-default`；`timeoutMs` 默认 30000、范围 100–120000 |

HTTP URL 只允许 HTTP/HTTPS，禁止内嵌凭据与 fragment；动态 URL 在替换后重新校验。不自动跟随跳转或底层重试，响应正文最大 256 KiB。只读网络/超时、429、5xx 由工作流协调器最多再试两次；POST 使用外部操作协议。

## 创建与查询运行

```http
POST /api/workflows/{id}/runs
Content-Type: application/json
Idempotency-Key: demo-request-1

{"inputs":{"document":"业务文档内容"}}
```

`inputs` 默认为 `{}`，最多 50 个字符串字段；所有必需输入在外部调用前检查。同工作流、同键同参返回原 Run，异参 409。键可省略，省略时每个请求创建新运行。

202 表示已持久创建，返回的 `status` 可能为 QUEUED、RUNNING 或终态。通过 GET 等待 SUCCEEDED / FAILED / MANUAL_REVIEW；运行内的业务失败不会转换成查询 HTTP 错误。节点包括 `attemptId` 和 `attempts`，重启中断尝试保留为 INTERRUPTED。

## SSE

```http
GET /api/runs/{id}/events?after=7
Last-Event-ID: 9
```

取两个游标较大值，返回 seq 大于该值的已提交事件。负数或无效游标返回 400。

```text
id: 10
event: workflow
data: {"runId":"...","nodeId":"summary","attemptId":1,"seq":10,"type":"LLM_DELTA","payload":{"text":"摘要片段"}}
```

事件含 `runId/nodeId/attemptId/seq/type/payload`。客户端按 seq 去重，按 nodeId 和 attemptId 分开模型答案；旧终态事件可能出现在恢复后的历史中，应追平当前数据库尾部后再结束。服务端终态追平后关闭，活动连接定期关闭以释放资源，可带游标重连。容量耗尽返回 429。

## 外部 POST 协议

```json
{
  "id":"save","type":"HTTP","url":"http://127.0.0.1:18082/reports",
  "method":"POST","body":"${report.output}","dependsOn":["report"],
  "idempotency":{"supported":true,"lookupUrl":"http://127.0.0.1:18082/reports/by-key/{key}"}
}
```

运行时使用稳定 `Idempotency-Key: runId:nodeId`，先冻结请求并提交意图，再发送。协议要求下游同键同参重放原响应、异参冲突，lookup 200 返回原结果、404 明确未执行。超时、断线或接管先核查原键；明确缺失才允许原请求原键再发。无法可靠核查进入 MANUAL_REVIEW，`resume` 不绕过核查。

## 错误

参数 / JSON 为 400，资源缺失 404，冲突 409，SSE 容量 429，内部错误 500：

```json
{"code":"VALIDATION_ERROR","message":"Required input is missing: document"}
```

常见 code：`INVALID_JSON`、`VALIDATION_ERROR`、`NOT_FOUND`、`CONFLICT`、`SSE_CAPACITY`、`INTERNAL_ERROR`。不向客户端返回原请求、数据库详情或内部堆栈。
