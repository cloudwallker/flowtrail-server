package dev.flowtrail.runtime;

public record ExternalOperation(
    String key, String state, FrozenRequest request, String response, int checks) {}
