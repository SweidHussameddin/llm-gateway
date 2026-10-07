package de.sweid.gateway.provider;

/**
 * A provider call failed. {@code status} is the upstream HTTP status, or 0 for transport errors.
 * {@link #retryable()} tells the router whether another route may still succeed.
 */
public class ProviderException extends RuntimeException {

  private final int status;

  public ProviderException(int status, String message) {
    super(message);
    this.status = status;
  }

  public ProviderException(String message, Throwable cause) {
    super(message, cause);
    this.status = 0;
  }

  public int status() {
    return status;
  }

  /**
   * Transport errors, timeouts, rate limits and server errors are worth a second route. Auth
   * failures and 404 too, since they point at a misconfigured route (wrong key, wrong model name)
   * rather than a bad request. A 400, 413 or 422 is the client's problem and is returned as is.
   */
  public boolean retryable() {
    return status == 0
        || status == 401
        || status == 403
        || status == 404
        || status == 408
        || status == 429
        || status >= 500;
  }

  public boolean clientError() {
    return status >= 400 && status < 500 && !retryable();
  }
}
