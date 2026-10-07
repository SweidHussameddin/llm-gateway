package de.sweid.gateway.cost;

import static org.assertj.core.api.Assertions.assertThat;

import de.sweid.gateway.TestSupport;
import de.sweid.gateway.model.Usage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerAndCostTest {

  @Test
  void costIsTokensTimesPricePerMillion() {
    BigDecimal cost =
        CostCalculator.cost(TestSupport.route("p", "m", "3", "15"), Usage.of(2_000, 500));
    assertThat(cost).isEqualByComparingTo("0.0135");

    Usage priced = CostCalculator.price(TestSupport.route("p", "m", "0", "0"), Usage.of(10, 10));
    assertThat(priced.route()).isEqualTo("p/m");
    assertThat(priced.costUsd()).isEqualByComparingTo("0");
  }

  @Test
  void ledgerAppendsAndReloadsFromFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("usage.jsonl");
    UsageLedger ledger = new UsageLedger(file, TestSupport.mapper());

    ledger.record(record("demo", "p/a", "0.010000"));
    ledger.record(record("demo", "p/b", "0.005000"));
    ledger.record(record("trial", "p/a", "0.001000"));

    assertThat(ledger.spent("demo")).isEqualByComparingTo("0.015");
    assertThat(ledger.requests("demo")).isEqualTo(2);
    assertThat(ledger.recent("demo", 10)).hasSize(2);
    assertThat(ledger.byRoute("demo")).containsEntry("p/a", new BigDecimal("0.010000"));
    assertThat(Files.readAllLines(file)).hasSize(3);

    UsageLedger reloaded = new UsageLedger(file, TestSupport.mapper());
    assertThat(reloaded.spent("demo")).isEqualByComparingTo("0.015");
    assertThat(reloaded.spent("trial")).isEqualByComparingTo("0.001");
    assertThat(reloaded.recent(null, 10)).hasSize(3);
  }

  private static UsageRecord record(String key, String route, String cost) {
    return new UsageRecord(
        Instant.parse("2026-10-07T10:00:00Z"), key, "chat", "fast", route, 10, 5,
        new BigDecimal(cost), 120, false);
  }
}
