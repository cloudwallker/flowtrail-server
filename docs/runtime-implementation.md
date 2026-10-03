# FlowTrail Server 持久化运行时

本说明对应 0.2.0 源码。服务将经过校验的定义、输入和模型配置版本落库，返回 `202 Accepted`；协调器随后领取运行并调度节点。API 返回的运行对象可能是 `QUEUED`、`RUNNING` 或已完成状态，客户端通过运行查询和 SSE 跟踪结果。

## 执行器和并发

`execution/NodeExecutor` 定义节点接口；`NodeExecutorRegistry` 注册 TEXT、HTTP、LLM。`RunCoordinator` 使用独立协调线程与 `CompletableFuture.runAsync` 投递节点。节点线程不等待同一个池内的后继任务。

默认每实例 8 个工作线程、32 个等待任务，每个运行最多 4 个已投递任务；同时在内存跟踪最多 32 个运行，其余运行留在数据库中。资源参数位于 `flowtrail.runtime.*`；工作流定义最多 30 个节点。就绪状态每轮从持久化成功状态重新计算，通过节点状态条件更新防止重复领取。队列满时保留待执行节点，下一轮继续调度。

`ExecutionContext` 复制并封装本次输入和已成功祖先输出。执行器仅对定义中的模板做一次替换，插入的业务文本不会再次解释。LLM 将系统提示与用户提示分别构造为角色消息。输出和插值均有大小上限。

节点结果、节点状态和完成事件在同一事务中提交；下一轮协调器只能从已提交状态中读取输出。发现终结性失败后停止投递后继，已启动节点收敛为实际结果，未启动节点最终标记 `SKIPPED`。接管时若并行分支已失败，存在持久化外部操作的中断节点先按其真实状态收敛：`UNKNOWN` 转人工核查，`CONFIRMED` 复用已存响应并标记成功，`DECLINED` 标记失败；仅尚未发送的 `PREPARED` 可跳过。该收敛不发起新请求，不新增尝试。

## 数据库与租约

默认 H2 路径为 `./data/flowtrail-runtime-v2`，与旧 `./data/flowtrail` 分离。`mysql` profile 使用 MySQL 驱动和 Flyway 的 `V1__durable_runtime.sql`。旧的 `workflows/runs` 数据模型保留用于旧历史存储兼容；新运行存入以下独立表：

| 表 | 用途 |
| --- | --- |
| `workflow_version` | 工作流不可变版本定义 |
| `workflow_run` | 定义/模型快照、输入、请求幂等、运行状态、租约、事件序号 |
| `node_run` | 当前节点状态、尝试号、已提交结果、下一次可执行时间 |
| `node_attempt` | 每次尝试的输入摘要、状态、结果和起止时间 |
| `run_event` | 持久化状态与模型片段事件 |
| `external_operation` | 稳定外部操作键、冻结请求和摘要、核查状态 |

领取和写回使用 `SELECT ... FOR UPDATE` 锁住运行行，所有续租、完成、片段及外部操作写入均检查 `owner + epoch + 未过期租约 + RUNNING`。租约依据数据库 `CURRENT_TIMESTAMP` 判断；接管递增 epoch。调用 HTTP 和模型时没有持有数据库事务。

默认租约 15 秒，协调器约每 5 秒续租。重启后的协调器自动扫描 `QUEUED` 或租约已过期的 `RUNNING`。成功节点直接复用；未完成尝试标记 `INTERRUPTED`，新尝试使用新 attemptId。中断模型流重新生成，不拼接旧尝试的 token。

只读 HTTP 的网络/超时、429、5xx 错误最多再试 2 次，间隔默认 1 秒、3 秒；重试时间落库，不占用节点线程等待。业务写请求进入单独的核查流程。`resume` 为显式恢复入口；有效租约返回 409，成功或已排队运行幂等返回。

## 外部写与未知结果

POST 先存 `PREPARED` 意图，然后在发送前将状态改为 `UNKNOWN`。请求 URL、body、headers、timeout 和查询协议一起冻结，稳定键为 `runId:nodeId`。再次执行始终读取冻结请求，不能用新键或新参数替代旧操作。

声明的 `idempotency` 契约要求下游按 `Idempotency-Key` 对同键同参去重，并提供 `lookupUrl`：200 返回原响应，404 明确表示不存在。接到不确定结果时先查询原键；只有明确缺失才能用原键原请求再发。查询最多 3 次。查询失败、预算耗尽或缺少协议时转 `MANUAL_REVIEW`，后继停止。该语义依赖下游实际遵守契约。

## Spring AI 与事件

LLM 节点使用 `modelRef`、`systemPrompt`、`userPrompt` 与可选 `timeoutMs`。`mock-demo` 输出确定性片段；`live-default` 使用 Spring AI 1.1.8 的 `OpenAiApi`、`OpenAiChatModel` 与 `ChatClient.stream().content()`。模型地址、名称、参数和版本在创建时固定；API key 从部署配置读取，不写入模型快照或事件。取凭据前必须确认当前部署的模型引用、模式、地址、名称及版本与运行快照匹配；配置绑定变化时拒绝请求，同一绑定允许密钥轮换。模型调用错误明确失败。

片段按 200ms 或最多 512 个 Unicode 码点合批（最多 2 KiB UTF-8），落库后才可被 SSE 读取。最后一批在节点成功事务之前完成持久化。序号在运行行锁内分配，`run_event(run_id, seq)` 为主键。

SSE 使用 `event: workflow`，数据包含 `runId/nodeId/attemptId/seq/type/payload`。片段类型为 `LLM_DELTA`，内容在 `payload.text`。支持 `Last-Event-ID` 和 `?after=`，取较大的游标；客户端按 seq 去重、按 nodeId 和 attemptId 区分流。终态追平后关闭。连接线程和等待队列有界，每次最多读取 100 个事件，连接定期关闭后可使用游标重连。

## 验证入口

`mvn clean verify` 执行原有接口/HTTP/校验测试，以及 `RuntimeAcceptanceTest`、`RuntimeLimitsTest`、`FailureConvergenceTest`、`CheckpointLeaseTest`、`ExternalWriteTest`、`InterruptedWriteConvergenceTest`、`ModelCredentialBindingTest` 和 `ModelProtocolTest`。其中模型协议测试启动本地 OpenAI 兼容 HTTP 服务，经真实 Spring AI 客户端验证片段、500 错误和恢复时的凭据绑定。

真实 MySQL 的行锁和 epoch 检查入口为 `MySqlCheckpointTest`。设置 `FLOWTRAIL_TEST_MYSQL_URL`、`FLOWTRAIL_TEST_MYSQL_USER`、`FLOWTRAIL_TEST_MYSQL_PASSWORD` 后运行；测试通过真实 MySQL JDBC 连接及 Flyway 建表。未提供该 URL 时 JUnit 明确跳过这组测试，H2 测试不会冒充 MySQL 验收。
