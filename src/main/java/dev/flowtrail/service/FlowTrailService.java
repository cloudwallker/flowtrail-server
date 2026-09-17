package dev.flowtrail.service;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeResult;
import dev.flowtrail.api.NodeStatus;
import dev.flowtrail.api.Run;
import dev.flowtrail.api.RunRequest;
import dev.flowtrail.api.RunStatus;
import dev.flowtrail.api.ValidationResponse;
import dev.flowtrail.api.Workflow;
import dev.flowtrail.api.WorkflowRequest;
import dev.flowtrail.error.ApiException;
import dev.flowtrail.execution.NodeExecutionException;
import dev.flowtrail.execution.NodeExecutor;
import dev.flowtrail.persistence.FlowTrailRepository;
import dev.flowtrail.validation.ValidatedWorkflow;
import dev.flowtrail.validation.WorkflowValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FlowTrailService {
  private final WorkflowValidator validator;
  private final FlowTrailRepository repository;
  private final NodeExecutor nodeExecutor;

  public FlowTrailService(
      WorkflowValidator validator, FlowTrailRepository repository, NodeExecutor nodeExecutor) {
    this.validator = validator;
    this.repository = repository;
    this.nodeExecutor = nodeExecutor;
  }

  public Workflow createWorkflow(WorkflowRequest request) {
    ValidatedWorkflow validated = validator.validate(request);
    Workflow workflow =
        new Workflow(
            UUID.randomUUID().toString(), validated.name(), validated.nodes(), Instant.now());
    repository.saveWorkflow(workflow);
    return workflow;
  }

  public ValidationResponse validateWorkflow(WorkflowRequest request) {
    ValidatedWorkflow validated = validator.validate(request);
    return new ValidationResponse(true, validated.order());
  }

  public List<Workflow> listWorkflows() {
    return repository.findAllWorkflows();
  }

  public Workflow getWorkflow(String id) {
    return repository.findWorkflow(id).orElseThrow(() -> ApiException.notFound("Workflow"));
  }

  public Run executeWorkflow(String workflowId, RunRequest request) {
    Workflow workflow = getWorkflow(workflowId);
    ValidatedWorkflow validated =
        validator.validate(new WorkflowRequest(workflow.name(), workflow.nodes()));
    Map<String, String> inputs =
        validator.validateInputs(
            request == null ? null : request.inputs(), validated.requiredInputs());
    Map<String, NodeDefinition> nodesById = new LinkedHashMap<>();
    validated.nodes().forEach(node -> nodesById.put(node.id(), node));
    Map<String, String> outputs = new LinkedHashMap<>();
    List<NodeResult> results = new ArrayList<>();
    boolean failed = false;
    Instant startedAt = Instant.now();
    for (String nodeId : validated.order()) {
      if (failed) {
        results.add(new NodeResult(nodeId, NodeStatus.SKIPPED, null, null, 0));
        continue;
      }
      long startNanos = System.nanoTime();
      try {
        String output = nodeExecutor.execute(nodesById.get(nodeId), inputs, outputs);
        outputs.put(nodeId, output);
        results.add(
            new NodeResult(nodeId, NodeStatus.SUCCEEDED, output, null, elapsedMillis(startNanos)));
      } catch (NodeExecutionException exception) {
        failed = true;
        results.add(
            new NodeResult(
                nodeId,
                NodeStatus.FAILED,
                null,
                exception.getMessage(),
                elapsedMillis(startNanos)));
      } catch (RuntimeException exception) {
        failed = true;
        results.add(
            new NodeResult(
                nodeId,
                NodeStatus.FAILED,
                null,
                "Node execution failed",
                elapsedMillis(startNanos)));
      }
    }
    Run run =
        new Run(
            UUID.randomUUID().toString(),
            workflowId,
            failed ? RunStatus.FAILED : RunStatus.SUCCEEDED,
            inputs,
            List.copyOf(results),
            startedAt,
            Instant.now());
    repository.saveRun(run);
    return run;
  }

  public Run getRun(String id) {
    return repository.findRun(id).orElseThrow(() -> ApiException.notFound("Run"));
  }

  public List<Run> listRuns(String workflowId) {
    getWorkflow(workflowId);
    return repository.findRunsForWorkflow(workflowId);
  }

  private long elapsedMillis(long startNanos) {
    return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
  }
}
