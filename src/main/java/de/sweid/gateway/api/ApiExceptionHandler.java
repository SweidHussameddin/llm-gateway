package de.sweid.gateway.api;

import de.sweid.gateway.routing.RoutingException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error leaves as {@code {"error": {"type", "message"}}}, the shape OpenAI SDKs parse. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(error(e.type(), e.getMessage(), null));
  }

  @ExceptionHandler(RoutingException.class)
  public ResponseEntity<Map<String, Object>> routing(RoutingException e) {
    String type =
        switch (e.status()) {
          case 404 -> "model_not_found";
          case 429 -> "rate_limit_error";
          default -> "upstream_error";
        };
    return ResponseEntity.status(e.status()).body(error(type, e.getMessage(), e.attempts()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
    String message =
        e instanceof MethodArgumentNotValidException m
            ? m.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request")
            : "request body is not valid JSON for this endpoint";
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(error("invalid_request_error", message, null));
  }

  static Map<String, Object> error(String type, String message, Object attempts) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("message", message);
    if (attempts != null) {
      body.put("attempts", attempts);
    }
    return Map.of("error", body);
  }
}
