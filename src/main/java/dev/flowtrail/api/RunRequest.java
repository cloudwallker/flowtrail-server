package dev.flowtrail.api;

import java.util.Map;

public record RunRequest(Map<String, String> inputs) {}
