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
    Integer timeoutMs) {}
