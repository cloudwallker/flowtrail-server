package dev.flowtrail.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException validation(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
  }

  public static ApiException notFound(String resource) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " was not found");
  }
}
