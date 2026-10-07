package de.sweid.gateway.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The result of an agent run with every tool call that happened on the way. */
public record AgentResponse(
    String id,
    String output,
    List<Step> steps,
    int modelCalls,
    long promptTokens,
    long completionTokens,
    BigDecimal costUsd,
    long latencyMs,
    List<String> routes,
    String warning) {

  /** One tool invocation. {@code error} is set instead of {@code result} when the tool threw. */
  public record Step(
      int n, String tool, Map<String, Object> arguments, Object result, String error, long ms) {}
}
