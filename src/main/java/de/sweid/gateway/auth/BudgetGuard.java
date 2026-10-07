package de.sweid.gateway.auth;

import de.sweid.gateway.api.ApiException;
import de.sweid.gateway.cost.UsageLedger;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** Refuses work for a key whose spend has reached its budget. */
@Component
public class BudgetGuard {

  private final UsageLedger ledger;

  public BudgetGuard(UsageLedger ledger) {
    this.ledger = ledger;
  }

  public void check(Caller caller) {
    if (caller.budgetUsd() == null) {
      return;
    }
    BigDecimal spent = ledger.spent(caller.name());
    if (spent.compareTo(caller.budgetUsd()) >= 0) {
      throw new ApiException(
          402,
          "budget_exceeded",
          "key '" + caller.name() + "' has spent $" + spent.toPlainString()
              + " of its $" + caller.budgetUsd().toPlainString() + " budget");
    }
  }
}
