package dev.flowtrail.execution;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import dev.flowtrail.error.ApiException;
import dev.flowtrail.validation.WorkflowValidator;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
public class HttpTransport {
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;

  private final WorkflowValidator validator;
  private final ReferenceResolver resolver;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final CloseableHttpClient httpClient =
      HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build();

  public HttpTransport(WorkflowValidator validator, ReferenceResolver resolver) {
    this.validator = validator;
    this.resolver = resolver;
  }

  public String execute(
      NodeDefinition node, Map<String, String> inputs, Map<String, String> nodeOutputs) {
    if (node.type() == NodeType.TEXT) {
      return resolver.resolve(node.text(), inputs, nodeOutputs);
    }
    return executeHttp(node, inputs, nodeOutputs);
  }

  @PreDestroy
  public void close() {
    try {
      httpClient.close();
    } catch (IOException ignored) {
    }
    executor.close();
  }

  public String executeHttp(
      NodeDefinition node, Map<String, String> inputs, Map<String, String> nodeOutputs) {
    String url = resolver.resolve(node.url(), inputs, nodeOutputs);
    try {
      validator.validateResolvedUrl(url);
    } catch (ApiException exception) {
      throw new NodeExecutionException(exception.getMessage());
    }
    String body = resolver.resolve(node.body(), inputs, nodeOutputs);
    Map<String, String> headers = new LinkedHashMap<>();
    node.headers()
        .forEach(
            (name, value) ->
                headers.put(
                    resolver.resolve(name, inputs, nodeOutputs),
                    resolver.resolve(value, inputs, nodeOutputs)));
    HttpResult result = exchange(node.method(), url, body, headers, node.timeoutMs());
    if (result.status() < 200 || result.status() >= 300) {
      throw new HttpFailure(
          "HTTP status " + result.status(), result.status() == 429 || result.status() >= 500);
    }
    return result.body();
  }

  public HttpResult exchange(
      String method, String url, String body, Map<String, String> headers, int timeout) {
    validator.validateResolvedUrl(url);
    NodeDefinition node =
        new NodeDefinition(
            "transport",
            NodeType.HTTP,
            java.util.List.of(),
            null,
            url,
            method,
            headers,
            body,
            timeout);
    HttpUriRequestBase request = buildRequest(node, url, body, headers);
    AtomicReference<InputStream> responseStream = new AtomicReference<>();
    Future<HttpResult> future = executor.submit(() -> sendAndRead(request, responseStream));
    try {
      HttpResult result = future.get(node.timeoutMs(), TimeUnit.MILLISECONDS);
      return result;
    } catch (TimeoutException exception) {
      request.cancel();
      closeQuietly(responseStream.get());
      future.cancel(true);
      throw new HttpFailure("HTTP request timed out", true);
    } catch (InterruptedException exception) {
      request.cancel();
      closeQuietly(responseStream.get());
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new NodeExecutionException("HTTP request was interrupted");
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof NodeExecutionException nodeFailure) {
        throw nodeFailure;
      }
      throw new HttpFailure("HTTP request failed", true);
    }
  }

  private HttpUriRequestBase buildRequest(
      NodeDefinition node, String url, String body, Map<String, String> headers) {
    try {
      URI uri = URI.create(url);
      HttpUriRequestBase request =
          node.method().equals("POST") ? new HttpPost(uri) : new HttpGet(uri);
      RequestConfig requestConfig =
          RequestConfig.custom()
              .setConnectionRequestTimeout(Timeout.ofMilliseconds(node.timeoutMs()))
              .setResponseTimeout(Timeout.ofMilliseconds(node.timeoutMs()))
              .build();
      request.setConfig(requestConfig);
      headers.forEach(request::setHeader);
      if (node.method().equals("POST")) {
        HttpPost post = (HttpPost) request;
        if (body != null) {
          post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        }
      }
      return request;
    } catch (IllegalArgumentException exception) {
      throw new NodeExecutionException("HTTP request configuration is invalid");
    }
  }

  private HttpResult sendAndRead(
      HttpUriRequestBase request, AtomicReference<InputStream> responseStream) {
    try {
      return httpClient.execute(
          request,
          response -> {
            HttpEntity entity = response.getEntity();
            if (entity == null) {
              return new HttpResult(response.getCode(), "");
            }
            InputStream stream = entity.getContent();
            responseStream.set(stream);
            try (stream) {
              try {
                return new HttpResult(response.getCode(), readLimited(stream));
              } catch (IOException | NodeExecutionException exception) {
                request.cancel();
                throw exception;
              }
            } finally {
              responseStream.set(null);
            }
          });
    } catch (IOException exception) {
      throw new HttpFailure("HTTP request failed", true);
    }
  }

  private String readLimited(InputStream stream) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = stream.read(buffer)) != -1) {
      total += read;
      if (total > MAX_RESPONSE_BYTES) {
        throw new NodeExecutionException("HTTP response exceeds 256 KiB");
      }
      output.write(buffer, 0, read);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private void closeQuietly(InputStream stream) {
    if (stream == null) {
      return;
    }
    try {
      stream.close();
    } catch (IOException ignored) {
    }
  }

  public record HttpResult(int status, String body) {}
}
