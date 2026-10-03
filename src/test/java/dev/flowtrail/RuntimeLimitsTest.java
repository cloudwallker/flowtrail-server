package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.api.*;
import dev.flowtrail.runtime.RunCoordinator;
import dev.flowtrail.service.FlowTrailService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:limits;DB_CLOSE_DELAY=-1",
      "flowtrail.runtime.workers=2",
      "flowtrail.runtime.per-run=1",
      "flowtrail.runtime.queue-capacity=2"
    })
class RuntimeLimitsTest {
  @Autowired FlowTrailService service;
  @Autowired RunCoordinator coordinator;

  @Test
  void instancePerRunAndQueueLimitsHoldUnderSaturationWithoutLosingNodes() throws Exception {
    CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger(),
        maximum = new AtomicInteger(),
        calls = new AtomicInteger(),
        sameRunOverlap = new AtomicInteger();
    Map<String, AtomicInteger> perRun = new ConcurrentHashMap<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/bounded",
        exchange -> {
          String tag = exchange.getRequestHeaders().getFirst("X-Run");
          int count = active.incrementAndGet();
          maximum.accumulateAndGet(count, Math::max);
          if (perRun.computeIfAbsent(tag, k -> new AtomicInteger()).incrementAndGet() > 1)
            sameRunOverlap.incrementAndGet();
          calls.incrementAndGet();
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            active.decrementAndGet();
            perRun.get(tag).decrementAndGet();
          }
          byte[] body = tag.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/bounded";
      var node1 =
          new NodeDefinition(
              "one",
              NodeType.HTTP,
              List.of(),
              null,
              url,
              "GET",
              Map.of("X-Run", "${input.tag}"),
              null,
              6000);
      var node2 =
          new NodeDefinition(
              "two",
              NodeType.HTTP,
              List.of(),
              null,
              url,
              "GET",
              Map.of("X-Run", "${input.tag}"),
              null,
              6000);
      Workflow workflow =
          service.createWorkflow(new WorkflowRequest("capacity", List.of(node1, node2)));
      List<Run> runs = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        runs.add(
            service.executeWorkflow(
                workflow.id(), new RunRequest(Map.of("tag", "run-" + i)), null));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(200);
      assertThat(coordinator.activeWorkers()).isEqualTo(2);
      assertThat(coordinator.queuedWorkers()).isEqualTo(2);
      release.countDown();
      for (int i = 0; i < runs.size(); i++) {
        Run completed = await(runs.get(i).id());
        assertThat(completed.status()).isEqualTo(RunStatus.SUCCEEDED);
        String tag = "run-" + i;
        assertThat(completed.nodes()).allSatisfy(node -> assertThat(node.output()).isEqualTo(tag));
      }
      assertThat(calls).hasValue(12);
      assertThat(maximum.get()).isLessThanOrEqualTo(2);
      assertThat(sameRunOverlap).hasValue(0);
    } finally {
      release.countDown();
      server.stop(0);
    }
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
