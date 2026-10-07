package de.sweid.gateway.agent.tools;

import de.sweid.gateway.agent.Tool;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Looks up an order in a small in-memory table. Stands in for a real order service or database. */
@Component
public class OrderLookupTool implements Tool {

  private static final Map<String, Map<String, Object>> ORDERS =
      Map.of(
          "1042",
          Map.of(
              "order_id", "1042",
              "status", "shipped",
              "carrier", "DHL",
              "tracking", "JD014600003456789012",
              "items", List.of(Map.of("sku", "HP-VP40", "name", "Heat pump VP40", "qty", 1)),
              "total", 4890.00,
              "currency", "EUR",
              "eta", "2026-10-09"),
          "1043",
          Map.of(
              "order_id", "1043",
              "status", "processing",
              "items", List.of(Map.of("sku", "FLT-200", "name", "Filter set 200", "qty", 3)),
              "total", 87.50,
              "currency", "EUR"),
          "1044",
          Map.of(
              "order_id", "1044",
              "status", "cancelled",
              "items", List.of(),
              "total", 0,
              "currency", "EUR",
              "note", "cancelled by customer before dispatch"));

  @Override
  public String name() {
    return "lookup_order";
  }

  @Override
  public String description() {
    return "Look up an order by its number. Returns status, items, total, currency and, when "
        + "shipped, carrier, tracking number and expected delivery date.";
  }

  @Override
  public Map<String, Object> parameters() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "order_id", Map.of("type", "string", "description", "The order number, e.g. 1042")),
        "required", List.of("order_id"));
  }

  @Override
  public Object execute(Map<String, Object> arguments) {
    String id = String.valueOf(arguments.get("order_id")).trim().replace("#", "");
    Map<String, Object> order = ORDERS.get(id);
    if (order == null) {
      return Map.of("found", false, "order_id", id);
    }
    return order;
  }
}
