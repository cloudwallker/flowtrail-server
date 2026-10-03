package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.api.*;
import dev.flowtrail.service.FlowTrailService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:model-protocol;DB_CLOSE_DELAY=-1")
class ModelProtocolTest {
  static final AtomicInteger calls = new AtomicInteger();
  static final HttpServer provider;

  static {
    try {
      provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      provider.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      provider.createContext(
          "/v1/chat/completions",
          exchange -> {
            calls.incrementAndGet();
            String request =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("fail-protocol")) {
              byte[] error =
                  "{\"error\":{\"message\":\"private provider failure\",\"type\":\"server_error\"}}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(500, error.length);
              exchange.getResponseBody().write(error);
              exchange.close();
              return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            for (String token : List.of("protocol ", "verified")) {
              String data =
                  "data: "
                      + new ObjectMapper()
                          .writeValueAsString(
                              Map.of(
                                  "id",
                                  "local-test",
                                  "object",
                                  "chat.completion.chunk",
                                  "created",
                                  1,
                                  "model",
                                  "test-model",
                                  "choices",
                                  List.of(Map.of("index", 0, "delta", Map.of("content", token)))))
                      + "\n\n";
              exchange.getResponseBody().write(data.getBytes(StandardCharsets.UTF_8));
              exchange.getResponseBody().flush();
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
          });
      provider.start();
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add(
        "flowtrail.model.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    registry.add("flowtrail.model.api-key", () -> "local-protocol-placeholder");
    registry.add("flowtrail.model.name", () -> "protocol-model");
  }

  @Autowired FlowTrailService service;
  @Autowired dev.flowtrail.persistence.RuntimeStore store;

  @Test
  void springAiConsumesLocalOpenAiStreamAndPersistsFinalBatch() throws Exception {
    Run run = start("normal");
    Run result = await(run.id());
    assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(result.nodes().getFirst().output()).isEqualTo("protocol verified");
    var events = store.events(run.id(), 0, 100);
    String text =
        events.stream()
            .filter(e -> e.type().equals("LLM_DELTA"))
            .map(e -> (String) e.payload().get("text"))
            .reduce("", String::concat);
    assertThat(text).isEqualTo("protocol verified");
    assertThat(events)
        .extracting(e -> e.type())
        .containsSubsequence("LLM_DELTA", "NODE_SUCCEEDED", "RUN_SUCCEEDED");
    assertThat(store.json(store.snapshot(run.id()))).doesNotContain("local-protocol-placeholder");
  }

  @Test
  void provider500FailsWithoutMockFallbackOrHiddenClientRetries() throws Exception {
    int before = calls.get();
    Run result = await(start("fail-protocol").id());
    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.nodes().getFirst().output()).isNull();
    assertThat(result.nodes().getFirst().error())
        .contains("(live)")
        .doesNotContain("private provider failure");
    assertThat(calls.get() - before).isEqualTo(1);
  }

  Run start(String input) {
    NodeDefinition node =
        new NodeDefinition(
            "model",
            NodeType.LLM,
            List.of(),
            null,
            null,
            null,
            Map.of(),
            null,
            3000,
            "live-default",
            "system only",
            input,
            null);
    Workflow workflow = service.createWorkflow(new WorkflowRequest("protocol", List.of(node)));
    return service.executeWorkflow(workflow.id(), new RunRequest(Map.of()), null);
  }

  Run await(String id) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Run run = service.getRun(id);
      if (run.status().terminal()) return run;
      Thread.sleep(20);
    }
    throw new AssertionError("Model run timed out");
  }

  @AfterAll
  static void close() {
    provider.stop(0);
  }
}
