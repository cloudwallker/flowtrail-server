package dev.flowtrail.execution;

public class HttpFailure extends NodeExecutionException {
  private final boolean retryable;

  public HttpFailure(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
