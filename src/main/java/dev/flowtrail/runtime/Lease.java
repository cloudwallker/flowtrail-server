package dev.flowtrail.runtime;

public record Lease(String runId, String owner, long epoch) {}
