package dev.flowtrail.execution;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReferenceResolver {
  private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^{}]+)}");
  private static final int MAX_INTERPOLATED_LENGTH = 256 * 1024;

  public String resolve(String value, Map<String, String> inputs, Map<String, String> nodeOutputs) {
    if (value == null) {
      return null;
    }
    Matcher matcher = REFERENCE.matcher(value);
    StringBuilder resolved = new StringBuilder();
    int cursor = 0;
    while (matcher.find()) {
      String expression = matcher.group(1);
      String replacement;
      if (expression.startsWith("input.")) {
        replacement = inputs.get(expression.substring("input.".length()));
      } else {
        replacement =
            nodeOutputs.get(expression.substring(0, expression.length() - ".output".length()));
      }
      ensureFits(resolved.length(), matcher.start() - cursor, replacement.length());
      resolved.append(value, cursor, matcher.start());
      resolved.append(replacement);
      cursor = matcher.end();
    }
    ensureFits(resolved.length(), value.length() - cursor, 0);
    resolved.append(value, cursor, value.length());
    return resolved.toString();
  }

  private void ensureFits(int currentLength, int literalLength, int replacementLength) {
    long resultLength = (long) currentLength + literalLength + replacementLength;
    if (resultLength > MAX_INTERPOLATED_LENGTH) {
      throw new NodeExecutionException("Interpolated value exceeds 262144 characters");
    }
  }
}
