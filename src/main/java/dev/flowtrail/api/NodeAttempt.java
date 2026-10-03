package dev.flowtrail.api;

import java.time.Instant;

public record NodeAttempt(
    int attemptId,
    String status,
    String output,
    String error,
    Instant startedAt,
    Instant finishedAt) {}
