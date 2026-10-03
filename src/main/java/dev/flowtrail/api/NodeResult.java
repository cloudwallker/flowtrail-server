package dev.flowtrail.api;

import java.util.List;

public record NodeResult(
    String id,
    NodeStatus status,
    String output,
    String error,
    long durationMs,
    int attemptId,
    List<NodeAttempt> attempts) {
  public NodeResult(String id, NodeStatus status, String output, String error, long durationMs) {
    this(id, status, output, error, durationMs, 0, List.of());
  }
}
