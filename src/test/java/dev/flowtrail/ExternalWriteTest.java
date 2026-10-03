package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.api.*;
import dev.flowtrail.persistence.RuntimeStore;
import dev.flowtrail.service.FlowTrailService;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:external;DB_CLOSE_DELAY=-1")
class ExternalWriteTest {
  @Autowired FlowTrailService service;
  @Autowired RuntimeStore store;

  @Test
  void lostResponseIsConfirmedByOriginalKeyWithoutAnotherWrite() throws Exception {
    AtomicInteger writes = new AtomicInteger();
    AtomicInteger lookups = new AtomicInteger();
    Map<String, String> reports = new ConcurrentHashMap<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/reports",
        exchange -> {
          String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
          reports.putIfAbsent(
              key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          writes.incrementAndGet();
          try {
            Thread.sleep(3000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.createContext(
        "/lookup/",
        exchange -> {
          lookups.incrementAndGet();
          String key =
              URLDecoder.decode(
                  exchange.getRequestURI().getRawPath().substring("/lookup/".length()),
                  StandardCharsets.UTF_8);
          byte[] body = reports.getOrDefault(key, "missing").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(reports.containsKey(key) ? 200 : 404, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      Run result =
          await(start(base + "/reports", new IdempotencyPolicy(true, base + "/lookup/{key}")).id());
      assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
      assertThat(writes).hasValue(1);
      assertThat(lookups).hasValue(1);
      assertThat(reports).hasSize(1);
      var operation = store.operation(result.id(), "save").orElseThrow();
      assertThat(operation.state()).isEqualTo("CONFIRMED");
      assertThat(reports).containsKey(operation.key());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void unknownWriteWithoutLookupEntersManualReviewAndResumeNeverReplays() throws Exception {
    AtomicInteger writes = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/write",
        exchange -> {
          writes.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          try {
            Thread.sleep(3000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.start();
    try {
      Run run =
          await(start("http://127.0.0.1:" + server.getAddress().getPort() + "/write", null).id());
      assertThat(run.status()).isEqualTo(RunStatus.MANUAL_REVIEW);
      service.resume(run.id());
      assertThat(await(run.id()).status()).isEqualTo(RunStatus.MANUAL_REVIEW);
      assertThat(writes).hasValue(1);
      assertThat(store.operation(run.id(), "save").orElseThrow().state()).isEqualTo("UNKNOWN");
    } finally {
      server.stop(0);
    }
  }

  Run start(String url, IdempotencyPolicy policy) {
    NodeDefinition node =
        new NodeDefinition(
            "save",
            NodeType.HTTP,
            List.of(),
            null,
            url,
            "POST",
            Map.of(),
            "report",
            2000,
            null,
            null,
            null,
            policy);
    Workflow workflow = service.createWorkflow(new WorkflowRequest("write", List.of(node)));
    return service.executeWorkflow(workflow.id(), new RunRequest(Map.of()), null);
  }

  Run await(String id) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      Run run = service.getRun(id);
      if (run.status().terminal()) return run;
      Thread.sleep(20);
    }
    throw new AssertionError("Run timed out");
  }
}
