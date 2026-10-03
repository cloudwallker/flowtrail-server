package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.api.*;
import dev.flowtrail.runtime.RunCoordinator;
import dev.flowtrail.service.FlowTrailService;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:queued-failure;DB_CLOSE_DELAY=-1",
      "flowtrail.runtime.workers=1",
      "flowtrail.runtime.per-run=4",
      "flowtrail.runtime.queue-capacity=4"
    })
class QueuedFailureTest {
  @Autowired FlowTrailService service;
  @Autowired RunCoordinator coordinator;

  @Test
  void failureDoesNotStartAlreadyQueuedExternalCalls() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger later = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/fail",
        exchange -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(400, -1);
          exchange.close();
        });
    server.createContext(
        "/later",
        exchange -> {
          later.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var first =
          new NodeDefinition(
              "first", NodeType.HTTP, List.of(), null, base + "/fail", "GET", Map.of(), null, 6000);
      var next =
          new NodeDefinition(
              "next", NodeType.HTTP, List.of(), null, base + "/later", "GET", Map.of(), null, 6000);
      Workflow workflow =
          service.createWorkflow(new WorkflowRequest("queued-failure", List.of(first, next)));
      Run run = service.executeWorkflow(workflow.id(), new RunRequest(Map.of()), null);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (coordinator.queuedWorkers() < 1 && System.nanoTime() < until) Thread.sleep(10);
      assertThat(coordinator.queuedWorkers()).isEqualTo(1);
      release.countDown();
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
      while (!service.getRun(run.id()).status().terminal() && System.nanoTime() < until)
        Thread.sleep(20);
      Run done = service.getRun(run.id());
      assertThat(done.status()).isEqualTo(RunStatus.FAILED);
      assertThat(later).hasValue(0);
      assertThat(done.nodes().get(1).status()).isEqualTo(NodeStatus.SKIPPED);
      assertThat(done.nodes().get(1).attemptId()).isZero();
    } finally {
      release.countDown();
      server.stop(0);
    }
  }
}
