package dev.flowtrail.api;

import java.util.List;
import java.util.Map;

public record NodeDefinition(
    String id,
    NodeType type,
    List<String> dependsOn,
    String text,
    String url,
    String method,
    Map<String, String> headers,
    String body,
    Integer timeoutMs,
    String modelRef,
    String systemPrompt,
    String userPrompt,
    IdempotencyPolicy idempotency) {
  public NodeDefinition(
      String id,
      NodeType type,
      List<String> dependsOn,
      String text,
      String url,
      String method,
      Map<String, String> headers,
      String body,
      Integer timeoutMs) {
    this(id, type, dependsOn, text, url, method, headers, body, timeoutMs, null, null, null, null);
  }
}
