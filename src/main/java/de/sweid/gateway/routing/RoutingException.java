package de.sweid.gateway.routing;

import java.util.List;

/** No route could answer. Carries the HTTP status for the client and what was tried. */
public class RoutingException extends RuntimeException {

  private final int status;
  private final List<String> attempts;

  public RoutingException(int status, String message, List<String> attempts) {
    super(message);
    this.status = status;
    this.attempts = List.copyOf(attempts);
  }

  public int status() {
    return status;
  }

  public List<String> attempts() {
    return attempts;
  }
}
