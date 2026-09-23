# FlowTrail Server

**中文简介：** Java 21 工作流服务，可校验并运行 JSON DAG，并在本地网页查看持久化执行历史。

**English overview:** A Java 21 workflow server that validates and runs JSON DAGs and shows persistent execution history in a local web interface.

## 使用 / Usage

需要 JDK 21 和 Maven；默认使用嵌入式 H2。Windows 可运行 `start.bat`。

Requires JDK 21 and Maven; uses embedded H2 by default. On Windows, run `start.bat`.

```text
mvn package -DskipTests
java -jar target/flowtrail-server.jar
```

## 许可 / License

见 [LICENSE](LICENSE)。 / See [LICENSE](LICENSE).
