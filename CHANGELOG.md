# Changelog

## 0.2.0 — 2026-10-03

- `POST /api/workflows/{id}/runs` 改为异步 `202 Accepted`，返回持久化运行的 ID 与快照；通过 GET/SSE 跟踪结果。
- 新增 TEXT/HTTP/LLM 执行器注册表、有界 DAG 并行、独立上下文与逐节点检查点。
- 新增 MySQL/Flyway、owner/epoch 租约、自动接管、attempt 历史和显式 resume。
- 新增持久化 SSE、游标回放、mock/Spring AI 模型节点及监控页。
- POST 外部操作冻结请求与幂等键；未知结果先核查，缺少可靠协议时进入人工核查。
- 默认 H2 文件改为 `data/flowtrail-runtime-v2`。旧 `data/flowtrail` 保留；参见迁移说明。

## 0.1.0

顺序执行 JSON DAG、TEXT/HTTP 节点、H2 完成历史与本地页面。
