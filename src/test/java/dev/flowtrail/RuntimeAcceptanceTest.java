package dev.flowtrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:runtime;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RuntimeAcceptanceTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @Test
  void persistsBeforeReturning202AndReusesSameKeyButRejectsDifferentInputs() throws Exception {
    String workflow =
        create(
            "{\"name\":\"async\",\"nodes\":[{\"id\":\"n\",\"type\":\"TEXT\",\"text\":\"${input.x}\"}]}");
    String id = submit(workflow, "{\"inputs\":{\"x\":\"a\"}}", "request-1");
    assertThat(submit(workflow, "{\"inputs\":{\"x\":\"a\"}}", "request-1")).isEqualTo(id);
    mvc.perform(
            post("/api/workflows/{id}/runs", workflow)
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputs\":{\"x\":\"b\"}}"))
        .andExpect(status().isConflict());
    assertThat(await(id).path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void diamondBranchesOverlapAtBarrierAndJoinExecutesOnceAfterBothCommitted() throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    AtomicInteger branches = new AtomicInteger();
    AtomicInteger joins = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/branch",
        exchange -> {
          branches.incrementAndGet();
          entered.countDown();
          boolean overlap;
          try {
            overlap = entered.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
          }
          byte[] body = (overlap ? "overlapped" : "serialized").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(overlap ? 200 : 409, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/join",
        exchange -> {
          joins.incrementAndGet();
          byte[] body = "joined".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      String workflow =
          create(
              """
          {"name":"diamond","nodes":[
          {"id":"root","type":"TEXT","text":"root"},
          {"id":"left","type":"HTTP","dependsOn":["root"],"url":"http://127.0.0.1:%d/branch"},
          {"id":"right","type":"HTTP","dependsOn":["root"],"url":"http://127.0.0.1:%d/branch"},
          {"id":"join","type":"HTTP","dependsOn":["left","right"],"url":"http://127.0.0.1:%d/join","headers":{"X-Results":"${left.output}/${right.output}"}}]}
          """
                  .formatted(port, port, port));
      JsonNode run = await(submit(workflow, "{}", null));
      assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(branches).hasValue(2);
      assertThat(joins).hasValue(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mockLlmPersistsAttemptSeparatedChunksAndReplayCursor() throws Exception {
    String workflow =
        create(
            """
        {"name":"mock","nodes":[{"id":"answer","type":"LLM","modelRef":"mock-demo","systemPrompt":"Summarize","userPrompt":"${input.text}"}]}
        """);
    String id = submit(workflow, "{\"inputs\":{\"text\":\"hello\"}}", null);
    assertThat(await(id).path("status").asText()).isEqualTo("SUCCEEDED");
    JsonNode events =
        mapper.readTree(
            mvc.perform(get("/api/runs/{id}/events/history", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(events.size()).isGreaterThan(4);
    long previous = 0;
    boolean chunk = false;
    for (JsonNode event : events) {
      assertThat(event.path("seq").asLong()).isGreaterThan(previous);
      previous = event.path("seq").asLong();
      if (event.path("type").asText().equals("LLM_DELTA")) {
        chunk = true;
        assertThat(event.path("attemptId").asInt()).isEqualTo(1);
      }
    }
    assertThat(chunk).isTrue();
    JsonNode tail =
        mapper.readTree(
            mvc.perform(get("/api/runs/{id}/events/history?after={after}", id, previous - 1))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(tail.size()).isEqualTo(1);
    var connection =
        mvc.perform(
                get("/api/runs/{id}/events", id)
                    .header("Last-Event-ID", Long.toString(previous - 1)))
            .andReturn();
    connection.getAsyncResult(3000);
    String replay =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(
                    connection))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(replay)
        .contains("event:workflow", "id:" + previous, "RUN_SUCCEEDED")
        .doesNotContain("LLM_DELTA");
  }

  String create(String definition) throws Exception {
    return mapper
        .readTree(
            mvc.perform(
                    post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(definition))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("id")
        .asText();
  }

  String submit(String workflow, String input, String key) throws Exception {
    var request =
        post("/api/workflows/{id}/runs", workflow)
            .contentType(MediaType.APPLICATION_JSON)
            .content(input);
    if (key != null) request.header("Idempotency-Key", key);
    return mapper
        .readTree(
            mvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("id")
        .asText();
  }

  JsonNode await(String id) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      JsonNode run =
          mapper.readTree(
              mvc.perform(get("/api/runs/{id}", id))
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      if (!run.path("status").asText().equals("QUEUED")
          && !run.path("status").asText().equals("RUNNING")) return run;
      Thread.sleep(25);
    }
    throw new AssertionError("run did not reach terminal state");
  }
}
