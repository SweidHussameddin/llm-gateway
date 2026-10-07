package de.sweid.gateway.model;

import java.math.BigDecimal;

/**
 * Token usage plus what the gateway adds: the route that served the request and the cost in USD.
 * {@code estimated} is true when the provider sent no usage block and tokens were approximated.
 */
public record Usage(
    long promptTokens,
    long completionTokens,
    long totalTokens,
    String route,
    BigDecimal costUsd,
    Boolean estimated) {

  public static Usage of(long prompt, long completion) {
    return new Usage(prompt, completion, prompt + completion, null, null, null);
  }

  public static Usage estimated(long prompt, long completion) {
    return new Usage(prompt, completion, prompt + completion, null, null, true);
  }

  public Usage withRouteAndCost(String routeId, BigDecimal cost) {
    return new Usage(promptTokens, completionTokens, totalTokens, routeId, cost, estimated);
  }
}
