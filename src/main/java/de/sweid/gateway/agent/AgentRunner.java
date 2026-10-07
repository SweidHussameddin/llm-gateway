package de.sweid.gateway.agent;

import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.cost.CostCalculator;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Message;
import de.sweid.gateway.model.ToolCall;
import de.sweid.gateway.model.ToolDef;
import de.sweid.gateway.model.Usage;
import de.sweid.gateway.routing.Router;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The tool-calling loop: ask the model, run whatever tools it asked for, feed the results back,
 * repeat until it answers in plain text or the step cap is hit. Every model call goes through the
 * router, so failover and cost tracking apply per step.
 */
@Component
public class AgentRunner {

  private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
  private static final String DEFAULT_INSTRUCTIONS =
      "You are a precise assistant. Use the available tools to look things up instead of guessing."
          + " When you have what you need, answer briefly and include the figures you found.";

  private final Router router;
  private final ToolRegistry tools;
  private final ObjectMapper json;

  public AgentRunner(Router router, ToolRegistry tools, ObjectMapper json) {
    this.router = router;
    this.tools = tools;
    this.json = json;
  }

  public AgentResponse run(AgentRequest request, Consumer<AgentEvent> events) {
    final long started = System.nanoTime();
    final String id = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    List<ToolDef> defs = tools.definitions(request.tools());
    List<Message> messages = new ArrayList<>();
    messages.add(
        Message.system(
            request.instructions() == null ? DEFAULT_INSTRUCTIONS : request.instructions()));
    messages.add(Message.user(request.input()));

    List<AgentResponse.Step> steps = new ArrayList<>();
    List<String> routes = new ArrayList<>();
    long prompt = 0;
    long completion = 0;
    BigDecimal cost = BigDecimal.ZERO;
    int calls = 0;
    String warning = null;
    String output = null;

    while (calls < request.maxStepsOrDefault()) {
      calls++;
      ChatRequest chat =
          new ChatRequest(
              request.model(), List.copyOf(messages), 0.0, null, false, defs, "auto", null);
      Router.Routed<ChatResponse> routed = router.complete(chat);
      Route route = routed.route();
      ChatResponse response = routed.value();
      Usage usage = CostCalculator.price(route, response.usage());
      prompt += usage.promptTokens();
      completion += usage.completionTokens();
      cost = cost.add(usage.costUsd());
      routes.add(route.id());
      events.accept(
          AgentEvent.of(
              "model_call",
              Map.of(
                  "n", calls,
                  "route", route.id(),
                  "prompt_tokens", usage.promptTokens(),
                  "completion_tokens", usage.completionTokens(),
                  "cost_usd", usage.costUsd())));

      Message reply = response.firstMessage();
      if (reply == null) {
        warning = "model returned no message";
        break;
      }
      messages.add(Message.assistant(reply.text().isEmpty() ? null : reply.text(),
          reply.toolCalls()));

      if (reply.toolCalls() == null || reply.toolCalls().isEmpty()) {
        output = reply.text();
        break;
      }

      for (ToolCall call : reply.toolCalls()) {
        AgentResponse.Step step = execute(steps.size() + 1, call, events);
        steps.add(step);
        Object payload = step.error() != null ? Map.of("error", step.error()) : step.result();
        messages.add(Message.tool(call.id(), json.writeValueAsString(payload)));
      }
    }

    if (output == null && warning == null) {
      warning = "stopped after " + calls + " model calls without a final answer";
      output = lastAssistantText(messages);
    }

    AgentResponse result =
        new AgentResponse(
            id,
            output,
            steps,
            calls,
            prompt,
            completion,
            cost,
            (System.nanoTime() - started) / 1_000_000,
            routes,
            warning);
    events.accept(AgentEvent.of("done", result));
    return result;
  }

  private AgentResponse.Step execute(int n, ToolCall call, Consumer<AgentEvent> events) {
    String name = call.function().name();
    Map<String, Object> args = parseArguments(call.function().arguments());
    events.accept(AgentEvent.of("tool_call", Map.of("n", n, "tool", name, "arguments", args)));
    long t0 = System.nanoTime();
    Object result = null;
    String error = null;
    Tool tool = tools.get(name).orElse(null);
    if (tool == null) {
      error = "unknown tool '" + name + "'";
    } else {
      try {
        result = tool.execute(args);
      } catch (RuntimeException e) {
        error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("tool {} failed: {}", name, error);
      }
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    Map<String, Object> ev = new LinkedHashMap<>();
    ev.put("n", n);
    ev.put("tool", name);
    ev.put("ms", ms);
    if (error != null) {
      ev.put("error", error);
    } else {
      ev.put("result", result);
    }
    events.accept(AgentEvent.of("tool_result", ev));
    return new AgentResponse.Step(n, name, args, result, error, ms);
  }

  private Map<String, Object> parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(arguments, new TypeReference<Map<String, Object>>() {});
    } catch (RuntimeException e) {
      return Map.of("_raw", arguments);
    }
  }

  private static String lastAssistantText(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      Message m = messages.get(i);
      if ("assistant".equals(m.role()) && !m.text().isBlank()) {
        return m.text();
      }
    }
    return null;
  }
}
