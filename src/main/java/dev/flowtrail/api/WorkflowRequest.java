package dev.flowtrail.api;

import java.util.List;

public record WorkflowRequest(String name, List<NodeDefinition> nodes) {}
