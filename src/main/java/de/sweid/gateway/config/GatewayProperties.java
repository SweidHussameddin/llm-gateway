package de.sweid.gateway.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything the gateway knows about keys, providers and routes. Bound from application.yml. */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    String ledgerFile,
    List<ApiKey> apiKeys,
    Map<String, Provider> providers,
    Breaker breaker,
    List<ModelAlias> models) {

  public GatewayProperties {
    ledgerFile = ledgerFile == null ? "data/usage.jsonl" : ledgerFile;
    apiKeys = apiKeys == null ? List.of() : apiKeys;
    providers = providers == null ? Map.of() : providers;
    breaker = breaker == null ? new Breaker(3, 30) : breaker;
    models = models == null ? List.of() : models;
  }

  /** A client credential with a spend ceiling in USD. */
  public record ApiKey(String key, String name, BigDecimal budgetUsd) {}

  /** One upstream. {@code type} is {@code openai-compatible} or {@code anthropic}. */
  public record Provider(String type, String baseUrl, String apiKey, Integer timeoutSeconds) {
    public Provider {
      timeoutSeconds = timeoutSeconds == null ? 120 : timeoutSeconds;
    }

    public boolean configured() {
      return apiKey != null && !apiKey.isBlank();
    }
  }

  /** Circuit breaker settings shared by all routes. */
  public record Breaker(int failureThreshold, int openSeconds) {}

  /** A model name clients ask for, backed by an ordered list of routes. */
  public record ModelAlias(String alias, String description, List<Route> routes) {
    public ModelAlias {
      routes = routes == null ? List.of() : routes;
    }
  }

  /** One provider/model pair with its price. */
  public record Route(
      String provider, String model, BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok) {
    public Route {
      inputUsdPerMtok = inputUsdPerMtok == null ? BigDecimal.ZERO : inputUsdPerMtok;
      outputUsdPerMtok = outputUsdPerMtok == null ? BigDecimal.ZERO : outputUsdPerMtok;
    }

    public String id() {
      return provider + "/" + model;
    }
  }
}
