package dev.flowtrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.stream.IntStream;
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
      "spring.datasource.url=jdbc:h2:mem:flowtrail-api;DB_CLOSE_DELAY=-1",
      "spring.sql.init.mode=always"
    })
@AutoConfigureMockMvc
class FlowTrailApiTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clearDatabase() {
    jdbcTemplate.update("DELETE FROM runs");
    jdbcTemplate.update("DELETE FROM workflows");
  }

  @Test
  void exposesHealthAndWorkflowCrud() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.service").value("flowtrail-server"));

    String body =
        """
        {"name":"demo","nodes":[
          {"id":"last","type":"TEXT","dependsOn":["first"],"text":"${first.output}!"},
          {"id":"first","type":"TEXT","text":"hello"}
        ]}
        """;
    String response =
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("demo"))
            .andExpect(jsonPath("$.nodes[0].id").value("last"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(response).path("id").asText();

    mvc.perform(get("/api/workflows/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    mvc.perform(get("/api/workflows"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id));
  }

  @Test
  void validatesWithoutPersistingAndRunsTextDag() throws Exception {
    String workflow =
        """
        {"name":"refs","nodes":[
          {"id":"leaf","type":"TEXT","dependsOn":["root"],"text":"${root.output}/end"},
          {"id":"root","type":"TEXT","text":"hello ${input.name}"}
        ]}
        """;
    mvc.perform(
            post("/api/workflows/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(workflow))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.order[0]").value("root"))
        .andExpect(jsonPath("$.order[1]").value("leaf"));
    mvc.perform(get("/api/workflows")).andExpect(jsonPath("$.length()").value(0));

    String created =
        mvc.perform(
                post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();
    String run =
        mvc.perform(
                post("/api/workflows/{id}/runs", workflowId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"inputs\":{\"name\":\"Ada\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.nodes[0].id").value("root"))
            .andExpect(jsonPath("$.nodes[0].output").value("hello Ada"))
            .andExpect(jsonPath("$.nodes[1].output").value("hello Ada/end"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = objectMapper.readTree(run).path("id").asText();

    mvc.perform(get("/api/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(runId));
  }

  @Test
  void rejectsMissingInputsBeforeCreatingRun() throws Exception {
    String workflow =
        """
        {"name":"missing","nodes":[{"id":"text","type":"TEXT","text":"${input.required}"}]}
        """;
    String created =
        mvc.perform(
                post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();

    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/workflows/{id}/runs", workflowId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void returnsSafeJsonErrorsForMalformedUnknownAndInternalFailures() throws Exception {
    mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("{bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_JSON"))
        .andExpect(jsonPath("$.message").isString());
    mvc.perform(get("/api/workflows/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void rejectsScalarCoercionForStringIntegerAndEnumFields() throws Exception {
    String[] invalidDefinitions = {
      "{\"name\":\"bad\",\"nodes\":[{\"id\":\"n\",\"type\":\"TEXT\",\"text\":42}]}",
      "{\"name\":\"bad\",\"nodes\":[{\"id\":\"n\",\"type\":\"HTTP\",\"url\":true}]}",
      "{\"name\":\"bad\",\"nodes\":[{\"id\":\"n\",\"type\":\"HTTP\",\"url\":\"http://localhost\",\"headers\":{\"X\":7}}]}",
      "{\"name\":\"bad\",\"nodes\":[{\"id\":\"n\",\"type\":\"HTTP\",\"url\":\"http://localhost\",\"timeoutMs\":\"500\"}]}",
      "{\"name\":\"bad\",\"nodes\":[{\"id\":\"n\",\"type\":1,\"text\":\"x\"}]}"
    };
    for (String invalid : invalidDefinitions) {
      mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(invalid))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }

    String created =
        mvc.perform(
                post("/api/workflows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"inputs\",\"nodes\":[{\"id\":\"n\",\"type\":\"TEXT\",\"text\":\"${input.value}\"}]}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();
    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputs\":{\"value\":false}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_JSON"));
  }

  @Test
  void returnsOnlyTheLatestFiftyRuns() throws Exception {
    String created =
        mvc.perform(
                post("/api/workflows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"history\",\"nodes\":[{\"id\":\"n\",\"type\":\"TEXT\",\"text\":\"ok\"}]}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();
    for (int index : IntStream.range(0, 51).toArray()) {
      mvc.perform(
              post("/api/workflows/{id}/runs", workflowId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"inputs\":{\"sequence\":\"" + index + "\"}}"))
          .andExpect(status().isOk());
    }

    String history =
        mvc.perform(get("/api/workflows/{id}/runs", workflowId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(50))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode runs = objectMapper.readTree(history);
    assertThat(runs.get(0).path("startedAt").asText())
        .isGreaterThanOrEqualTo(runs.get(49).path("startedAt").asText());
  }

  @Test
  void replacementRunsOnceAndDoesNotInterpretInsertedText() throws Exception {
    String definition =
        """
        {"name":"once","nodes":[
          {"id":"source","type":"TEXT","text":"fixed"},
          {"id":"result","type":"TEXT","dependsOn":["source"],"text":"${input.value}"}
        ]}
        """;
    String created =
        mvc.perform(
                post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(definition))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();

    mvc.perform(
            post("/api/workflows/{id}/runs", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputs\":{\"value\":\"${source.output}\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodes[1].output").value("${source.output}"));
  }

  @Test
  void internalStorageErrorsReturnOnlySafeJson() throws Exception {
    String created =
        mvc.perform(
                post("/api/workflows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"corrupt\",\"nodes\":[{\"id\":\"n\",\"type\":\"TEXT\",\"text\":\"ok\"}]}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();
    jdbcTemplate.update(
        "UPDATE workflows SET nodes_json = ? WHERE id = ?", "{private-database-detail", workflowId);

    String error =
        mvc.perform(get("/api/workflows/{id}", workflowId))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An internal error occurred"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(error).doesNotContain("private-database-detail").doesNotContain("Jackson");
  }

  @Test
  void interpolationLimitFailsNodeSkipsRemainderAndPersistsRun() throws Exception {
    String template = "${input.chunk}".repeat(100);
    String definition =
        """
        {"name":"bounded","nodes":[
          {"id":"expand","type":"TEXT","text":"%s"},
          {"id":"later","type":"TEXT","text":"must not run"}
        ]}
        """
            .formatted(template);
    String created =
        mvc.perform(
                post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(definition))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String workflowId = objectMapper.readTree(created).path("id").asText();
    String request =
        objectMapper.writeValueAsString(Map.of("inputs", Map.of("chunk", "x".repeat(3000))));

    String response =
        mvc.perform(
                post("/api/workflows/{id}/runs", workflowId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.nodes[0].status").value("FAILED"))
            .andExpect(
                jsonPath("$.nodes[0].error").value("Interpolated value exceeds 262144 characters"))
            .andExpect(jsonPath("$.nodes[1].status").value("SKIPPED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = objectMapper.readTree(response).path("id").asText();
    mvc.perform(get("/api/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }
}
