package de.sweid.gateway.agent.tools;

import de.sweid.gateway.agent.Tool;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Converts between a few currencies with fixed rates. Replace the table with a rates API. */
@Component
public class ConvertCurrencyTool implements Tool {

  private static final Map<String, BigDecimal> USD_PER_UNIT =
      Map.of(
          "USD", new BigDecimal("1.00"),
          "EUR", new BigDecimal("1.17"),
          "GBP", new BigDecimal("1.34"),
          "CHF", new BigDecimal("1.26"),
          "JPY", new BigDecimal("0.0067"));

  @Override
  public String name() {
    return "convert_currency";
  }

  @Override
  public String description() {
    return "Convert an amount from one currency to another. Supports USD, EUR, GBP, CHF, JPY.";
  }

  @Override
  public Map<String, Object> parameters() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "amount", Map.of("type", "number"),
            "from", Map.of("type", "string", "description", "ISO code, e.g. EUR"),
            "to", Map.of("type", "string", "description", "ISO code, e.g. USD")),
        "required", List.of("amount", "from", "to"));
  }

  @Override
  public Object execute(Map<String, Object> arguments) {
    BigDecimal amount = new BigDecimal(String.valueOf(arguments.get("amount")));
    String from = String.valueOf(arguments.get("from")).toUpperCase();
    String to = String.valueOf(arguments.get("to")).toUpperCase();
    BigDecimal fromRate = USD_PER_UNIT.get(from);
    BigDecimal toRate = USD_PER_UNIT.get(to);
    if (fromRate == null || toRate == null) {
      throw new IllegalArgumentException(
          "unsupported currency, use one of " + USD_PER_UNIT.keySet());
    }
    BigDecimal result = amount.multiply(fromRate).divide(toRate, 2, RoundingMode.HALF_UP);
    return Map.of(
        "amount", amount, "from", from, "to", to, "result", result,
        "rate", fromRate.divide(toRate, 6, RoundingMode.HALF_UP));
  }
}
