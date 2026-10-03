package dev.flowtrail.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeResult;
import dev.flowtrail.api.Run;
import dev.flowtrail.api.RunStatus;
import dev.flowtrail.api.Workflow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FlowTrailRepository {
  private static final TypeReference<List<NodeDefinition>> NODE_DEFINITIONS =
      new TypeReference<>() {};
  private static final TypeReference<List<NodeResult>> NODE_RESULTS = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public FlowTrailRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public void saveWorkflow(Workflow workflow) {
    jdbcTemplate.update(
        "INSERT INTO workflows (id, name, nodes_json, created_at) VALUES (?, ?, ?, ?)",
        workflow.id(),
        workflow.name(),
        writeJson(workflow.nodes()),
        java.sql.Timestamp.from(workflow.createdAt()));
  }

  public List<Workflow> findAllWorkflows() {
    return jdbcTemplate.query(
        "SELECT id, name, nodes_json, created_at FROM workflows ORDER BY created_at DESC, id DESC",
        this::mapWorkflow);
  }

  public Optional<Workflow> findWorkflow(String id) {
    return jdbcTemplate
        .query(
            "SELECT id, name, nodes_json, created_at FROM workflows WHERE id = ?",
            this::mapWorkflow,
            id)
        .stream()
        .findFirst();
  }

  public void saveRun(Run run) {
    jdbcTemplate.update(
        "INSERT INTO runs (id, workflow_id, status, inputs_json, nodes_json, started_at, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        run.id(),
        run.workflowId(),
        run.status().name(),
        writeJson(run.inputs()),
        writeJson(run.nodes()),
        OffsetDateTime.ofInstant(run.startedAt(), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(run.finishedAt(), ZoneOffset.UTC));
  }

  public Optional<Run> findRun(String id) {
    return jdbcTemplate
        .query(
            "SELECT id, workflow_id, status, inputs_json, nodes_json, started_at, finished_at FROM runs WHERE id = ?",
            this::mapRun,
            id)
        .stream()
        .findFirst();
  }

  public List<Run> findRunsForWorkflow(String workflowId) {
    return jdbcTemplate.query(
        "SELECT id, workflow_id, status, inputs_json, nodes_json, started_at, finished_at FROM runs WHERE workflow_id = ? ORDER BY started_at DESC, id DESC LIMIT 50",
        this::mapRun,
        workflowId);
  }

  private Workflow mapWorkflow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Workflow(
        resultSet.getString("id"),
        resultSet.getString("name"),
        readJson(resultSet.getString("nodes_json"), NODE_DEFINITIONS),
        resultSet.getTimestamp("created_at").toInstant());
  }

  private Run mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Run(
        resultSet.getString("id"),
        resultSet.getString("workflow_id"),
        RunStatus.valueOf(resultSet.getString("status")),
        readJson(resultSet.getString("inputs_json"), STRING_MAP),
        readJson(resultSet.getString("nodes_json"), NODE_RESULTS),
        resultSet.getObject("started_at", OffsetDateTime.class).toInstant(),
        resultSet.getObject("finished_at", OffsetDateTime.class).toInstant());
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize stored data", exception);
    }
  }

  private <T> T readJson(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not deserialize stored data", exception);
    }
  }
}
