package dev.flowtrail.runtime;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.Run;
import dev.flowtrail.execution.ModelSnapshot;
import java.util.List;
import java.util.Map;

public record RunSnapshot(
    Run run, List<NodeDefinition> definitions, Map<String, ModelSnapshot> models) {}
