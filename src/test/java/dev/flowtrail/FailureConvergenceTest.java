package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.api.*;
import dev.flowtrail.execution.ExecutionContext;
import dev.flowtrail.service.FlowTrailService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = "spring.datasource.url=jdbc:h2:mem:failure-convergence;DB_CLOSE_DELAY=-1")
class FailureConvergenceTest {
  @Autowired FlowTrailService service;

  @Test
  void alreadyStartedBranchFinishesTruthfullyAndNoSuccessorStartsAfterFailure() throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2), releaseSlow = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        exchange -> {
          bothStarted.countDown();
          try {
            bothStarted.await(3, TimeUnit.SECONDS);
            if (exchange.getRequestURI().getPath().equals("/slow"))
              releaseSlow.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          byte[] body = "finished".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(
              exchange.getRequestURI().getPath().equals("/fail") ? 400 : 200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var failure =
          new NodeDefinition(
              "failure",
              NodeType.HTTP,
              List.of(),
              null,
              base + "/fail",
              "GET",
              Map.of(),
              null,
              6000);
      var slow =
          new NodeDefinition(
              "slow", NodeType.HTTP, List.of(), null, base + "/slow", "GET", Map.of(), null, 6000);
      var child =
          new NodeDefinition(
              "child", NodeType.TEXT, List.of("slow"), "child", null, null, Map.of(), null, null);
      Workflow workflow =
          service.createWorkflow(new WorkflowRequest("converge", List.of(failure, slow, child)));
      Run created = service.executeWorkflow(workflow.id(), new RunRequest(Map.of()), null);
      assertThat(bothStarted.await(3, TimeUnit.SECONDS)).isTrue();
      Run during = null;
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < until) {
        during = service.getRun(created.id());
        if (during.nodes().getFirst().status() == NodeStatus.FAILED) break;
        Thread.sleep(20);
      }
      assertThat(during.status()).isEqualTo(RunStatus.RUNNING);
      assertThat(during.nodes().get(1).status()).isEqualTo(NodeStatus.RUNNING);
      releaseSlow.countDown();
      Run done = await(created.id());
      assertThat(done.status()).isEqualTo(RunStatus.FAILED);
      assertThat(done.nodes())
          .extracting(NodeResult::status)
          .containsExactly(NodeStatus.FAILED, NodeStatus.SUCCEEDED, NodeStatus.SKIPPED);
      assertThat(done.nodes().get(2).attemptId()).isZero();
    } finally {
      releaseSlow.countDown();
      server.stop(0);
    }
  }

  @Test
  void contextCopiesInputMapsAndExposesNoMutableReferences() {
    Map<String, String> inputs = new HashMap<>(Map.of("name", "first")),
        ancestors = new HashMap<>(Map.of("prior", "committed"));
    ExecutionContext context =
        new ExecutionContext("run", "node", 1, inputs, ancestors, null, t -> {}, null);
    inputs.put("name", "changed");
    ancestors.clear();
    assertThat(context.inputs()).containsEntry("name", "first");
    assertThat(context.ancestorOutputs()).containsEntry("prior", "committed");
    assertThatThrownBy(() -> context.inputs().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> context.ancestorOutputs().clear())
        .isInstanceOf(UnsupportedOperationException.class);
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
