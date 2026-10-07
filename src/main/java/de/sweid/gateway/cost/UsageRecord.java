package de.sweid.gateway.cost;

import java.math.BigDecimal;
import java.time.Instant;

/** One line in the ledger: who, what, which route, how many tokens, what it cost. */
public record UsageRecord(
    Instant at,
    String apiKey,
    String kind,
    String alias,
    String route,
    long promptTokens,
    long completionTokens,
    BigDecimal costUsd,
    long latencyMs,
    boolean estimated) {}
