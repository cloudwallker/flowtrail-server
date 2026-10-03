# FlowTrail Server 0.2 本地验证

日期：2026-10-03（Asia/Shanghai）。环境：Windows 11 amd64、OpenJDK 21.0.1、Maven 3.9.9、MySQL 8.4.0；Node.js 24.15.0 用于网页状态回归。源码基于 `0d736bf` 的本地工作树；源码摘要与 JAR 哈希见 `verification-artifacts.json`。

| 检查 | 实际结果 |
| --- | --- |
| `mvn spotless:apply clean verify` | BUILD SUCCESS，48 tests，0 failures / errors / skipped |
| 真实 MySQL | Flyway 建表、行锁领取、owner/epoch 栅栏与节点结果检查通过 |
| 调度 | 屏障验证分支重叠和汇合一次；全局/每运行上界；失败后已排队工作不调用外部服务 |
| 模型协议 | Spring AI 对本地兼容端的流式片段、HTTP 错误与配置/凭据绑定检查通过 |
| 外部写恢复组合 | 兄弟失败后接管：UNKNOWN 转人工，CONFIRMED 复用，DECLINED 失败，未发送 PREPARED 跳过 |
| 发行 ZIP | 解压启动、中文输入、异步 API、网页静态资源与重启后历史读取通过 |
| 网页回归 | 7 项 Node 测试通过：去重、attempt 隔离、游标缺口、旧终态穿透与尾部追平 |
| 报告服务协议 | 5 项实际 HTTP 测试通过：同键重放/异参冲突/响应丢失/缺失键/截断正文零写入 |
| 许可 | 76 个运行依赖 JAR 清单与原始许可资源核对通过 |

## 真实进程与副作用场景

最终 JAR 在隔离本机 MySQL 数据库上的 `scripts/recovery_smoke.py --mysql` 通过五组场景，其中第 2、3 组执行真实强杀并重启 Java 进程：

1. 202 创建、同键请求复用和异参冲突、Last-Event-ID 重放；8 个事件顺序补齐。
2. 已提交 HTTP 调用次数为 1；中断节点 attempt=2；汇合执行 1 次。
3. 模型旧 attempt=INTERRUPTED，新 attempt=SUCCEEDED，片段按两次尝试区分。
4. 下游成功但响应丢失，经原键查询运行成功；writes=1、postCalls=1。
5. 无可靠查询协议为 MANUAL_REVIEW，显式 resume 后仍不盲目发送；writes=1、postCalls=1。

该报告运行于 2026-10-03 00:24:55（Asia/Shanghai），原始结果保存为 `target/recovery-report-mysql.json`。更早的 H2 进程恢复也运行过同五组场景。

## 网页点击验收

在浏览器实际执行离线文本流程和文档摘要模板，观察到 SUCCEEDED、节点结果、独立模型尝试和已追平事件尾部；点击重连后输出没有重复追加。截图见 [执行结果](images/monitor-result.jpg) 和 [完整页面](images/monitor-demo.jpg)。

复验入口：

```sh
mvn clean verify
python scripts/smoke.py
python scripts/recovery_smoke.py --mysql
python -m unittest discover -s scripts -p test_mock_reports.py
node --test scripts/ui-state.test.cjs scripts/ui-replay.test.cjs
python scripts/collect_licenses.py --check
```

MySQL JUnit 使用 `FLOWTRAIL_TEST_MYSQL_URL / USER / PASSWORD`；进程脚本使用 `FLOWTRAIL_TEST_DB_URL / USER / PASSWORD`，仅接受本机、库名以 `_test` 结尾的测试库。Maven 默认不提供测试 URL 时 MySQL 用例会明确跳过，本次完整验收已提供实际数据库。
