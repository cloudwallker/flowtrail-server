package dev.flowtrail.execution;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import dev.flowtrail.persistence.RuntimeStore;
import dev.flowtrail.runtime.ExternalOperation;
import dev.flowtrail.runtime.FrozenRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpNodeExecutor implements NodeExecutor {
  private final HttpTransport transport;
  private final ReferenceResolver resolver;
  private final RuntimeStore store;

  public HttpNodeExecutor(HttpTransport transport, ReferenceResolver resolver, RuntimeStore store) {
    this.transport = transport;
    this.resolver = resolver;
    this.store = store;
  }

  public NodeType type() {
    return NodeType.HTTP;
  }

  public String execute(NodeDefinition node, ExecutionContext context) {
    if (node.method().equals("GET"))
      return transport.executeHttp(node, context.inputs(), context.ancestorOutputs());
    Map<String, String> headers = new LinkedHashMap<>();
    node.headers().forEach((k, v) -> headers.put(resolve(k, context), resolve(v, context)));
    FrozenRequest request =
        new FrozenRequest(
            "POST",
            resolve(node.url(), context),
            resolve(node.body(), context),
            headers,
            node.timeoutMs(),
            node.idempotency() == null ? null : node.idempotency().lookupUrl(),
            node.idempotency() != null && node.idempotency().supported());
    ExternalOperation operation = store.prepare(context.lease(), node.id(), request);
    if (operation.state().equals("CONFIRMED")) return operation.response();
    if (operation.state().equals("DECLINED"))
      throw new NodeExecutionException("External operation was declined");
    if (operation.state().equals("UNKNOWN")) return reconcile(node, context, operation);
    return send(node, context, operation);
  }

  private String send(NodeDefinition node, ExecutionContext context, ExternalOperation operation) {
    FrozenRequest request = operation.request();
    Map<String, String> headers = new LinkedHashMap<>(request.headers());
    headers.put("Idempotency-Key", operation.key());
    // A crash at any point after this transaction is an unknown outcome, including before send.
    store.operationState(context.lease(), node.id(), "UNKNOWN", null, false);
    HttpTransport.HttpResult response;
    try {
      response =
          transport.exchange(
              request.method(), request.url(), request.body(), headers, request.timeoutMs());
    } catch (NodeExecutionException failure) {
      return reconcile(node, context, store.operation(context.runId(), node.id()).orElseThrow());
    }
    if (response.status() >= 200 && response.status() < 300) {
      store.operationState(context.lease(), node.id(), "CONFIRMED", response.body(), false);
      return response.body();
    }
    if (response.status() >= 400
        && response.status() < 500
        && response.status() != 408
        && response.status() != 429) {
      store.operationState(context.lease(), node.id(), "DECLINED", null, false);
      throw new NodeExecutionException("HTTP status " + response.status());
    }
    return reconcile(node, context, store.operation(context.runId(), node.id()).orElseThrow());
  }

  private String reconcile(
      NodeDefinition node, ExecutionContext context, ExternalOperation operation) {
    FrozenRequest frozen = operation.request();
    if (!frozen.supported() || frozen.lookupUrl() == null)
      throw new ManualReviewException(
          "External write outcome is unknown; reliable lookup is unavailable");
    if (operation.checks() >= 3)
      throw new ManualReviewException("External write lookup budget exhausted");
    store.operationState(context.lease(), node.id(), "UNKNOWN", null, true);
    HttpTransport.HttpResult result;
    try {
      result =
          transport.exchange(
              "GET",
              frozen
                  .lookupUrl()
                  .replace("{key}", URLEncoder.encode(operation.key(), StandardCharsets.UTF_8)),
              null,
              Map.of(),
              frozen.timeoutMs());
    } catch (NodeExecutionException failure) {
      throw new ManualReviewException("External write lookup failed; manual review required");
    }
    if (result.status() == 200) {
      store.operationState(context.lease(), node.id(), "CONFIRMED", result.body(), false);
      return result.body();
    }
    if (result.status() == 404)
      return send(node, context, store.operation(context.runId(), node.id()).orElseThrow());
    throw new ManualReviewException("External write lookup did not prove success or absence");
  }

  private String resolve(String value, ExecutionContext context) {
    return resolver.resolve(value, context.inputs(), context.ancestorOutputs());
  }
}
