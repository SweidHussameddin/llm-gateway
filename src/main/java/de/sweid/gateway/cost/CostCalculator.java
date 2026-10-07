package de.sweid.gateway.cost;

import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Tokens times the route's price per million tokens. Six decimals cover fractions of a cent. */
public final class CostCalculator {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  private CostCalculator() {}

  public static BigDecimal cost(Route route, Usage usage) {
    if (usage == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal in =
        route.inputUsdPerMtok().multiply(BigDecimal.valueOf(usage.promptTokens())).divide(MILLION);
    BigDecimal out =
        route
            .outputUsdPerMtok()
            .multiply(BigDecimal.valueOf(usage.completionTokens()))
            .divide(MILLION);
    return in.add(out).setScale(6, RoundingMode.HALF_UP);
  }

  /** Attaches route id and cost to a usage block. */
  public static Usage price(Route route, Usage usage) {
    return usage == null ? null : usage.withRouteAndCost(route.id(), cost(route, usage));
  }
}
