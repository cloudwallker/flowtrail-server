package dev.flowtrail.events;

import java.util.Map;

public record RunEvent(
    String runId,
    String nodeId,
    Integer attemptId,
    long seq,
    String type,
    Map<String, Object> payload) {}
