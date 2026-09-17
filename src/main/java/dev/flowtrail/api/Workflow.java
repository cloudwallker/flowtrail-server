package dev.flowtrail.api;

import java.time.Instant;
import java.util.List;

public record Workflow(String id, String name, List<NodeDefinition> nodes, Instant createdAt) {}
