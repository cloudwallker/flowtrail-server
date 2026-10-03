package dev.flowtrail.execution;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class LlmNodeExecutor implements NodeExecutor {
  private final ReferenceResolver resolver;
  private final ModelCatalog catalog;

  public LlmNodeExecutor(ReferenceResolver resolver, ModelCatalog catalog) {
    this.resolver = resolver;
    this.catalog = catalog;
  }

  public NodeType type() {
    return NodeType.LLM;
  }

  public String execute(NodeDefinition node, ExecutionContext context) {
    ModelSnapshot model = context.model();
    String user = resolver.resolve(node.userPrompt(), context.inputs(), context.ancestorOutputs());
    String system =
        resolver.resolve(node.systemPrompt(), context.inputs(), context.ancestorOutputs());
    Flux<String> tokens;
    if (model.mode().equals("mock")) {
      String response = "[mock:" + model.model() + "] " + user;
      tokens =
          Flux.range(0, (response.length() + 15) / 16)
              .map(i -> response.substring(i * 16, Math.min(response.length(), i * 16 + 16)))
              .delayElements(Duration.ofMillis(10));
    } else {
      String key = catalog.keyFor(model);
      OpenAiApi api = OpenAiApi.builder().baseUrl(model.baseUrl()).apiKey(key).build();
      OpenAiChatModel chat =
          OpenAiChatModel.builder()
              .openAiApi(api)
              .defaultOptions(
                  OpenAiChatOptions.builder()
                      .model(model.model())
                      .temperature(model.temperature())
                      .maxTokens(model.maxTokens())
                      .build())
              .retryTemplate(
                  RetryTemplate.builder()
                      .maxAttempts(1)
                      .fixedBackoff(1)
                      .retryOn(Exception.class)
                      .build())
              .build();
      var prompt = ChatClient.create(chat).prompt();
      if (system != null && !system.isBlank()) prompt.system(system);
      tokens = prompt.user(user).stream().content();
    }
    StringBuilder output = new StringBuilder();
    AtomicInteger bytes = new AtomicInteger();
    try {
      // Timed buffer flushes even when the provider stalls between tokens. The complete final
      // buffer is persisted before this method returns and before NODE_SUCCEEDED is committed.
      tokens
          .timeout(Duration.ofMillis(node.timeoutMs()))
          .doOnNext(
              token -> {
                if (bytes.addAndGet(token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    > 262144) throw new NodeExecutionException("Model output exceeds 256 KiB");
              })
          .concatMapIterable(
              token ->
                  token.codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toList())
          .bufferTimeout(512, Duration.ofMillis(200))
          .map(parts -> String.join("", parts))
          .filter(text -> !text.isEmpty())
          .doOnNext(
              text -> {
                for (int start = 0; start < text.length(); ) {
                  int end = start;
                  int size = 0;
                  while (end < text.length() && size < 1800) {
                    int cp = text.codePointAt(end);
                    size +=
                        new String(Character.toChars(cp))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            .length;
                    end += Character.charCount(cp);
                  }
                  String chunk = text.substring(start, end);
                  context.chunks().accept(chunk);
                  output.append(chunk);
                  start = end;
                }
              })
          .blockLast(Duration.ofMillis(node.timeoutMs()));
      return output.toString();
    } catch (dev.flowtrail.runtime.StaleLeaseException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new NodeExecutionException("Model stream failed or timed out (" + model.mode() + ")");
    }
  }
}
