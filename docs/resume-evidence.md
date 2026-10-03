# FlowTrail Server：能力、代码与演示

项目名称：**FlowTrail Server｜可恢复工作流编排服务**。技术栈：Java 21、Spring Boot 3.5.11、Spring AI 1.1.8、Spring JDBC、MySQL 8.4、Flyway、H2、SSE、CompletableFuture。

可用于面试的项目描述：

> 设计 JSON DAG 和统一节点执行器，支持 TEXT / HTTP / LLM 的有界并行调度。运行与节点结果逐步持久化，使用数据库时间租约和 owner/epoch 隔离失效持有者；通过 SSE 提供带游标和尝试编号的事件重放。外部 POST 先冻结请求和稳定幂等键，结果未知先核查，无法确认时进入人工处理，避免故障恢复盲目重放。

| 能力 | 代码入口 | 证据 |
| --- | --- | --- |
| DSL 与变量隔离 | `validation/WorkflowValidator`、`execution/ExecutionContext` | 环/非祖先引用/缺输入前置拒绝，跨运行隔离；原接口与 RuntimeAcceptanceTest |
| 有界并行和失败收敛 | `runtime/RunCoordinator` | 菱形分支屏障重叠，汇合一次；RuntimeLimitsTest、FailureConvergenceTest、QueuedFailureTest |
| 模型接入与流式处理 | `execution/LlmNodeExecutor`、`ModelCatalog` | Spring AI 本地兼容协议测试，角色提示分离、片段合批、错误明确失败 |
| MySQL 检查点 | `persistence/RuntimeStore`、`db/migration/V1__durable_runtime.sql` | 真实 MySQL 行锁/epoch 回归及强杀进程后成功节点调用次数不增加 |
| SSE 重放 | `events/EventController`、`run_event`、网页状态模块 | Last-Event-ID 补齐，seq 去重、attempt 分流，恢复后穿过旧终态继续回放 |
| 外部写核查 | `execution/HttpNodeExecutor`、`external_operation` | 响应丢失后按原键找回结果，一份报告；无协议转 MANUAL_REVIEW 且不重发 |

## 演示路径

启动服务与 `scripts/mock_reports.py`，网页依次运行离线文本、文档摘要、接口解读和业务报告模板。业务报告有两条并行 LLM 分支、一次汇合和一次幂等保存。

自动故障演示：

```sh
python scripts/recovery_smoke.py
python scripts/recovery_smoke.py --mysql
```

MySQL 使用独立本机测试库，通过 `FLOWTRAIL_TEST_DB_URL / USER / PASSWORD` 提供。脚本验证五组场景，其中第 2、3 组真实强杀 Java 进程并重启：

1. 异步创建、请求幂等、SSE 断线与游标重放。
2. 成功检查点调用一次；中断 HTTP 新 attempt；汇合一次。
3. 模型流旧尝试 INTERRUPTED，新尝试单独生成。
4. 下游已保存但响应丢失，查询后成功，writes=1 / postCalls=1。
5. 没有可靠查询协议时进入 MANUAL_REVIEW，显式 resume 后仍不盲目重发。

当前参数为实例内 8 工作线程、每运行 4 个投递任务、队列 32；它们是资源上限。模型中断恢复重新生成，外部幂等依赖下游契约，不能据此承诺任意系统 exactly-once。

设计细节见 [运行时说明](runtime-implementation.md)，接口见 [API](api.md)，构建与实际验收见 [验证记录](verification-0.2.md)。
