package dev.flowtrail.execution;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import dev.flowtrail.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelCatalog {
  private final String baseUrl, model, key, version;

  public ModelCatalog(
      @Value("${flowtrail.model.base-url:https://api.openai.com}") String baseUrl,
      @Value("${flowtrail.model.name:gpt-4o-mini}") String model,
      @Value("${flowtrail.model.api-key:}") String key,
      @Value("${flowtrail.model.version:deployment-v1}") String version) {
    this.baseUrl = baseUrl;
    this.model = model;
    this.key = key;
    this.version = version;
  }

  public Map<String, ModelSnapshot> snapshot(List<NodeDefinition> nodes) {
    Map<String, ModelSnapshot> result = new LinkedHashMap<>();
    for (NodeDefinition node : nodes)
      if (node.type() == NodeType.LLM) {
        if (node.modelRef().equals("live-default") && key.isBlank())
          throw ApiException.validation("live-default requires FLOWTRAIL_MODEL_API_KEY");
        result.put(
            node.modelRef(),
            node.modelRef().equals("mock-demo")
                ? new ModelSnapshot(
                    "mock-demo", "mock", "", "deterministic-mock", "mock-v1", 0, 1024)
                : new ModelSnapshot("live-default", "live", baseUrl, model, version, 0.2, 2048));
      }
    return Map.copyOf(result);
  }

  public String keyFor(ModelSnapshot snapshot) {
    if (!Objects.equals(snapshot.ref(), "live-default")
        || !Objects.equals(snapshot.mode(), "live")
        || !Objects.equals(snapshot.baseUrl(), baseUrl)
        || !Objects.equals(snapshot.model(), model)
        || !Objects.equals(snapshot.version(), version))
      throw new NodeExecutionException("Live model configuration no longer matches run snapshot");
    if (key.isBlank()) throw new NodeExecutionException("Live model credentials are unavailable");
    return key;
  }
}
