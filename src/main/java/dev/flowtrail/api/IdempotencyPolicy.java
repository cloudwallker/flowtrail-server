package dev.flowtrail.api;

/**
 * Contract: lookup 200 returns original response, 404 is authoritative absence. Writes must
 * deduplicate the Idempotency-Key header.
 */
public record IdempotencyPolicy(boolean supported, String lookupUrl) {}
