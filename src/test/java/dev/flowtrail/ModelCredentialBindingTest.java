package dev.flowtrail;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.api.*;
import dev.flowtrail.execution.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelCredentialBindingTest {
  @Test
  void deploymentEndpointSwitchCannotSendCurrentCredentialsToSnapshotEndpoint() throws Exception {
    try (Provider oldProvider = new Provider();
        Provider currentProvider = new Provider()) {
      ModelCatalog original = catalog(oldProvider.url(), "snapshot-model", "old-placeholder", "v1");
      ModelSnapshot snapshot = original.snapshot(List.of(node())).get("live-default");
      ModelCatalog changed =
          catalog(currentProvider.url(), "snapshot-model", "current-placeholder", "v1");
      LlmNodeExecutor executor = new LlmNodeExecutor(new ReferenceResolver(), changed);

      assertThatThrownBy(() -> executor.execute(node(), context(snapshot)))
          .isInstanceOf(NodeExecutionException.class);
      assertThat(oldProvider.calls).hasValue(0);
      assertThat(currentProvider.calls).hasValue(0);
    }
  }

  @Test
  void modelOrDeploymentVersionChangesCannotReuseCredentialsForOldSnapshot() throws Exception {
    try (Provider provider = new Provider()) {
      ModelSnapshot snapshot =
          catalog(provider.url(), "snapshot-model", "old-placeholder", "v1")
              .snapshot(List.of(node()))
              .get("live-default");
      for (ModelCatalog changed :
          List.of(
              catalog(provider.url(), "different-model", "current-placeholder", "v1"),
              catalog(provider.url(), "snapshot-model", "current-placeholder", "v2"))) {
        LlmNodeExecutor executor = new LlmNodeExecutor(new ReferenceResolver(), changed);
        assertThatThrownBy(() -> executor.execute(node(), context(snapshot)))
            .isInstanceOf(NodeExecutionException.class);
      }
      assertThat(provider.calls).hasValue(0);
    }
  }

  @Test
  void credentialRotationForSameEndpointModelAndVersionUsesCurrentCredential() throws Exception {
    try (Provider provider = new Provider()) {
      ModelSnapshot snapshot =
          catalog(provider.url(), "snapshot-model", "old-placeholder", "v1")
              .snapshot(List.of(node()))
              .get("live-default");
      LlmNodeExecutor executor =
          new LlmNodeExecutor(
              new ReferenceResolver(),
              catalog(provider.url(), "snapshot-model", "rotated-placeholder", "v1"));
      assertThat(executor.execute(node(), context(snapshot))).isEqualTo("verified");
      assertThat(provider.calls).hasValue(1);
      assertThat(provider.authorization.get()).isEqualTo("Bearer rotated-placeholder");
    }
  }

  private static ModelCatalog catalog(String url, String model, String key, String version) {
    return new ModelCatalog(url, model, key, version);
  }

  private static NodeDefinition node() {
    return new NodeDefinition(
        "llm",
        NodeType.LLM,
        List.of(),
        null,
        null,
        null,
        Map.of(),
        null,
        3000,
        "live-default",
        "system",
        "user",
        null);
  }

  private static ExecutionContext context(ModelSnapshot model) {
    return new ExecutionContext("run", "llm", 1, Map.of(), Map.of(), model, chunk -> {}, null);
  }

  private static final class Provider implements AutoCloseable {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final HttpServer server;

    Provider() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body =
                ("data: {\"id\":\"local\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"snapshot-model\",\"choices\":["
                        + "{\"index\":0,\"delta\":{\"content\":\"verified\"}}]}\n\n"
                        + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void close() {
      server.stop(0);
    }
  }
}
