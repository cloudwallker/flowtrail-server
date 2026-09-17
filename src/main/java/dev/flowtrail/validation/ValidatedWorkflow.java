package dev.flowtrail.validation;

import dev.flowtrail.api.NodeDefinition;
import java.util.List;
import java.util.Set;

public record ValidatedWorkflow(
    String name, List<NodeDefinition> nodes, List<String> order, Set<String> requiredInputs) {}
