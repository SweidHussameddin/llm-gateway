package de.sweid.gateway.api;

import de.sweid.gateway.auth.Caller;
import de.sweid.gateway.cost.UsageLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the calling key has spent, and on which routes. */
@RestController
public class UsageController {

  private final UsageLedger ledger;

  public UsageController(UsageLedger ledger) {
    this.ledger = ledger;
  }

  @GetMapping("/v1/usage")
  public Map<String, Object> usage(
      @RequestAttribute(Caller.ATTRIBUTE) Caller caller,
      @RequestParam(defaultValue = "20") int limit) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("api_key", caller.name());
    body.put("budget_usd", caller.budgetUsd());
    body.put("spent_usd", ledger.spent(caller.name()));
    body.put("requests", ledger.requests(caller.name()));
    body.put("by_route", ledger.byRoute(caller.name()));
    body.put("recent", ledger.recent(caller.name(), Math.clamp(limit, 1, 200)));
    return body;
  }
}
