package dev.flowtrail.validation;

import dev.flowtrail.api.NodeDefinition;
import dev.flowtrail.api.NodeType;
import dev.flowtrail.api.WorkflowRequest;
import dev.flowtrail.error.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WorkflowValidator {
  private static final Pattern NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
  private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^{}]+)}");
  private static final Pattern INPUT_REFERENCE = Pattern.compile("input\\.([^{}]+)");
  private static final Pattern NODE_REFERENCE =
      Pattern.compile("([A-Za-z][A-Za-z0-9_]{0,39})\\.output");
  private static final int MAX_STRING_LENGTH = 20_000;

  public ValidatedWorkflow validate(WorkflowRequest request) {
    if (request == null) {
      throw ApiException.validation("Workflow definition is required");
    }
    validateName(request.name());
    if (request.nodes() == null || request.nodes().isEmpty() || request.nodes().size() > 30) {
      throw ApiException.validation("Workflow must contain between 1 and 30 nodes");
    }
    Map<String, NodeDefinition> byId = new LinkedHashMap<>();
    List<NodeDefinition> normalized = new ArrayList<>();
    for (NodeDefinition node : request.nodes()) {
      NodeDefinition validNode = normalizeNode(node);
      if (byId.putIfAbsent(validNode.id(), validNode) != null) {
        throw ApiException.validation("Node ids must be unique");
      }
      normalized.add(validNode);
    }
    validateDependencies(normalized, byId.keySet());
    List<String> order = stableTopologicalOrder(normalized);
    Map<String, Set<String>> ancestors = findAncestors(order, byId);
    Set<String> requiredInputs = validateReferences(normalized, ancestors);
    return new ValidatedWorkflow(
        request.name(), List.copyOf(normalized), List.copyOf(order), Set.copyOf(requiredInputs));
  }

  public Map<String, String> validateInputs(
      Map<String, String> suppliedInputs, Set<String> requiredInputs) {
    Map<String, String> inputs = suppliedInputs == null ? Map.of() : suppliedInputs;
    if (inputs.size() > 50) {
      throw ApiException.validation("A run may contain at most 50 input fields");
    }
    Map<String, String> copy = new LinkedHashMap<>();
    inputs.forEach(
        (key, value) -> {
          validateString(key, "Input key");
          validateString(value, "Input value");
          copy.put(key, value);
        });
    for (String required : requiredInputs) {
      if (!copy.containsKey(required)) {
        throw ApiException.validation("Required input is missing: " + required);
      }
    }
    return Map.copyOf(copy);
  }

  public void validateResolvedUrl(String url) {
    validateString(url, "HTTP url");
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
        throw ApiException.validation("HTTP url must use http or https");
      }
      if (!uri.isAbsolute() || uri.getHost() == null) {
        throw ApiException.validation("HTTP url must be absolute and contain a host");
      }
      if (uri.getRawUserInfo() != null) {
        throw ApiException.validation("HTTP url must not contain credentials");
      }
      if (uri.getRawFragment() != null) {
        throw ApiException.validation("HTTP url must not contain a fragment");
      }
    } catch (URISyntaxException exception) {
      throw ApiException.validation("HTTP url is invalid");
    }
  }

  private void validateName(String name) {
    if (name == null || name.isBlank() || name.length() > 100) {
      throw ApiException.validation("Workflow name must contain between 1 and 100 characters");
    }
  }

  private NodeDefinition normalizeNode(NodeDefinition node) {
    if (node == null || node.id() == null || !NODE_ID.matcher(node.id()).matches()) {
      throw ApiException.validation("Node id is invalid");
    }
    if (node.type() == null) {
      throw ApiException.validation("Node type is required");
    }
    if (node.dependsOn() != null && node.dependsOn().stream().anyMatch(value -> value == null)) {
      throw ApiException.validation("Node dependency must be a string");
    }
    List<String> dependencies =
        node.dependsOn() == null ? List.of() : List.copyOf(node.dependsOn());
    if (node.headers() != null
        && node.headers().entrySet().stream()
            .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
      throw ApiException.validation("HTTP headers must contain string names and values");
    }
    Map<String, String> headers = node.headers() == null ? Map.of() : Map.copyOf(node.headers());
    if (node.type() == NodeType.TEXT) {
      if (node.text() == null) {
        throw ApiException.validation("TEXT node requires text");
      }
      validateString(node.text(), "TEXT text");
      return new NodeDefinition(
          node.id(), NodeType.TEXT, dependencies, node.text(), null, null, Map.of(), null, null);
    }
    if (node.url() == null || node.url().isBlank()) {
      throw ApiException.validation("HTTP node requires url");
    }
    String method = node.method() == null ? "GET" : node.method().toUpperCase(Locale.ROOT);
    if (!(method.equals("GET") || method.equals("POST"))) {
      throw ApiException.validation("HTTP method must be GET or POST");
    }
    if (method.equals("GET") && node.body() != null) {
      throw ApiException.validation("GET node must not contain body");
    }
    int timeout = node.timeoutMs() == null ? 3000 : node.timeoutMs();
    if (timeout < 100 || timeout > 30_000) {
      throw ApiException.validation("HTTP timeoutMs must be between 100 and 30000");
    }
    validateString(node.url(), "HTTP url");
    validateOptionalString(node.body(), "HTTP body");
    headers.forEach(
        (key, value) -> {
          validateString(key, "HTTP header name");
          validateString(value, "HTTP header value");
        });
    if (!REFERENCE.matcher(node.url()).find()) {
      validateResolvedUrl(node.url());
    }
    return new NodeDefinition(
        node.id(),
        NodeType.HTTP,
        dependencies,
        null,
        node.url(),
        method,
        headers,
        node.body(),
        timeout);
  }

  private void validateDependencies(List<NodeDefinition> nodes, Set<String> knownIds) {
    for (NodeDefinition node : nodes) {
      Set<String> seen = new HashSet<>();
      for (String dependency : node.dependsOn()) {
        if (dependency == null || !knownIds.contains(dependency)) {
          throw ApiException.validation("Node dependency does not exist");
        }
        if (dependency.equals(node.id())) {
          throw ApiException.validation("Node must not depend on itself");
        }
        if (!seen.add(dependency)) {
          throw ApiException.validation("Node dependencies must not contain duplicates");
        }
      }
    }
  }

  private List<String> stableTopologicalOrder(List<NodeDefinition> nodes) {
    Map<String, Integer> positions = new HashMap<>();
    Map<String, Integer> degrees = new HashMap<>();
    Map<String, List<String>> children = new HashMap<>();
    for (int index = 0; index < nodes.size(); index++) {
      NodeDefinition node = nodes.get(index);
      positions.put(node.id(), index);
      degrees.put(node.id(), node.dependsOn().size());
      children.put(node.id(), new ArrayList<>());
    }
    for (NodeDefinition node : nodes) {
      for (String dependency : node.dependsOn()) {
        children.get(dependency).add(node.id());
      }
    }
    PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(positions::get));
    degrees.forEach(
        (id, degree) -> {
          if (degree == 0) {
            ready.add(id);
          }
        });
    List<String> order = new ArrayList<>();
    while (!ready.isEmpty()) {
      String id = ready.remove();
      order.add(id);
      for (String child : children.get(id)) {
        int remaining = degrees.compute(child, (ignored, degree) -> degree - 1);
        if (remaining == 0) {
          ready.add(child);
        }
      }
    }
    if (order.size() != nodes.size()) {
      throw ApiException.validation("Workflow dependencies must not contain a cycle");
    }
    return order;
  }

  private Map<String, Set<String>> findAncestors(
      List<String> order, Map<String, NodeDefinition> nodes) {
    Map<String, Set<String>> ancestors = new HashMap<>();
    for (String id : order) {
      Set<String> values = new HashSet<>();
      for (String dependency : nodes.get(id).dependsOn()) {
        values.add(dependency);
        values.addAll(ancestors.get(dependency));
      }
      ancestors.put(id, values);
    }
    return ancestors;
  }

  private Set<String> validateReferences(
      List<NodeDefinition> nodes, Map<String, Set<String>> ancestors) {
    Set<String> inputs = new LinkedHashSet<>();
    for (NodeDefinition node : nodes) {
      for (String value : configurationStrings(node)) {
        Matcher matcher = REFERENCE.matcher(value);
        int cursor = 0;
        while (matcher.find()) {
          if (value.substring(cursor, matcher.start()).contains("${")) {
            throw ApiException.validation("Reference expression is malformed");
          }
          cursor = matcher.end();
          String expression = matcher.group(1);
          Matcher inputMatcher = INPUT_REFERENCE.matcher(expression);
          Matcher nodeMatcher = NODE_REFERENCE.matcher(expression);
          if (inputMatcher.matches()) {
            if (inputMatcher.group(1).isEmpty()) {
              throw ApiException.validation("Input reference key is required");
            }
            inputs.add(inputMatcher.group(1));
          } else if (nodeMatcher.matches()) {
            if (!ancestors.get(node.id()).contains(nodeMatcher.group(1))) {
              throw ApiException.validation("Node output reference must point to an ancestor");
            }
          } else {
            throw ApiException.validation("Reference expression is not supported");
          }
        }
        if (value.substring(cursor).contains("${")) {
          throw ApiException.validation("Reference expression is malformed");
        }
      }
    }
    return inputs;
  }

  private List<String> configurationStrings(NodeDefinition node) {
    List<String> values = new ArrayList<>();
    if (node.text() != null) {
      values.add(node.text());
    }
    if (node.url() != null) {
      values.add(node.url());
    }
    if (node.body() != null) {
      values.add(node.body());
    }
    node.headers()
        .forEach(
            (key, value) -> {
              values.add(key);
              values.add(value);
            });
    return values;
  }

  private void validateOptionalString(String value, String field) {
    if (value != null) {
      validateString(value, field);
    }
  }

  private void validateString(String value, String field) {
    if (value == null || value.length() > MAX_STRING_LENGTH) {
      throw ApiException.validation(field + " must be a string of at most 20000 characters");
    }
  }
}
