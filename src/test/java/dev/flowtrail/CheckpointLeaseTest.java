package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.api.*;
import dev.flowtrail.persistence.*;
import dev.flowtrail.runtime.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CheckpointLeaseTest {
  @Test
  void expiredTakeoverRejectsOldWritesAndPreservesCommittedAncestor() throws Exception {
    verifyLease(h2());
  }

  @Test
  void concurrentClaimHasExactlyOneOwner() throws Exception {
    verifyRace(h2());
  }

  @Test
  void externalOperationAlwaysReturnsOriginalFrozenRequestAndKey() {
    DataSource dataSource = h2();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    RuntimeStore store = new RuntimeStore(jdbc, new ObjectMapper().findAndRegisterModules());
    Run run = create(dataSource, store);
    Lease lease = store.claim(run.id(), "owner", 5).orElseThrow();
    FrozenRequest original =
        new FrozenRequest(
            "POST",
            "http://localhost/report",
            "original",
            Map.of("X-Fixed", "first"),
            1000,
            "http://localhost/lookup/{key}",
            true);
    ExternalOperation first = store.prepare(lease, "second", original);
    store.operationState(lease, "second", "UNKNOWN", null, false);
    ExternalOperation retried =
        store.prepare(
            lease,
            "second",
            new FrozenRequest(
                "POST", "http://localhost/changed", "changed", Map.of(), 1000, null, false));
    assertThat(retried.key()).isEqualTo(first.key());
    assertThat(retried.request()).isEqualTo(original);
    assertThat(retried.state()).isEqualTo("UNKNOWN");
  }

  static DataSource h2() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:checkpoint" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    return dataSource;
  }

  static Run create(DataSource dataSource, RuntimeStore store) {
    var nodes =
        List.of(
            new NodeDefinition(
                "first", NodeType.TEXT, List.of(), "saved", null, null, Map.of(), null, null),
            new NodeDefinition(
                "second",
                NodeType.TEXT,
                List.of("first"),
                "next",
                null,
                null,
                Map.of(),
                null,
                null));
    Workflow workflow =
        new Workflow(UUID.randomUUID().toString(), "checkpoint", nodes, Instant.now());
    new FlowTrailRepository(
            new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules())
        .saveWorkflow(workflow);
    return store.create(workflow, nodes, Map.of(), null, Map.of());
  }

  static void verifyLease(DataSource dataSource) throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    RuntimeStore store = new RuntimeStore(jdbc, new ObjectMapper().findAndRegisterModules());
    Run run = create(dataSource, store);
    Lease old = store.claim(run.id(), "old-owner", 5).orElseThrow();
    int first = store.begin(old, "first", "hash");
    store.complete(old, "first", first, NodeStatus.SUCCEEDED, "saved", null, 10, 0);
    int interrupted = store.begin(old, "second", "hash");
    store.append(old, "second", interrupted, "LLM_DELTA", Map.of("text", "old-part"));
    assertThatThrownBy(() -> store.resume(run.id()))
        .isInstanceOf(dev.flowtrail.error.ApiException.class);
    jdbc.update(
        "UPDATE workflow_run SET lease_until=? WHERE id=?",
        Timestamp.from(store.now().minusSeconds(30)),
        run.id());
    assertThatThrownBy(
            () -> store.append(old, "second", interrupted, "LLM_DELTA", Map.of("text", "late")))
        .isInstanceOf(StaleLeaseException.class);
    Lease next = store.claim(run.id(), "new-owner", 5).orElseThrow();
    assertThat(next.epoch()).isEqualTo(old.epoch() + 1);
    int before = store.events(run.id(), 0, 1000).size();
    assertThatThrownBy(
            () ->
                store.complete(
                    old, "second", interrupted, NodeStatus.SUCCEEDED, "stale", null, 10, 0))
        .isInstanceOf(StaleLeaseException.class);
    assertThat(store.events(run.id(), 0, 1000)).hasSize(before);
    assertThat(store.find(run.id()).orElseThrow().nodes().getFirst().output()).isEqualTo("saved");
    assertThat(store.begin(next, "first", "hash")).isZero();
    int retry = store.begin(next, "second", "hash");
    assertThat(retry).isEqualTo(interrupted + 1);
    store.append(next, "second", retry, "LLM_DELTA", Map.of("text", "new-part"));
    store.complete(next, "second", retry, NodeStatus.SUCCEEDED, "new-part", null, 10, 0);
    assertThat(store.settle(next)).isTrue();
    NodeResult result = store.find(run.id()).orElseThrow().nodes().get(1);
    assertThat(result.attempts())
        .extracting(NodeAttempt::status)
        .containsExactly("INTERRUPTED", "SUCCEEDED");
    assertThat(result.output()).isEqualTo("new-part");
  }

  static void verifyRace(DataSource dataSource) throws Exception {
    RuntimeStore first =
        new RuntimeStore(new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules());
    RuntimeStore second =
        new RuntimeStore(new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules());
    Run run = create(dataSource, first);
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          workers.submit(
              () -> {
                start.await();
                return first.claim(run.id(), "a", 5);
              });
      var b =
          workers.submit(
              () -> {
                start.await();
                return second.claim(run.id(), "b", 5);
              });
      start.countDown();
      assertThat(
              List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).stream()
                  .filter(Optional::isPresent)
                  .count())
          .isEqualTo(1);
    }
  }
}

@EnabledIfEnvironmentVariable(named = "FLOWTRAIL_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class MySqlCheckpointTest {
  private DataSource mysql() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            System.getenv("FLOWTRAIL_TEST_MYSQL_URL"),
            System.getenv().getOrDefault("FLOWTRAIL_TEST_MYSQL_USER", "root"),
            System.getenv().getOrDefault("FLOWTRAIL_TEST_MYSQL_PASSWORD", ""));
    org.flywaydb.core.Flyway.configure().dataSource(dataSource).load().migrate();
    return dataSource;
  }

  @Test
  void mysqlRowLockAllowsOnlyOneOwner() throws Exception {
    CheckpointLeaseTest.verifyRace(mysql());
  }

  @Test
  void mysqlEpochFenceAndCheckpointRecovery() throws Exception {
    CheckpointLeaseTest.verifyLease(mysql());
  }
}
