package de.sweid.gateway.cost;

import de.sweid.gateway.config.GatewayProperties;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only cost ledger. Every request lands as one JSON line in a file and in memory, so spend
 * per key survives a restart and the usage endpoint can answer without a database. Swap the file
 * for a table when you need queries across many instances.
 */
@Component
public class UsageLedger {

  private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);
  private static final int KEEP_RECENT = 200;

  private final Path file;
  private final ObjectMapper json;
  private final Map<String, BigDecimal> spentByKey = new ConcurrentHashMap<>();
  private final Map<String, Long> requestsByKey = new ConcurrentHashMap<>();
  private final Deque<UsageRecord> recent = new ArrayDeque<>();
  private final Object writeLock = new Object();

  @Autowired
  public UsageLedger(GatewayProperties properties, ObjectMapper json) {
    this(Path.of(properties.ledgerFile()), json);
  }

  UsageLedger(Path file, ObjectMapper json) {
    this.file = file;
    this.json = json;
    load();
  }

  private void load() {
    if (!Files.exists(file)) {
      return;
    }
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          remember(json.readValue(line, UsageRecord.class));
        }
      }
      log.info("ledger loaded from {}: {} keys", file, spentByKey.size());
    } catch (IOException | RuntimeException e) {
      log.warn("could not read ledger {}: {}", file, e.getMessage());
    }
  }

  public void record(UsageRecord record) {
    remember(record);
    synchronized (writeLock) {
      try {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (BufferedWriter w =
            Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
          w.write(json.writeValueAsString(record));
          w.newLine();
        }
      } catch (IOException e) {
        log.warn("could not append to ledger {}: {}", file, e.getMessage());
      }
    }
  }

  private void remember(UsageRecord record) {
    spentByKey.merge(record.apiKey(), record.costUsd(), BigDecimal::add);
    requestsByKey.merge(record.apiKey(), 1L, Long::sum);
    synchronized (recent) {
      recent.addFirst(record);
      while (recent.size() > KEEP_RECENT) {
        recent.removeLast();
      }
    }
  }

  public BigDecimal spent(String apiKeyName) {
    return spentByKey.getOrDefault(apiKeyName, BigDecimal.ZERO);
  }

  public long requests(String apiKeyName) {
    return requestsByKey.getOrDefault(apiKeyName, 0L);
  }

  public List<UsageRecord> recent(String apiKeyName, int limit) {
    List<UsageRecord> out = new ArrayList<>();
    synchronized (recent) {
      for (UsageRecord r : recent) {
        if (apiKeyName == null || apiKeyName.equals(r.apiKey())) {
          out.add(r);
          if (out.size() >= limit) {
            break;
          }
        }
      }
    }
    return out;
  }

  /** Spend per route for one key, most expensive first. */
  public Map<String, BigDecimal> byRoute(String apiKeyName) {
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    synchronized (recent) {
      for (UsageRecord r : recent) {
        if (apiKeyName.equals(r.apiKey())) {
          totals.merge(r.route(), r.costUsd(), BigDecimal::add);
        }
      }
    }
    return totals;
  }
}
