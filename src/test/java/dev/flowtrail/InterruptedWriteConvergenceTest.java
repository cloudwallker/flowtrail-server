package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.api.*;
import dev.flowtrail.persistence.*;
import dev.flowtrail.runtime.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class InterruptedWriteConvergenceTest {
  @ParameterizedTest
  @CsvSource({
    "UNKNOWN, MANUAL_REVIEW, MANUAL_REVIEW",
    "CONFIRMED, SUCCEEDED, FAILED",
    "DECLINED, FAILED, FAILED",
    "PREPARED, SKIPPED, FAILED"
  })
  void failedSiblingAndLeaseTakeoverPreserveActualExternalOutcome(
      String operationState, NodeStatus expectedNode, RunStatus expectedRun) {
    DataSource dataSource = CheckpointLeaseTest.h2();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RuntimeStore store = new RuntimeStore(jdbc, mapper);
    List<NodeDefinition> nodes =
        List.of(
            new NodeDefinition(
                "failure", NodeType.TEXT, List.of(), "fail", null, null, Map.of(), null, null),
            new NodeDefinition(
                "write",
                NodeType.HTTP,
                List.of(),
                null,
                "http://localhost/report",
                "POST",
                Map.of(),
                "frozen-report",
                1000),
            new NodeDefinition(
                "successor",
                NodeType.TEXT,
                List.of("write"),
                "later",
                null,
                null,
                Map.of(),
                null,
                null));
    Workflow workflow =
        new Workflow(UUID.randomUUID().toString(), "write-convergence", nodes, Instant.now());
    new FlowTrailRepository(jdbc, mapper).saveWorkflow(workflow);
    Run run = store.create(workflow, nodes, Map.of(), null, Map.of());
    Lease old = store.claim(run.id(), "old-owner", 5).orElseThrow();
    int failure = store.begin(old, "failure", "input");
    int interrupted = store.begin(old, "write", "input");
    ExternalOperation prepared =
        store.prepare(
            old,
            "write",
            new FrozenRequest(
                "POST", "http://localhost/report", "frozen-report", Map.of(), 1000, null, false));
    if (!operationState.equals("PREPARED")) {
      store.operationState(old, "write", "UNKNOWN", null, false);
      if (!operationState.equals("UNKNOWN")) {
        store.operationState(
            old,
            "write",
            operationState,
            operationState.equals("CONFIRMED") ? "committed-response" : null,
            false);
      }
    }
    store.complete(old, "failure", failure, NodeStatus.FAILED, null, "sibling failed", 1, 0);
    // Simulate a killed owner after the operation checkpoint but before node completion.
    jdbc.update(
        "UPDATE workflow_run SET lease_until=? WHERE id=?",
        Timestamp.from(store.now().minusSeconds(30)),
        run.id());
    Lease next = store.claim(run.id(), "new-owner", 5).orElseThrow();

    assertThat(store.begin(next, "write", "input")).isZero();
    assertThat(store.settle(next)).isTrue();
    Run result = store.find(run.id()).orElseThrow();
    assertThat(result.status()).isEqualTo(expectedRun);
    NodeResult write = result.nodes().get(1);
    assertThat(write.status()).isEqualTo(expectedNode);
    assertThat(write.attemptId()).isEqualTo(interrupted);
    assertThat(write.attempts()).extracting(NodeAttempt::status).containsExactly("INTERRUPTED");
    assertThat(result.nodes().get(2).status()).isEqualTo(NodeStatus.SKIPPED);
    assertThat(result.nodes().get(2).attemptId()).isZero();
    ExternalOperation operation = store.operation(run.id(), "write").orElseThrow();
    assertThat(operation.state()).isEqualTo(operationState);
    assertThat(operation.key()).isEqualTo(prepared.key());
    assertThat(operation.checks()).isZero();
    if (operationState.equals("CONFIRMED")) {
      assertThat(write.output()).isEqualTo("committed-response");
      assertThat(store.events(run.id(), 0, 100))
          .extracting(e -> e.type())
          .contains("NODE_SUCCEEDED");
    } else if (operationState.equals("UNKNOWN")) {
      assertThat(write.error()).contains("unknown");
      assertThat(store.events(run.id(), 0, 100))
          .extracting(e -> e.type())
          .contains("NODE_MANUAL_REVIEW");
    }
  }
}
