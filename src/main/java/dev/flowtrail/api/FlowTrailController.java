package dev.flowtrail.api;

import dev.flowtrail.service.FlowTrailService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FlowTrailController {
  private final FlowTrailService service;

  public FlowTrailController(FlowTrailService service) {
    this.service = service;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "service", "flowtrail-server");
  }

  @PostMapping("/workflows")
  public ResponseEntity<Workflow> createWorkflow(@RequestBody WorkflowRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.createWorkflow(request));
  }

  @GetMapping("/workflows")
  public List<Workflow> listWorkflows() {
    return service.listWorkflows();
  }

  @GetMapping("/workflows/{id}")
  public Workflow getWorkflow(@PathVariable String id) {
    return service.getWorkflow(id);
  }

  @PostMapping("/workflows/validate")
  public ValidationResponse validateWorkflow(@RequestBody WorkflowRequest request) {
    return service.validateWorkflow(request);
  }

  @PostMapping("/workflows/{id}/runs")
  public Run executeWorkflow(
      @PathVariable String id, @RequestBody(required = false) RunRequest request) {
    return service.executeWorkflow(id, request);
  }

  @GetMapping("/runs/{id}")
  public Run getRun(@PathVariable String id) {
    return service.getRun(id);
  }

  @GetMapping("/workflows/{id}/runs")
  public List<Run> listRuns(@PathVariable String id) {
    return service.listRuns(id);
  }
}
