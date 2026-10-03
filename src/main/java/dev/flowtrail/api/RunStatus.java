package dev.flowtrail.api;

public enum RunStatus {
  QUEUED,
  RUNNING,
  MANUAL_REVIEW,
  SUCCEEDED,
  FAILED;

  public boolean terminal() {
    return this != QUEUED && this != RUNNING;
  }
}
