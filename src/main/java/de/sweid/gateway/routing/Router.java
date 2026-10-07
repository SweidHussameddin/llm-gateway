package de.sweid.gateway.routing;

import de.sweid.gateway.config.GatewayProperties;
import de.sweid.gateway.config.GatewayProperties.ModelAlias;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.provider.ProviderException;
import de.sweid.gateway.provider.ProviderRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a model alias to its routes and walks them in order: skip routes whose breaker is open
 * or whose provider has no key, call the next one on a retryable failure, stop on the first
 * answer. For streams, failover only happens before the first chunk reached the client; after
 * that the stream is committed and an error is passed through.
 */
@Component
public class Router {

  private static final Logger log = LoggerFactory.getLogger(Router.class);

  private final Map<String, ModelAlias> aliases = new LinkedHashMap<>();
  private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
  private final ProviderRegistry providers;
  private final GatewayProperties.Breaker breakerConfig;
  private final Clock clock;

  public Router(GatewayProperties properties, ProviderRegistry providers, Clock clock) {
    this.providers = providers;
    this.breakerConfig = properties.breaker();
    this.clock = clock;
    properties.models().forEach(m -> aliases.put(m.alias(), m));
  }

  public List<ModelAlias> aliases() {
    return List.copyOf(aliases.values());
  }

  public Optional<ModelAlias> alias(String name) {
    return Optional.ofNullable(aliases.get(name));
  }

  public CircuitBreaker breaker(Route route) {
    return breakers.computeIfAbsent(
        route.id(),
        id ->
            new CircuitBreaker(
                breakerConfig.failureThreshold(),
                Duration.ofSeconds(breakerConfig.openSeconds()),
                clock));
  }

  /** The route that produced the answer, with the answer. */
  public record Routed<T>(Route route, T value, List<String> attempts) {}

  public Routed<ChatResponse> complete(ChatRequest request) {
    List<Route> candidates = candidates(request.model());
    List<String> attempts = new ArrayList<>();
    ProviderException last = null;
    for (Route route : candidates) {
      CircuitBreaker breaker = breaker(route);
      if (!breaker.allow()) {
        attempts.add(route.id() + ": breaker open");
        continue;
      }
      try {
        ChatResponse response = providers.get(route.provider()).complete(request, route);
        breaker.onSuccess();
        return new Routed<>(route, response, attempts);
      } catch (ProviderException e) {
        last = e;
        attempts.add(route.id() + ": " + describe(e));
        if (e.clientError()) {
          throw new RoutingException(e.status(), e.getMessage(), attempts);
        }
        breaker.onFailure();
        log.warn("route {} failed ({}), trying next", route.id(), describe(e));
      }
    }
    throw exhausted(request.model(), attempts, last);
  }

  public Routed<Void> stream(ChatRequest request, BiConsumer<Route, ChatChunk> onChunk) {
    List<Route> candidates = candidates(request.model());
    List<String> attempts = new ArrayList<>();
    ProviderException last = null;
    for (Route route : candidates) {
      CircuitBreaker breaker = breaker(route);
      if (!breaker.allow()) {
        attempts.add(route.id() + ": breaker open");
        continue;
      }
      boolean[] committed = {false};
      try {
        providers
            .get(route.provider())
            .stream(
                request,
                route,
                chunk -> {
                  committed[0] = true;
                  onChunk.accept(route, chunk);
                });
        breaker.onSuccess();
        return new Routed<>(route, null, attempts);
      } catch (ProviderException e) {
        last = e;
        attempts.add(route.id() + ": " + describe(e));
        if (e.clientError()) {
          throw new RoutingException(e.status(), e.getMessage(), attempts);
        }
        breaker.onFailure();
        if (committed[0]) {
          throw new RoutingException(502, "stream broke after it started: " + e.getMessage(),
              attempts);
        }
        log.warn("route {} failed ({}), trying next", route.id(), describe(e));
      }
    }
    throw exhausted(request.model(), attempts, last);
  }

  private List<Route> candidates(String model) {
    ModelAlias alias = aliases.get(model);
    if (alias == null) {
      throw new RoutingException(404, "unknown model '" + model + "'", List.of());
    }
    List<Route> usable = new ArrayList<>();
    for (Route r : alias.routes()) {
      if (providers.usable(r.provider())) {
        usable.add(r);
      }
    }
    if (usable.isEmpty()) {
      throw new RoutingException(
          503, "no route for '" + model + "' has a configured provider", List.of());
    }
    return usable;
  }

  private static RoutingException exhausted(
      String model, List<String> attempts, ProviderException last) {
    int status = last != null && last.status() == 429 ? 429 : 503;
    return new RoutingException(
        status, "all routes for '" + model + "' failed", attempts);
  }

  private static String describe(ProviderException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
    if (msg.length() > 160) {
      msg = msg.substring(0, 160) + "...";
    }
    return (e.status() == 0 ? "transport" : "HTTP " + e.status()) + " " + msg;
  }
}
