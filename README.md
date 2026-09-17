# FlowTrail Server

**中文简介：** 基于 Java 21 的工作流学习服务，支持 JSON DAG 校验、按依赖执行、节点间数据传递，以及通过本地网页查看持久化执行历史。默认示例离线运行，使用嵌入式 H2 数据库。

**English:** A Java 21 workflow service for validating and executing JSON DAGs, passing data between nodes, and inspecting persistent execution history through a local web interface. The default demo runs offline with an embedded H2 database.

Windows 可双击根目录 `start.bat`：自动查找 Java 21+。服务就绪后自动打开浏览器；保持终端窗口打开，按 Ctrl+C 停止服务。再次双击会打开已运行的同名服务。缺少 JAR 时会提示先构建；启动失败保留错误信息。`start.bat -Check` 仅检查启动环境。

**让每一步执行，都有迹可循。** 一个独立实现的 Java 21 工作流学习服务：用 JSON 描述 DAG，传递输入和节点结果，通过网页查看执行轨迹及历史。

默认示例完全离线，数据库使用本地文件型 H2。启动一个 JAR 即可打开演示页，不需要数据库服务器、模型密钥或 Node.js。

## 运行

源码构建需要 JDK 21+、Maven 3.8.5+：

```sh
mvn clean verify
java -jar target/flowtrail-server.jar
```

打开 **http://127.0.0.1:18081**。页面已预填离线示例，点击“校验定义”，再点击“保存并运行”。两个节点会按依赖顺序执行，结果自动保存。

也可以在 PowerShell 使用 `bin/start.ps1`，在 Linux/macOS 使用 `sh bin/start.sh`。启动器优先采用 `JAVA_HOME` 指向的 JDK，并固定以项目目录作为工作目录。

构建会生成 `target/flowtrail-server-dist.zip`。解压后执行 `bin/start.ps1` 或 `sh bin/start.sh`，只需 JDK 21，不再需要 Maven。

## 做了什么

| 能力 | 行为 |
| --- | --- |
| 定义校验 | 拒绝重复 ID、未知依赖、自依赖、环和非法引用 |
| 拓扑排序 | 按依赖顺序执行，定义顺序不必与执行顺序一致 |
| 变量传递 | `${input.name}` 读取输入，`${node.output}` 读取祖先节点输出 |
| TEXT 节点 | 替换引用后输出文本 |
| HTTP 节点 | GET/POST、完整响应超时、256 KiB 正文上限 |
| 失败传播 | 当前节点失败后，剩余节点标为 SKIPPED |
| 持久化 | 工作流和完整运行记录保存到 H2，重启后仍可查看 |
| 演示页 | 编辑定义、校验、执行、节点输出及历史回看 |

首版按拓扑顺序**串行执行**，没有并行调度、条件分支、重试、暂停恢复或图形拖拽。HTTP 调用不占用数据库事务；这是小型学习服务，不是企业工作流平台。

## 定义示例

```json
{
  "name": "hello-dag",
  "nodes": [
    {"id":"summary","type":"TEXT","dependsOn":["greeting"],"text":"完成：${greeting.output}"},
    {"id":"greeting","type":"TEXT","dependsOn":[],"text":"你好，${input.name}！"}
  ]
}
```

执行输入：`{"inputs":{"name":"学习者"}}`。即使 summary 写在前面，也必须先执行 greeting。节点只能引用其依赖图中的祖先；缺失输入会在执行任何节点前被发现。

每个工作流 1–30 个节点。HTTP 方法只支持 GET/POST，超时默认 3000ms，范围 100–30000ms；不自动跟随重定向或重试。完整定义与错误协议见 [设计说明](docs/design.md) 和 [API 文档](docs/api.md)。
单个插值结果上限为 262144 个 UTF-16 code unit，超过上限会记录为节点失败，避免引用重复导致内存膨胀。

## 用 API 复现

PowerShell：

```powershell
$base = 'http://127.0.0.1:18081'
$definition = Get-Content examples/hello-workflow.json -Raw -Encoding UTF8
$workflow = Invoke-RestMethod "$base/api/workflows" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($definition))
$body = Get-Content examples/run-inputs.json -Raw -Encoding UTF8
Invoke-RestMethod "$base/api/workflows/$($workflow.id)/runs" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

`examples/api.http` 也可在支持 HTTP Client 的 IDE 中运行。运行接口同步返回执行记录，工作流业务失败时 HTTP 仍为 200，应检查 `status` 字段；无效定义或输入返回 400，找不到记录返回 404。

## 配置与数据

默认文件数据库设置 `WRITE_DELAY=0`，关闭 H2 的提交写入延迟。发行包测试会在请求完成后终止服务并重启，验证已完成运行记录的恢复；这不等于对硬件故障或断电作保证。自定义数据库 URL 时请保留该设置。参见 [H2 WRITE_DELAY](https://h2database.com/html/commands.html#set_write_delay)。

| 环境变量 | 默认 |
| --- | --- |
| SERVER_ADDRESS | 127.0.0.1 |
| SERVER_PORT | 18081 |
| FLOWTRAIL_DB_URL | jdbc:h2:file:./data/flowtrail;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0 |

`.env.example` 只说明配置，不会被自动加载。通过 shell 或 IDE 的环境变量设置生效。相对数据库路径基于进程工作目录，启动器会固定该目录。`data/` 不纳入 Git。

服务没有认证和租户隔离，默认只监听本机。HTTP 节点代表主动网络操作，请只执行可信定义；POST 可能改变目标服务状态。不要直接修改监听地址后部署到公网。

## 验证与学习

```sh
mvn spotless:apply
mvn clean verify
python scripts/smoke.py
```

最后一条是可选的发行包验证，需 Python 3.10+，会解压 ZIP、启动真实服务、运行 API、重启后验证数据，再停止服务；不会调用公网 API。JUnit 不需要 Python。

学习入口：

- [设计取舍](docs/learning-notes.md)：DAG、输入预检、状态模型、网络与事务边界。
- [API](docs/api.md)：请求、响应与错误。
- [发布指南](docs/publishing.md)：上传独立仓库与标签发行。
- [验证记录](docs/verification.md)：已执行检查和未覆盖范围。

初始版本在 AI 编程助手协助下实现。后续展示应基于自己的复现、解释与改进，避免把计划中的功能描述为已实现。适合继续练习的方向：并行执行、条件节点、取消与恢复，以及调用已有 CLI 的外部客户端。

代码采用 [MIT](LICENSE)，第三方依赖保留各自许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
