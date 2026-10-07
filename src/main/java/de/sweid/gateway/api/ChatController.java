package de.sweid.gateway.api;

import de.sweid.gateway.auth.BudgetGuard;
import de.sweid.gateway.auth.Caller;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.cost.CostCalculator;
import de.sweid.gateway.cost.UsageLedger;
import de.sweid.gateway.cost.UsageRecord;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Usage;
import de.sweid.gateway.routing.Router;
import de.sweid.gateway.routing.RoutingException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** The one endpoint clients need: OpenAI chat completions, sync or streamed. */
@RestController
public class ChatController {

  public static final String ROUTE_HEADER = "X-Gateway-Route";
  public static final String COST_HEADER = "X-Gateway-Cost-Usd";

  private final Router router;
  private final BudgetGuard budget;
  private final UsageLedger ledger;
  private final ObjectMapper json;

  public ChatController(Router router, BudgetGuard budget, UsageLedger ledger, ObjectMapper json) {
    this.router = router;
    this.budget = budget;
    this.ledger = ledger;
    this.json = json;
  }

  @PostMapping(value = "/v1/chat/completions", produces = {"application/json", "text/event-stream"})
  public ResponseEntity<ChatResponse> chat(
      @Valid @RequestBody ChatRequest request,
      @RequestAttribute(Caller.ATTRIBUTE) Caller caller,
      HttpServletResponse servletResponse) {
    budget.check(caller);
    long started = System.nanoTime();

    if (request.streaming()) {
      stream(request, caller, servletResponse, started);
      return null;
    }

    Router.Routed<ChatResponse> routed = router.complete(request);
    Usage priced = CostCalculator.price(routed.route(), routed.value().usage());
    ledger.record(record(caller, "chat", request.model(), routed.route(), priced, started));
    return ResponseEntity.ok()
        .header(ROUTE_HEADER, routed.route().id())
        .header(COST_HEADER, priced.costUsd().toPlainString())
        .body(routed.value().withModelAndUsage(routed.value().model(), priced));
  }

  private void stream(
      ChatRequest request, Caller caller, HttpServletResponse servletResponse, long started) {
    SseWriter sse = new SseWriter(servletResponse, json);
    try {
      router.stream(
          request,
          (route, chunk) -> {
            if (!sse.started()) {
              servletResponse.setHeader(ROUTE_HEADER, route.id());
            }
            ChatChunk out = chunk;
            if (chunk.usage() != null) {
              Usage priced = CostCalculator.price(route, chunk.usage());
              ledger.record(record(caller, "chat", request.model(), route, priced, started));
              out = chunk.withModelAndUsage(chunk.model(), priced);
            }
            sse.data(out);
          });
      sse.done();
    } catch (RoutingException e) {
      if (sse.started()) {
        sse.event("error", ApiExceptionHandler.error("upstream_error", e.getMessage(),
            e.attempts()));
        sse.done();
      } else {
        throw e;
      }
    }
  }

  static UsageRecord record(
      Caller caller, String kind, String alias, Route route, Usage usage, long startedNanos) {
    return new UsageRecord(
        Instant.now(),
        caller.name(),
        kind,
        alias,
        route.id(),
        usage.promptTokens(),
        usage.completionTokens(),
        usage.costUsd(),
        (System.nanoTime() - startedNanos) / 1_000_000,
        Boolean.TRUE.equals(usage.estimated()));
  }
}
