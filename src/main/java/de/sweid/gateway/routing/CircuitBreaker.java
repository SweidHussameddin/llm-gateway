package de.sweid.gateway.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-route breaker. Closed: calls pass. After {@code failureThreshold} consecutive failures it
 * opens for {@code openFor}; the first call after that is a probe (half-open) and decides whether
 * the route is back. In-memory on purpose: one process, no shared state to operate.
 */
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final Duration openFor;
  private final Clock clock;

  private State state = State.CLOSED;
  private int failures;
  private Instant openedAt;
  private Instant lastFailure;
  private boolean probing;

  public CircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
    this.failureThreshold = failureThreshold;
    this.openFor = openFor;
    this.clock = clock;
  }

  /** Whether a call may go through now. An open breaker lets exactly one probe through. */
  public synchronized boolean allow() {
    if (state == State.CLOSED) {
      return true;
    }
    if (state == State.OPEN && openElapsed()) {
      state = State.HALF_OPEN;
      probing = false;
    }
    if (state == State.HALF_OPEN && !probing) {
      probing = true;
      return true;
    }
    return false;
  }

  public synchronized void onSuccess() {
    state = State.CLOSED;
    failures = 0;
    probing = false;
  }

  public synchronized void onFailure() {
    failures++;
    lastFailure = clock.instant();
    if (state == State.HALF_OPEN || failures >= failureThreshold) {
      state = State.OPEN;
      openedAt = clock.instant();
      probing = false;
    }
  }

  public synchronized State state() {
    if (state == State.OPEN && openElapsed()) {
      return State.HALF_OPEN;
    }
    return state;
  }

  private boolean openElapsed() {
    return Duration.between(openedAt, clock.instant()).compareTo(openFor) >= 0;
  }

  public synchronized int failures() {
    return failures;
  }

  public synchronized Instant lastFailure() {
    return lastFailure;
  }
}
