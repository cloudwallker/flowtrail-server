package dev.flowtrail.execution;

import java.util.Map;
import java.util.function.Consumer;

public record ExecutionContext(
    String runId,
    String nodeId,
    int attemptId,
    Map<String, String> inputs,
    Map<String, String> ancestorOutputs,
    ModelSnapshot model,
    Consumer<String> chunks,
    dev.flowtrail.runtime.Lease lease) {
  public ExecutionContext {
    inputs = Map.copyOf(inputs);
    ancestorOutputs = Map.copyOf(ancestorOutputs);
  }
}
