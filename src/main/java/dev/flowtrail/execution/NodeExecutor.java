package dev.flowtrail.execution;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;

public interface NodeExecutor {
  NodeType type();

  String execute(NodeDefinition node, ExecutionContext context);
}
