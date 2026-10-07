package de.sweid.gateway.auth;

import java.math.BigDecimal;

/** The authenticated client of the current request. */
public record Caller(String name, BigDecimal budgetUsd) {

  public static final String ATTRIBUTE = "gateway.caller";
}
