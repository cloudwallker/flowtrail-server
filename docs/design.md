# FlowTrail Server v0.1

Java 21 独立学习型工作流服务，目标是让开发者理解 DAG 校验、依赖引用、失败传播与执行记录。全新实现，不读取或复制其他平台代码、配置、素材。

## 栈与边界

Spring Boot 3.5.11、Web、Validation、JDBC、H2 文件数据库、Lombok、JUnit。默认监听 127.0.0.1:18081，数据库由环境变量 FLOWTRAIL_DB_URL 覆盖，默认 jdbc:h2:file:./data/flowtrail。默认不需要其他服务。线程可用 Java 21 虚拟线程。未提供认证，不是生产托管平台。

## 接口契约

- GET /api/health -> {status:"UP",service:"flowtrail-server"}
- POST /api/workflows -> 201 Workflow；请求 {name,nodes:[Node]}。
- GET /api/workflows -> Workflow[]；GET /api/workflows/{id} -> Workflow。
- POST /api/workflows/validate -> 200 {valid:true,order:[nodeId]}；不持久化、不执行网络。
- POST /api/workflows/{id}/runs，请求 {inputs:{key:string}}，缺省为空 -> 200 Run。工作流失败也返回 Run，其 status 为 FAILED。
- GET /api/runs/{id} -> Run；GET /api/workflows/{id}/runs -> 最近 50 个 Run，按开始时间降序。
- Workflow = {id,name,nodes,createdAt}。
- Node = {id,type,dependsOn,text,url,method,headers,body,timeoutMs}。type 为 TEXT 或 HTTP；dependsOn 缺省 []。
- Run = {id,workflowId,status,inputs,nodes:[NodeResult],startedAt,finishedAt}。
- NodeResult = {id,status,output,error,durationMs}；状态 SUCCEEDED、FAILED、SKIPPED。失败后所有尚未执行节点 SKIPPED，整体 FAILED。
- 错误统一 {code,message}，参数错误 400，不存在 404，其余 500，不回显原始异常、请求体或数据库细节。

## 定义与执行

name 非空且最长 100 字符；1–30 个节点；id 为 [A-Za-z][A-Za-z0-9_]{0,39}；明确依赖必须存在、无重复、不可自依赖，拒绝环。按定义顺序稳定拓扑排序，顺序执行就绪节点，不实现并行。
TEXT 需要 text；HTTP 需要 url，method 默认 GET，仅 GET/POST，GET 禁止 body，headers 为字符串字典，timeoutMs 默认 3000、范围 100–30000。
引用仅支持 ${input.key} 和 ${nodeId.output}，节点引用必须指向依赖图的祖先；输入缺失必须在任何节点执行前发现。替换只进行一次，不执行表达式。每个输入/配置字符串最长 20000 字符，最多 50 个输入字段。
单个插值结果最多262144个UTF-16 code unit，在拼接前检查上限；超限作为节点失败记录，避免合法引用组合导致输出指数膨胀。
HTTP 仅 http/https，禁止内嵌凭据、fragment、自动跳转、自动重试；完整正文也受超时约束；响应最大 256 KiB；非 2xx 视为节点失败。动态 URL 在引用替换后校验。请求头不回显，错误响应正文不保存。
先验证完整定义和输入，再创建运行；运行结果和节点状态一并持久化。网络调用不占用数据库事务。运行记录跨服务重启保留，HTTP 返回时记录已写入。

## 验收

文本 DAG 乱序定义可按依赖执行；输入和祖先引用传递；重复/未知依赖/环/非祖先引用拒绝；缺失输入时零 HTTP 请求；本地 HTTP 成功、失败停止、超时包含正文、超大正文受限；缺失 id 为404；无效JSON为400；运行失败仍持久化；重开数据库后定义及记录可读。
提供内置静态演示页、PowerShell与Shell启动器、API示例、中文README、设计与学习文档、Windows/Linux CI及可执行JAR。CI必须真正运行测试。
