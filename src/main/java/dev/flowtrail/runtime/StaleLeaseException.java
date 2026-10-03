package dev.flowtrail.runtime;

public class StaleLeaseException extends RuntimeException {
  public StaleLeaseException() {
    super("Run lease is no longer valid");
  }
}
