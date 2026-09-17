package dev.flowtrail.api;

import java.util.List;

public record ValidationResponse(boolean valid, List<String> order) {}
