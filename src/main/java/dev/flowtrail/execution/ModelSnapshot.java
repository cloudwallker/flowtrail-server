package dev.flowtrail.execution;

/** Deliberately excludes API credentials. */
public record ModelSnapshot(
    String ref,
    String mode,
    String baseUrl,
    String model,
    String version,
    double temperature,
    int maxTokens) {}
