package dev.flowtrail.execution;

import dev.flowtrail.api.NodeType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NodeExecutorRegistry {
  private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

  public NodeExecutorRegistry(List<NodeExecutor> implementations) {
    for (NodeExecutor executor : implementations) {
      if (executors.put(executor.type(), executor) != null)
        throw new IllegalArgumentException("Duplicate node executor");
    }
  }

  public NodeExecutor get(NodeType type) {
    NodeExecutor executor = executors.get(type);
    if (executor == null) throw new NodeExecutionException("Unsupported node type");
    return executor;
  }
}
