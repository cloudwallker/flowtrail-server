# FlowTrail Server API

默认地址为 `http://127.0.0.1:18081`。所有请求和响应都使用 JSON，时间字段采用 ISO-8601 UTC 格式。

## 健康检查

```http
GET /api/health
```

```json
{"status":"UP","service":"flowtrail-server"}
```

## 工作流

创建工作流：

```http
POST /api/workflows
Content-Type: application/json
```

```json
{
  "name": "greeting",
  "nodes": [
    {
      "id": "summary",
      "type": "TEXT",
      "dependsOn": ["greeting"],
      "text": "${greeting.output} Done."
    },
    {
      "id": "greeting",
      "type": "TEXT",
      "text": "Hello ${input.name}"
    }
  ]
}
```

成功返回 `201` 和持久化后的 `Workflow`：

```json
{
  "id": "3f357d30-81eb-4db1-b114-c64db06ee9ef",
  "name": "greeting",
  "nodes": [
    {
      "id": "summary",
      "type": "TEXT",
      "dependsOn": ["greeting"],
      "text": "${greeting.output} Done.",
      "url": null,
      "method": null,
      "headers": {},
      "body": null,
      "timeoutMs": null
    },
    {
      "id": "greeting",
      "type": "TEXT",
      "dependsOn": [],
      "text": "Hello ${input.name}",
      "url": null,
      "method": null,
      "headers": {},
      "body": null,
      "timeoutMs": null
    }
  ],
  "createdAt": "2026-09-16T05:00:00Z"
}
```

查询接口：

```http
GET /api/workflows
GET /api/workflows/{id}
```

第一个接口直接返回 `Workflow[]`，第二个返回单个 `Workflow`。

只校验、不保存：

```http
POST /api/workflows/validate
Content-Type: application/json
```

请求体与创建工作流相同。上面的乱序定义返回稳定拓扑顺序：

```json
{"valid":true,"order":["greeting","summary"]}
```

## 节点字段

`Node` 包含 `id`、`type`、`dependsOn`、`text`、`url`、`method`、`headers`、`body`、`timeoutMs`。

- `TEXT` 节点必须提供字符串 `text`。
- `HTTP` 节点必须提供字符串 `url`；`method` 缺省为 `GET`，只接受 `GET` 或 `POST`。
- `GET` 不允许 `body`。`headers` 是字符串到字符串的对象。
- `timeoutMs` 缺省为 `3000`，范围为 `100` 至 `30000`。
- `dependsOn` 缺省为 `[]`。
- 定义最多包含 30 个节点，节点 id 必须匹配 `[A-Za-z][A-Za-z0-9_]{0,39}`。

JSON 类型严格匹配。数字和布尔值不会转换成字符串，字符串和小数不会转换成 `timeoutMs`，数字不会转换成枚举。

## 引用

只支持两种引用：

- `${input.key}` 读取运行输入。
- `${nodeId.output}` 读取祖先节点输出。

节点输出引用必须指向依赖图中的祖先。替换只执行一轮，因此输入值里的 `${...}` 会保留为普通文本。输入、节点配置和请求头中的每个字符串最长 20000 字符。每个字段完成插值后的结果最多为 262144 个 UTF-16 code unit；超过限制会令当前节点失败，后续节点跳过，并持久化本次运行。

## 运行工作流

```http
POST /api/workflows/{id}/runs
Content-Type: application/json
```

```json
{"inputs":{"name":"Ada"}}
```

`inputs` 缺省为 `{}`，最多包含 50 个字符串字段。所有必需输入会在任何节点执行前检查。

成功运行返回 `200`：

```json
{
  "id": "6ed8b763-c580-4792-a3aa-7adb73c3ccdc",
  "workflowId": "3f357d30-81eb-4db1-b114-c64db06ee9ef",
  "status": "SUCCEEDED",
  "inputs": {"name":"Ada"},
  "nodes": [
    {"id":"greeting","status":"SUCCEEDED","output":"Hello Ada","error":null,"durationMs":0},
    {"id":"summary","status":"SUCCEEDED","output":"Hello Ada Done.","error":null,"durationMs":0}
  ],
  "startedAt": "2026-09-16T05:01:00Z",
  "finishedAt": "2026-09-16T05:01:00.004Z"
}
```

节点失败时接口仍返回 `200` 和已持久化的 `Run`。失败节点为 `FAILED`，后续尚未执行的节点为 `SKIPPED`，整体状态为 `FAILED`。

查询运行记录：

```http
GET /api/runs/{id}
GET /api/workflows/{id}/runs
```

历史接口直接返回最近 50 条 `Run[]`，按 `startedAt` 降序排列。

## HTTP 节点限制

- URL 只允许 `http` 和 `https`，禁止内嵌凭据与 fragment。
- 动态 URL 在引用替换后重新校验。
- Apache HttpClient 显式禁用自动跳转和自动重试，每个 HTTP 节点只发送一次请求。
- 超时覆盖连接、响应头和完整响应正文。
- 响应正文最大为 256 KiB。
- 非 2xx 状态会令节点失败，错误响应正文不会写入运行记录。

## 错误

参数或定义错误返回 `400`，资源不存在返回 `404`，未处理的服务错误返回 `500`。错误正文统一为：

```json
{"code":"VALIDATION_ERROR","message":"Required input is missing: name"}
```

无效 JSON 使用 `INVALID_JSON`，不存在使用 `NOT_FOUND`，内部错误使用 `INTERNAL_ERROR`。内部异常、原请求体和数据库细节不会出现在响应中。
