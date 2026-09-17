package dev.flowtrail.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import dev.flowtrail.api.WorkflowRequest;
import dev.flowtrail.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {
  private final WorkflowValidator validator = new WorkflowValidator();

  @Test
  void producesStableTopologicalOrderForOutOfOrderDefinitions() {
    WorkflowRequest request =
        new WorkflowRequest(
            "stable",
            List.of(
                text("third", List.of("first", "second"), "done"),
                text("second", List.of(), "second"),
                text("first", List.of(), "first")));

    ValidatedWorkflow validated = validator.validate(request);

    assertThat(validated.order()).containsExactly("second", "first", "third");
  }

  @Test
  void rejectsDuplicateUnknownSelfAndCyclicDependencies() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "duplicate",
                        List.of(text("a", List.of(), "a"), text("a", List.of(), "b")))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest("unknown", List.of(text("a", List.of("missing"), "a")))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest("self", List.of(text("a", List.of("a"), "a")))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "cycle",
                        List.of(text("a", List.of("b"), "a"), text("b", List.of("a"), "b")))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsAncestorReferencesAndRejectsNonAncestorReferences() {
    WorkflowRequest accepted =
        new WorkflowRequest(
            "accepted",
            List.of(
                text("root", List.of(), "${input.value}"),
                text("middle", List.of("root"), "${root.output}"),
                text("leaf", List.of("middle"), "${root.output}/${middle.output}")));

    assertThat(validator.validate(accepted).requiredInputs()).containsExactly("value");

    WorkflowRequest rejected =
        new WorkflowRequest(
            "rejected",
            List.of(
                text("root", List.of(), "root"),
                text("sibling", List.of(), "sibling"),
                text("leaf", List.of("root"), "${sibling.output}")));
    assertThatThrownBy(() -> validator.validate(rejected)).isInstanceOf(ApiException.class);
  }

  @Test
  void enforcesNodeAndHttpConstraints() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "bad-get",
                        List.of(
                            new NodeDefinition(
                                "call",
                                NodeType.HTTP,
                                List.of(),
                                null,
                                "http://localhost/test",
                                "GET",
                                Map.of(),
                                "body",
                                3000)))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "credentials",
                        List.of(
                            new NodeDefinition(
                                "call",
                                NodeType.HTTP,
                                List.of(),
                                null,
                                "http://user:secret@localhost/test",
                                "GET",
                                Map.of(),
                                null,
                                3000)))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "fragment",
                        List.of(
                            new NodeDefinition(
                                "call",
                                NodeType.HTTP,
                                List.of(),
                                null,
                                "http://localhost/test#secret",
                                "GET",
                                Map.of(),
                                null,
                                3000)))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void defersFullyDynamicUrlValidationUntilReferencesAreResolved() {
    WorkflowRequest request =
        new WorkflowRequest(
            "dynamic",
            List.of(
                new NodeDefinition(
                    "call",
                    NodeType.HTTP,
                    List.of(),
                    null,
                    "${input.url}",
                    null,
                    Map.of(),
                    null,
                    null)));

    ValidatedWorkflow validated = validator.validate(request);

    assertThat(validated.requiredInputs()).containsExactly("url");
  }

  @Test
  void reportsNullDependencyAndHeaderValuesAsValidationErrors() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "null-dependency",
                        List.of(text("node", java.util.Arrays.asList((String) null), "text")))))
        .isInstanceOf(ApiException.class);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Test", null);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "null-header",
                        List.of(
                            new NodeDefinition(
                                "call",
                                NodeType.HTTP,
                                List.of(),
                                null,
                                "http://localhost/test",
                                "GET",
                                headers,
                                null,
                                3000)))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsMalformedReferenceBeforeAnOtherwiseValidReference() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "empty-reference", List.of(text("node", List.of(), "${} ${input.value}")))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new WorkflowRequest(
                        "nested-reference",
                        List.of(text("node", List.of(), "${broken ${input.value}")))))
        .isInstanceOf(ApiException.class);
  }

  private NodeDefinition text(String id, List<String> dependencies, String value) {
    return new NodeDefinition(
        id, NodeType.TEXT, dependencies, value, null, null, Map.of(), null, null);
  }
}
