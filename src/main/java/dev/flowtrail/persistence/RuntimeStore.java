package dev.flowtrail.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.api.*;
import dev.flowtrail.error.ApiException;
import dev.flowtrail.events.RunEvent;
import dev.flowtrail.execution.ModelSnapshot;
import dev.flowtrail.runtime.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class RuntimeStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;

  public RuntimeStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.tx =
        new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
  }

  public Run create(
      Workflow workflow,
      List<NodeDefinition> ordered,
      Map<String, String> inputs,
      String key,
      Map<String, ModelSnapshot> models) {
    return tx.execute(
        status -> {
          // Serialize creation for one workflow, including concurrent uses of an idempotency key.
          jdbc.queryForObject(
              "SELECT id FROM workflows WHERE id=? FOR UPDATE", String.class, workflow.id());
          String hash = digest(json(new TreeMap<>(inputs)));
          if (key != null) {
            List<Map<String, Object>> existing =
                jdbc.queryForList(
                    "SELECT id, request_hash FROM workflow_run WHERE workflow_id=? AND request_key=?",
                    workflow.id(),
                    key);
            if (!existing.isEmpty()) {
              if (!hash.equals(existing.getFirst().get("REQUEST_HASH"))
                  && !hash.equals(existing.getFirst().get("request_hash")))
                throw conflict("Idempotency key was already used with different inputs");
              return find(String.valueOf(value(existing.getFirst(), "id"))).orElseThrow();
            }
          }
          String id = UUID.randomUUID().toString();
          Instant now = now();
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM workflow_version WHERE workflow_id=? AND version=1",
                  Integer.class,
                  workflow.id())
              == 0)
            jdbc.update(
                "INSERT INTO workflow_version(workflow_id,version,definition_json) VALUES(?,1,?)",
                workflow.id(),
                json(workflow.nodes()));
          jdbc.update(
              "INSERT INTO workflow_run(id,workflow_id,version,status,inputs_json,snapshot_json,models_json,request_key,request_hash,started_at) VALUES(?,?,1,'QUEUED',?,?,?,?,?,?)",
              id,
              workflow.id(),
              json(inputs),
              json(ordered),
              json(models),
              key,
              hash,
              stamp(now));
          for (int i = 0; i < ordered.size(); i++)
            jdbc.update(
                "INSERT INTO node_run(run_id,node_id,position_index,status) VALUES(?,?,?,'PENDING')",
                id,
                ordered.get(i).id(),
                i);
          eventLocked(id, null, null, "RUN_QUEUED", Map.of("version", 1));
          return find(id).orElseThrow();
        });
  }

  public Optional<Run> find(String id) {
    return jdbc.query("SELECT * FROM workflow_run WHERE id=?", (rs, n) -> mapRun(rs), id).stream()
        .findFirst();
  }

  public List<Run> list(String workflowId) {
    return jdbc.query(
        "SELECT * FROM workflow_run WHERE workflow_id=? ORDER BY started_at DESC,id DESC LIMIT 50",
        (rs, n) -> mapRun(rs),
        workflowId);
  }

  public RunSnapshot snapshot(String id) {
    return jdbc.queryForObject(
        "SELECT * FROM workflow_run WHERE id=?",
        (rs, n) ->
            new RunSnapshot(
                mapRun(rs),
                read(rs.getString("snapshot_json"), new TypeReference<List<NodeDefinition>>() {}),
                read(
                    rs.getString("models_json"),
                    new TypeReference<Map<String, ModelSnapshot>>() {})),
        id);
  }

  public List<String> candidates() {
    return jdbc.queryForList(
        "SELECT id FROM workflow_run WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP) ORDER BY started_at LIMIT 64",
        String.class);
  }

  public Optional<Lease> claim(String id, String owner, int seconds) {
    return tx.execute(
        status -> {
          List<Map<String, Object>> rows =
              jdbc.queryForList("SELECT * FROM workflow_run WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) return Optional.empty();
          Map<String, Object> row = rows.getFirst();
          Instant now = now();
          String state = String.valueOf(value(row, "status"));
          Timestamp expiry = (Timestamp) value(row, "lease_until");
          if (!state.equals("QUEUED")
              && !(state.equals("RUNNING") && expiry != null && !expiry.toInstant().isAfter(now)))
            return Optional.empty();
          long epoch = ((Number) value(row, "epoch")).longValue() + 1;
          jdbc.update(
              "UPDATE workflow_run SET owner=?,epoch=?,lease_until=?,status='RUNNING',finished_at=NULL WHERE id=?",
              owner,
              epoch,
              stamp(now.plusSeconds(seconds)),
              id);
          List<Map<String, Object>> interrupted =
              jdbc.queryForList(
                  "SELECT node_id,attempt_id FROM node_run WHERE run_id=? AND status='RUNNING'",
                  id);
          for (Map<String, Object> node : interrupted) {
            String nodeId = String.valueOf(value(node, "node_id"));
            int attempt = ((Number) value(node, "attempt_id")).intValue();
            jdbc.update(
                "UPDATE node_attempt SET status='INTERRUPTED',error='Owner lease expired',finished_at=? WHERE run_id=? AND node_id=? AND attempt_id=? AND status='RUNNING'",
                stamp(now),
                id,
                nodeId,
                attempt);
            jdbc.update(
                "UPDATE node_run SET status='PENDING',ready_at=NULL WHERE run_id=? AND node_id=?",
                id,
                nodeId);
            eventLocked(
                id, nodeId, attempt, "NODE_INTERRUPTED", Map.of("reason", "Lease takeover"));
          }
          eventLocked(id, null, null, "RUN_STARTED", Map.of("epoch", epoch));
          return Optional.of(new Lease(id, owner, epoch));
        });
  }

  public void renew(Lease lease, int seconds) {
    tx.executeWithoutResult(
        status -> {
          requireLease(lease);
          jdbc.update(
              "UPDATE workflow_run SET lease_until=? WHERE id=? AND owner=? AND epoch=?",
              stamp(now().plusSeconds(seconds)),
              lease.runId(),
              lease.owner(),
              lease.epoch());
        });
  }

  public int begin(Lease lease, String nodeId, String inputHash) {
    return tx.execute(
        status -> {
          requireLease(lease);
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM node_run WHERE run_id=? AND status IN ('FAILED','MANUAL_REVIEW')",
                  Integer.class,
                  lease.runId())
              > 0) return 0;
          int changed =
              jdbc.update(
                  "UPDATE node_run SET status='RUNNING',attempt_id=attempt_id+1,error=NULL,ready_at=NULL WHERE run_id=? AND node_id=? AND status='PENDING' AND (ready_at IS NULL OR ready_at<=CURRENT_TIMESTAMP)",
                  lease.runId(),
                  nodeId);
          if (changed == 0) return 0;
          int attempt =
              jdbc.queryForObject(
                  "SELECT attempt_id FROM node_run WHERE run_id=? AND node_id=?",
                  Integer.class,
                  lease.runId(),
                  nodeId);
          jdbc.update(
              "INSERT INTO node_attempt(run_id,node_id,attempt_id,status,input_hash,started_at) VALUES(?,?,?,'RUNNING',?,?)",
              lease.runId(),
              nodeId,
              attempt,
              inputHash,
              stamp(now()));
          eventLocked(lease.runId(), nodeId, attempt, "NODE_STARTED", Map.of());
          return attempt;
        });
  }

  public void complete(
      Lease lease,
      String nodeId,
      int attempt,
      NodeStatus state,
      String output,
      String error,
      long durationMs,
      long retryDelay) {
    tx.executeWithoutResult(
        status -> {
          requireLease(lease);
          String attemptState = state == NodeStatus.PENDING ? "FAILED" : state.name();
          int changed =
              jdbc.update(
                  "UPDATE node_run SET status=?,output=?,error=?,duration_ms=?,ready_at=? WHERE run_id=? AND node_id=? AND status='RUNNING' AND attempt_id=?",
                  state.name(),
                  output,
                  error,
                  durationMs,
                  state == NodeStatus.PENDING ? stamp(now().plusMillis(retryDelay)) : null,
                  lease.runId(),
                  nodeId,
                  attempt);
          if (changed != 1) throw new StaleLeaseException();
          jdbc.update(
              "UPDATE node_attempt SET status=?,output=?,error=?,finished_at=? WHERE run_id=? AND node_id=? AND attempt_id=? AND status='RUNNING'",
              attemptState,
              output,
              error,
              stamp(now()),
              lease.runId(),
              nodeId,
              attempt);
          Map<String, Object> payload = new LinkedHashMap<>();
          if (output != null) payload.put("output", output);
          if (error != null) payload.put("error", error);
          if (state == NodeStatus.PENDING) payload.put("retryDelayMs", retryDelay);
          eventLocked(
              lease.runId(),
              nodeId,
              attempt,
              state == NodeStatus.PENDING ? "NODE_RETRY_SCHEDULED" : "NODE_" + state.name(),
              payload);
        });
  }

  public boolean settle(Lease lease) {
    return tx.execute(
        status -> {
          requireLease(lease);
          List<String> states =
              jdbc.queryForList(
                  "SELECT status FROM node_run WHERE run_id=?", String.class, lease.runId());
          if (states.contains("RUNNING")) return false;
          boolean manual = states.contains("MANUAL_REVIEW");
          boolean failed = states.contains("FAILED");
          if (!manual && !failed && states.contains("PENDING")) return false;
          if (manual || failed) {
            // A recovered attempt may already have sent a write before a sibling failed.
            // Preserve its durable external outcome without starting another attempt or request.
            List<Map<String, Object>> operations =
                jdbc.queryForList(
                    "SELECT n.node_id,o.state,o.response FROM node_run n JOIN external_operation o ON n.run_id=o.run_id AND n.node_id=o.node_id WHERE n.run_id=? AND n.status='PENDING' AND o.state IN ('UNKNOWN','CONFIRMED','DECLINED')",
                    lease.runId());
            for (Map<String, Object> operation : operations) {
              String id = String.valueOf(value(operation, "node_id"));
              String outcome = String.valueOf(value(operation, "state"));
              String result =
                  outcome.equals("CONFIRMED")
                      ? "SUCCEEDED"
                      : outcome.equals("UNKNOWN") ? "MANUAL_REVIEW" : "FAILED";
              String output =
                  outcome.equals("CONFIRMED") ? (String) value(operation, "response") : null;
              String error =
                  outcome.equals("UNKNOWN")
                      ? "External write outcome is unknown after interrupted attempt; manual review required"
                      : outcome.equals("DECLINED") ? "External operation was declined" : null;
              jdbc.update(
                  "UPDATE node_run SET status=?,output=?,error=?,ready_at=NULL WHERE run_id=? AND node_id=? AND status='PENDING'",
                  result,
                  output,
                  error,
                  lease.runId(),
                  id);
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("recoveredExternalOperation", true);
              if (output != null) payload.put("output", output);
              if (error != null) payload.put("error", error);
              eventLocked(lease.runId(), id, null, "NODE_" + result, payload);
              if (result.equals("MANUAL_REVIEW")) manual = true;
              if (result.equals("FAILED")) failed = true;
            }
            List<String> skipped =
                jdbc.queryForList(
                    "SELECT node_id FROM node_run WHERE run_id=? AND status='PENDING'",
                    String.class,
                    lease.runId());
            for (String id : skipped) {
              jdbc.update(
                  "UPDATE node_run SET status='SKIPPED' WHERE run_id=? AND node_id=? AND status='PENDING'",
                  lease.runId(),
                  id);
              eventLocked(lease.runId(), id, null, "NODE_SKIPPED", Map.of());
            }
          }
          String terminal = manual ? "MANUAL_REVIEW" : failed ? "FAILED" : "SUCCEEDED";
          jdbc.update(
              "UPDATE workflow_run SET status=?,finished_at=?,lease_until=NULL WHERE id=?",
              terminal,
              stamp(now()),
              lease.runId());
          eventLocked(lease.runId(), null, null, "RUN_" + terminal, Map.of());
          return true;
        });
  }

  public Run resume(String id) {
    return tx.execute(
        status -> {
          List<Map<String, Object>> rows =
              jdbc.queryForList("SELECT * FROM workflow_run WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) throw ApiException.notFound("Run");
          Map<String, Object> row = rows.getFirst();
          String state = String.valueOf(value(row, "status"));
          if (state.equals("SUCCEEDED") || state.equals("QUEUED")) return find(id).orElseThrow();
          Timestamp until = (Timestamp) value(row, "lease_until");
          if (state.equals("RUNNING") && until != null && until.toInstant().isAfter(now()))
            throw conflict("Run has an active lease");
          // Preserve RUNNING attempts for takeover to mark INTERRUPTED.
          jdbc.update(
              "UPDATE node_run SET status='PENDING',error=NULL,ready_at=NULL WHERE run_id=? AND status IN ('FAILED','MANUAL_REVIEW','SKIPPED')",
              id);
          jdbc.update(
              "UPDATE workflow_run SET status='QUEUED',lease_until=NULL,finished_at=NULL WHERE id=?",
              id);
          eventLocked(id, null, null, "RUN_QUEUED", Map.of("resumed", true));
          return find(id).orElseThrow();
        });
  }

  public void append(
      Lease lease, String nodeId, int attempt, String type, Map<String, Object> payload) {
    tx.executeWithoutResult(
        status -> {
          requireLease(lease);
          Integer count =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM node_run WHERE run_id=? AND node_id=? AND status='RUNNING' AND attempt_id=?",
                  Integer.class,
                  lease.runId(),
                  nodeId,
                  attempt);
          if (count != 1) throw new StaleLeaseException();
          eventLocked(lease.runId(), nodeId, attempt, type, payload);
        });
  }

  public List<RunEvent> events(String id, long after, int limit) {
    return jdbc.query(
        "SELECT * FROM run_event WHERE run_id=? AND seq>? ORDER BY seq LIMIT ?",
        (rs, n) ->
            new RunEvent(
                id,
                rs.getString("node_id"),
                (Integer) rs.getObject("attempt_id"),
                rs.getLong("seq"),
                rs.getString("event_type"),
                read(rs.getString("payload_json"), new TypeReference<Map<String, Object>>() {})),
        id,
        after,
        limit);
  }

  public ExternalOperation prepare(Lease lease, String nodeId, FrozenRequest request) {
    return tx.execute(
        status -> {
          requireLease(lease);
          Optional<ExternalOperation> existing = operation(lease.runId(), nodeId);
          if (existing.isPresent()) return existing.get();
          String key = lease.runId() + ":" + nodeId;
          String frozen = json(request);
          jdbc.update(
              "INSERT INTO external_operation(run_id,node_id,operation_key,state,request_json,request_hash) VALUES(?,?,?,'PREPARED',?,?)",
              lease.runId(),
              nodeId,
              key,
              frozen,
              digest(frozen));
          eventLocked(lease.runId(), nodeId, null, "EXTERNAL_PREPARED", Map.of("key", key));
          return operation(lease.runId(), nodeId).orElseThrow();
        });
  }

  public Optional<ExternalOperation> operation(String runId, String nodeId) {
    return jdbc
        .query(
            "SELECT * FROM external_operation WHERE run_id=? AND node_id=?",
            (rs, n) ->
                new ExternalOperation(
                    rs.getString("operation_key"),
                    rs.getString("state"),
                    read(rs.getString("request_json"), new TypeReference<FrozenRequest>() {}),
                    rs.getString("response"),
                    rs.getInt("checks")),
            runId,
            nodeId)
        .stream()
        .findFirst();
  }

  public void operationState(
      Lease lease, String nodeId, String state, String response, boolean checked) {
    tx.executeWithoutResult(
        status -> {
          requireLease(lease);
          jdbc.update(
              "UPDATE external_operation SET state=?,response=?,checks=checks+? WHERE run_id=? AND node_id=?",
              state,
              response,
              checked ? 1 : 0,
              lease.runId(),
              nodeId);
          eventLocked(lease.runId(), nodeId, null, "EXTERNAL_" + state, Map.of("checked", checked));
        });
  }

  private Run mapRun(ResultSet rs) throws SQLException {
    String id = rs.getString("id");
    List<NodeResult> nodes =
        jdbc.query(
            "SELECT * FROM node_run WHERE run_id=? ORDER BY position_index",
            (nr, n) -> {
              String node = nr.getString("node_id");
              List<NodeAttempt> attempts =
                  jdbc.query(
                      "SELECT * FROM node_attempt WHERE run_id=? AND node_id=? ORDER BY attempt_id",
                      (ar, a) ->
                          new NodeAttempt(
                              ar.getInt("attempt_id"),
                              ar.getString("status"),
                              ar.getString("output"),
                              ar.getString("error"),
                              instant(ar, "started_at"),
                              instant(ar, "finished_at")),
                      id,
                      node);
              return new NodeResult(
                  node,
                  NodeStatus.valueOf(nr.getString("status")),
                  nr.getString("output"),
                  nr.getString("error"),
                  nr.getLong("duration_ms"),
                  nr.getInt("attempt_id"),
                  attempts);
            },
            id);
    return new Run(
        id,
        rs.getString("workflow_id"),
        RunStatus.valueOf(rs.getString("status")),
        read(rs.getString("inputs_json"), new TypeReference<Map<String, String>>() {}),
        nodes,
        instant(rs, "started_at"),
        instant(rs, "finished_at"));
  }

  private void requireLease(Lease lease) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT owner,epoch,lease_until,status FROM workflow_run WHERE id=? FOR UPDATE",
            lease.runId());
    if (rows.isEmpty()) throw new StaleLeaseException();
    Map<String, Object> row = rows.getFirst();
    Timestamp expiry = (Timestamp) value(row, "lease_until");
    if (!lease.owner().equals(value(row, "owner"))
        || lease.epoch() != ((Number) value(row, "epoch")).longValue()
        || !"RUNNING".equals(value(row, "status"))
        || expiry == null
        || !expiry.toInstant().isAfter(now())) throw new StaleLeaseException();
  }

  private void eventLocked(
      String id, String node, Integer attempt, String type, Map<String, Object> payload) {
    long seq =
        jdbc.queryForObject("SELECT event_seq FROM workflow_run WHERE id=?", Long.class, id) + 1;
    jdbc.update("UPDATE workflow_run SET event_seq=? WHERE id=?", seq, id);
    jdbc.update(
        "INSERT INTO run_event(run_id,seq,node_id,attempt_id,event_type,payload_json,created_at) VALUES(?,?,?,?,?,?,?)",
        id,
        seq,
        node,
        attempt,
        type,
        json(payload),
        stamp(now()));
  }

  public Instant now() {
    return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
  }

  private static Timestamp stamp(Instant value) {
    return Timestamp.from(value);
  }

  private static Instant instant(ResultSet rs, String name) throws SQLException {
    Timestamp ts = rs.getTimestamp(name);
    return ts == null ? null : ts.toInstant();
  }

  private static Object value(Map<String, Object> map, String key) {
    return map.containsKey(key) ? map.get(key) : map.get(key.toUpperCase(Locale.ROOT));
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot serialize runtime data");
    }
  }

  private <T> T read(String json, TypeReference<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot read runtime data");
    }
  }

  public static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static ApiException conflict(String text) {
    return new ApiException(HttpStatus.CONFLICT, "CONFLICT", text);
  }
}
