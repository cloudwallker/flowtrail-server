package dev.flowtrail.api;

public record NodeResult(
    String id, NodeStatus status, String output, String error, long durationMs) {}
