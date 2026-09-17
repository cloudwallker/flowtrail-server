package dev.flowtrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:flowtrail-http;DB_CLOSE_DELAY=-1",
      "spring.sql.init.mode=always"
    })
@AutoConfigureMockMvc
class HttpNodeIntegrationTest {
  private static final AtomicInteger REQUESTS = new AtomicInteger();
  private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
  private static final AtomicReference<String> LAST_HEADER = new AtomicReference<>();
  private static HttpServer server;
  private static int port;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/ok",
        exchange -> {
          LAST_BODY.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          LAST_HEADER.set(exchange.getRequestHeaders().getFirst("X-Value"));
          reply(exchange, 200, "accepted");
        });
    server.createContext("/fail", exchange -> reply(exchange, 503, "private failure body"));
    server.createContext(
        "/redirect",
        exchange -> {
          REQUESTS.incrementAndGet();
          exchange.getResponseHeaders().set("Location", "/ok");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/slow-body", exchange -> streamSlowly(exchange, "start".getBytes(StandardCharsets.UTF_8)));
    server.createContext(
        "/large",
        exchange ->
            streamSlowly(exchange, "x".repeat(256 * 1024 + 1).getBytes(StandardCharsets.UTF_8)));
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @BeforeEach
  void clearDatabase() {
    REQUESTS.set(0);
    LAST_BODY.set(null);
    LAST_HEADER.set(null);
    jdbcTemplate.update("DELETE FROM runs");
    jdbcTemplate.update("DELETE FROM workflows");
  }

  @Test
  void executesHttpPostWithResolvedAncestorOutput() throws Exception {
    String definition =
        """
        {"name":"http","nodes":[
          {"id":"call","type":"HTTP","dependsOn":["source"],"url":"http://127.0.0.1:%d/ok","method":"POST","headers":{"X-Value":"${source.output}"},"body":"${source.output}"},
          {"id":"source","type":"TEXT","text":"payload"}
        ]}
        """
            .formatted(port);
    String workflowId = createWorkflow(definition);

    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.nodes[1].output").value("accepted"));
    assertThat(REQUESTS).hasValue(1);
    assertThat(LAST_BODY).hasValue("payload");
    assertThat(LAST_HEADER).hasValue("payload");
  }

  @Test
  void persistsFailureAndSkipsEveryRemainingNodeWithoutSavingErrorBody() throws Exception {
    String definition =
        """
        {"name":"failure","nodes":[
          {"id":"call","type":"HTTP","url":"http://127.0.0.1:%d/fail"},
          {"id":"later","type":"TEXT","text":"must not run"}
        ]}
        """
            .formatted(port);
    String workflowId = createWorkflow(definition);
    String response =
        mvc.perform(
                post("/api/workflows/{id}/runs", workflowId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.nodes[0].status").value("FAILED"))
            .andExpect(jsonPath("$.nodes[0].error").value("HTTP status 503"))
            .andExpect(jsonPath("$.nodes[1].status").value("SKIPPED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("private failure body");
    String runId = objectMapper.readTree(response).path("id").asText();
    mvc.perform(get("/api/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void appliesTimeoutToTheCompleteResponseBody() throws Exception {
    String workflowId =
        createWorkflow(
            "{\"name\":\"timeout\",\"nodes\":[{\"id\":\"call\",\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:"
                + port
                + "/slow-body\",\"timeoutMs\":100}]}");

    long started = System.nanoTime();
    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.nodes[0].error").value("HTTP request timed out"));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertThat(elapsedMs).isLessThan(800);
  }

  @Test
  void rejectsResponsesLargerThanTwoHundredFiftySixKibibytes() throws Exception {
    String workflowId =
        createWorkflow(
            "{\"name\":\"large\",\"nodes\":[{\"id\":\"call\",\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:"
                + port
                + "/large\",\"timeoutMs\":5000}]}");

    long started = System.nanoTime();
    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.nodes[0].error").value("HTTP response exceeds 256 KiB"));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertThat(elapsedMs).isLessThan(1000);
  }

  @Test
  void missingInputPreflightMakesNoHttpRequest() throws Exception {
    String workflowId =
        createWorkflow(
            "{\"name\":\"preflight\",\"nodes\":[{\"id\":\"call\",\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:"
                + port
                + "/ok\",\"headers\":{\"X-Required\":\"${input.missing}\"}}]}");

    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    assertThat(REQUESTS).hasValue(0);
  }

  @Test
  void doesNotFollowRedirects() throws Exception {
    String workflowId =
        createWorkflow(
            "{\"name\":\"redirect\",\"nodes\":[{\"id\":\"call\",\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:"
                + port
                + "/redirect\"}]}");

    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.nodes[0].error").value("HTTP status 302"));
    assertThat(REQUESTS).hasValue(1);
  }

  @Test
  void validatesDynamicUrlAfterSubstitutionAndPersistsFailure() throws Exception {
    String workflowId =
        createWorkflow(
            "{\"name\":\"dynamic\",\"nodes\":[{\"id\":\"call\",\"type\":\"HTTP\",\"url\":\"${input.url}\"}]}");

    String response =
        mvc.perform(
                post("/api/workflows/{id}/runs", workflowId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"inputs\":{\"url\":\"file:///private\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.nodes[0].error").value("HTTP url must use http or https"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = objectMapper.readTree(response).path("id").asText();
    mvc.perform(get("/api/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
    assertThat(REQUESTS).hasValue(0);
  }

  @Test
  void doesNotRetryGetWhenServerDisconnectsAfterReceivingRequest() throws Exception {
    AtomicInteger connections = new AtomicInteger();
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    try (ServerSocket rawServer = new ServerSocket()) {
      rawServer.bind(new InetSocketAddress("127.0.0.1", 0));
      rawServer.setSoTimeout(800);
      Thread acceptor =
          Thread.startVirtualThread(
              () -> {
                try {
                  while (connections.get() < 2) {
                    try (Socket socket = rawServer.accept()) {
                      connections.incrementAndGet();
                      readRequestHeaders(socket);
                    }
                  }
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable throwable) {
                  serverFailure.set(throwable);
                }
              });
      String workflowId =
          createWorkflow(
              "{\"name\":\"no-retry\",\"nodes\":[{\"id\":\"call\",\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:"
                  + rawServer.getLocalPort()
                  + "/disconnect\",\"timeoutMs\":2000}]}");

      mvc.perform(
              post("/api/workflows/{id}/runs", workflowId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("FAILED"));
      acceptor.join(1500);
    }

    assertThat(serverFailure.get()).isNull();
    assertThat(connections).hasValue(1);
  }

  private String createWorkflow(String definition) throws Exception {
    String created =
        mvc.perform(
                post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(definition))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode workflow = objectMapper.readTree(created);
    return workflow.path("id").asText();
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    REQUESTS.incrementAndGet();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static void streamSlowly(HttpExchange exchange, byte[] prefix) throws IOException {
    REQUESTS.incrementAndGet();
    exchange.sendResponseHeaders(200, 0);
    try {
      exchange.getResponseBody().write(prefix);
      exchange.getResponseBody().flush();
      for (int index = 0; index < 100; index++) {
        Thread.sleep(30);
        exchange.getResponseBody().write('x');
        exchange.getResponseBody().flush();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private static void readRequestHeaders(Socket socket) throws IOException {
    socket.setSoTimeout(500);
    int matched = 0;
    while (matched < 4) {
      int value = socket.getInputStream().read();
      if (value == -1) {
        return;
      }
      int expected =
          switch (matched) {
            case 0, 2 -> '\r';
            case 1, 3 -> '\n';
            default -> -1;
          };
      matched = value == expected ? matched + 1 : value == '\r' ? 1 : 0;
    }
  }
}
