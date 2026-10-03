package dev.flowtrail.service;

import dev.flowtrail.api.*;
import dev.flowtrail.error.ApiException;
import dev.flowtrail.execution.ModelCatalog;
import dev.flowtrail.persistence.FlowTrailRepository;
import dev.flowtrail.persistence.RuntimeStore;
import dev.flowtrail.validation.ValidatedWorkflow;
import dev.flowtrail.validation.WorkflowValidator;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FlowTrailService {
  private final WorkflowValidator validator;
  private final FlowTrailRepository repository;
  private final RuntimeStore runtime;
  private final ModelCatalog models;

  public FlowTrailService(
      WorkflowValidator validator,
      FlowTrailRepository repository,
      RuntimeStore runtime,
      ModelCatalog models) {
    this.validator = validator;
    this.repository = repository;
    this.runtime = runtime;
    this.models = models;
  }

  public Workflow createWorkflow(WorkflowRequest request) {
    ValidatedWorkflow valid = validator.validate(request);
    Workflow workflow =
        new Workflow(UUID.randomUUID().toString(), valid.name(), valid.nodes(), Instant.now());
    repository.saveWorkflow(workflow);
    return workflow;
  }

  public ValidationResponse validateWorkflow(WorkflowRequest request) {
    ValidatedWorkflow valid = validator.validate(request);
    return new ValidationResponse(true, valid.order());
  }

  public List<Workflow> listWorkflows() {
    return repository.findAllWorkflows();
  }

  public Workflow getWorkflow(String id) {
    return repository.findWorkflow(id).orElseThrow(() -> ApiException.notFound("Workflow"));
  }

  public Run executeWorkflow(String id, RunRequest request, String key) {
    if (key != null && (key.isBlank() || key.length() > 128))
      throw ApiException.validation("Idempotency-Key must contain 1 to 128 characters");
    Workflow workflow = getWorkflow(id);
    ValidatedWorkflow valid =
        validator.validate(new WorkflowRequest(workflow.name(), workflow.nodes()));
    Map<String, String> inputs =
        validator.validateInputs(request == null ? null : request.inputs(), valid.requiredInputs());
    Map<String, NodeDefinition> nodes = new HashMap<>();
    valid.nodes().forEach(n -> nodes.put(n.id(), n));
    return runtime.create(
        workflow,
        valid.order().stream().map(nodes::get).toList(),
        inputs,
        key,
        models.snapshot(valid.nodes()));
  }

  public Run getRun(String id) {
    return runtime.find(id).orElseThrow(() -> ApiException.notFound("Run"));
  }

  public List<Run> listRuns(String workflowId) {
    getWorkflow(workflowId);
    return runtime.list(workflowId);
  }

  public Run resume(String id) {
    return runtime.resume(id);
  }
}
