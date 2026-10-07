package de.sweid.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  /** A clock the test moves by hand. */
  static final class StepClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }
  }

  @Test
  void opensAfterThresholdAndProbesOnceAfterCooldown() {
    StepClock clock = new StepClock();
    CircuitBreaker b = new CircuitBreaker(3, Duration.ofSeconds(30), clock);

    assertThat(b.allow()).isTrue();
    b.onFailure();
    b.onFailure();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    b.onFailure();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(b.allow()).isFalse();

    clock.advance(Duration.ofSeconds(31));
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    assertThat(b.allow()).as("one probe gets through").isTrue();
    assertThat(b.allow()).as("second caller waits for the probe").isFalse();

    b.onFailure();
    assertThat(b.state()).as("failed probe reopens").isEqualTo(CircuitBreaker.State.OPEN);

    clock.advance(Duration.ofSeconds(31));
    assertThat(b.allow()).isTrue();
    b.onSuccess();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(b.failures()).isZero();
    assertThat(b.allow()).isTrue();
  }

  @Test
  void successResetsConsecutiveFailures() {
    CircuitBreaker b = new CircuitBreaker(3, Duration.ofSeconds(30), Clock.systemUTC());
    b.onFailure();
    b.onFailure();
    b.onSuccess();
    b.onFailure();
    b.onFailure();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
  }
}
