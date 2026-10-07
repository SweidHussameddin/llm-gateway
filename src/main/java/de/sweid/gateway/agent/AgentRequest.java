package de.sweid.gateway.agent;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * One agent run. {@code tools} names the server-side tools the model may use (all when empty).
 * {@code maxSteps} caps the number of model calls so a confused model cannot loop forever.
 */
public record AgentRequest(
    @NotBlank String model,
    @NotBlank String input,
    String instructions,
    List<String> tools,
    Integer maxSteps,
    Boolean stream) {

  public int maxStepsOrDefault() {
    return maxSteps == null ? 8 : Math.clamp(maxSteps, 1, 25);
  }

  public boolean streaming() {
    return Boolean.TRUE.equals(stream);
  }
}
