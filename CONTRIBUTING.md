# 参与开发

使用 JDK 21+ 与 Maven 3.8.5+。先阅读 docs/design.md 与 docs/api.md，修改行为前增加能复现问题的测试。

- `mvn spotless:apply` 整理 Java 格式。
- `mvn clean verify` 运行完整测试、格式校验和打包。
- HTTP 测试使用回环地址和随机端口，不调用真实商业服务。
- 数据库测试使用临时目录或隔离的内存库。
- 维持错误协议和文档，不回显请求体、密钥或数据库异常。
- 不提交 data/、target/、.env 或个人IDE设置。
- README 只记录真实实现，PR 说明问题、改动与验证命令。

