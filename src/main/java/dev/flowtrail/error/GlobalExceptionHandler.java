package dev.flowtrail.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiError> handleApiException(ApiException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(new ApiError(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleInvalidJson() {
    return ResponseEntity.badRequest()
        .body(new ApiError("INVALID_JSON", "Request body is not valid JSON"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleInvalidArgument() {
    return ResponseEntity.badRequest()
        .body(new ApiError("VALIDATION_ERROR", "Request parameters are invalid"));
  }

  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<ApiError> handleFrameworkError(ErrorResponseException exception) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "REQUEST_ERROR";
    return ResponseEntity.status(status).body(new ApiError(code, "Request could not be processed"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
    log.error("Unexpected request failure", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "An internal error occurred"));
  }
}
