package dev.flowtrail.runtime;

import java.util.Map;

public record FrozenRequest(
    String method,
    String url,
    String body,
    Map<String, String> headers,
    int timeoutMs,
    String lookupUrl,
    boolean supported) {
  public FrozenRequest {
    headers = Map.copyOf(headers);
  }
}
