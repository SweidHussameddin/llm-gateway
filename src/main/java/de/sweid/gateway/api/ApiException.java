package de.sweid.gateway.api;

/** An error the client should see, with its HTTP status and an OpenAI-style error type. */
public class ApiException extends RuntimeException {

  private final int status;
  private final String type;

  public ApiException(int status, String type, String message) {
    super(message);
    this.status = status;
    this.type = type;
  }

  public int status() {
    return status;
  }

  public String type() {
    return type;
  }
}
