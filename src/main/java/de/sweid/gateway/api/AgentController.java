package de.sweid.gateway.api;

import de.sweid.gateway.agent.AgentRequest;
import de.sweid.gateway.agent.AgentResponse;
import de.sweid.gateway.agent.AgentRunner;
import de.sweid.gateway.agent.ToolRegistry;
import de.sweid.gateway.auth.BudgetGuard;
import de.sweid.gateway.auth.Caller;
import de.sweid.gateway.cost.UsageLedger;
import de.sweid.gateway.cost.UsageRecord;
import de.sweid.gateway.routing.RoutingException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Agent runs: the model plus server-side tools, returned at once or streamed step by step. */
@RestController
public class AgentController {

  private final AgentRunner runner;
  private final ToolRegistry tools;
  private final BudgetGuard budget;
  private final UsageLedger ledger;
  private final ObjectMapper json;

  public AgentController(
      AgentRunner runner,
      ToolRegistry tools,
      BudgetGuard budget,
      UsageLedger ledger,
      ObjectMapper json) {
    this.runner = runner;
    this.tools = tools;
    this.budget = budget;
    this.ledger = ledger;
    this.json = json;
  }

  @GetMapping("/v1/tools")
  public Map<String, Object> listTools() {
    return Map.of("object", "list", "data", tools.definitions(null));
  }

  @PostMapping(value = "/v1/agent/runs", produces = {"application/json", "text/event-stream"})
  public ResponseEntity<AgentResponse> run(
      @Valid @RequestBody AgentRequest request,
      @RequestAttribute(Caller.ATTRIBUTE) Caller caller,
      HttpServletResponse servletResponse) {
    budget.check(caller);
    try {
      tools.definitions(request.tools());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "invalid_request_error", e.getMessage());
    }

    if (!request.streaming()) {
      AgentResponse result = runner.run(request, event -> {});
      record(caller, request, result);
      return ResponseEntity.ok()
          .header(ChatController.COST_HEADER, result.costUsd().toPlainString())
          .body(result);
    }

    SseWriter sse = new SseWriter(servletResponse, json);
    try {
      AgentResponse result = runner.run(request, event -> sse.event(event.type(), event.data()));
      record(caller, request, result);
    } catch (RoutingException e) {
      sse.event("error", ApiExceptionHandler.error("upstream_error", e.getMessage(), e.attempts()));
    }
    sse.done();
    return null;
  }

  private void record(Caller caller, AgentRequest request, AgentResponse result) {
    List<String> routes = result.routes();
    String route =
        routes.isEmpty() ? "none" : String.join(",", routes.stream().distinct().toList());
    ledger.record(
        new UsageRecord(
            Instant.now(),
            caller.name(),
            "agent",
            request.model(),
            route,
            result.promptTokens(),
            result.completionTokens(),
            result.costUsd(),
            result.latencyMs(),
            false));
  }
}
