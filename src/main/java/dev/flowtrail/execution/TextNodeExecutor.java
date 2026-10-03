package dev.flowtrail.execution;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import org.springframework.stereotype.Component;

@Component
public class TextNodeExecutor implements NodeExecutor {
  private final ReferenceResolver resolver;

  public TextNodeExecutor(ReferenceResolver resolver) {
    this.resolver = resolver;
  }

  public NodeType type() {
    return NodeType.TEXT;
  }

  public String execute(NodeDefinition node, ExecutionContext context) {
    return resolver.resolve(node.text(), context.inputs(), context.ancestorOutputs());
  }
}
