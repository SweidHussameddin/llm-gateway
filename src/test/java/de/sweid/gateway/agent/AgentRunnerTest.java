package de.sweid.gateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.sweid.gateway.TestSupport;
import de.sweid.gateway.agent.tools.CalculatorTool;
import de.sweid.gateway.agent.tools.OrderLookupTool;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Message;
import de.sweid.gateway.model.ToolCall;
import de.sweid.gateway.model.Usage;
import de.sweid.gateway.routing.Router;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunnerTest {

  private final Route route = TestSupport.route("p", "m", "1", "2");

  private static ChatResponse reply(Message message, String finish, int prompt, int completion) {
    return new ChatResponse(
        "id", "chat.completion", 1L, "m",
        List.of(new ChatResponse.Choice(0, message, finish)), Usage.of(prompt, completion));
  }

  @Test
  void runsToolsUntilTheModelAnswers() {
    Router router = mock(Router.class);
    ToolRegistry registry = new ToolRegistry(List.of(new OrderLookupTool(), new CalculatorTool()));
    AgentRunner runner = new AgentRunner(router, registry, TestSupport.mapper());

    Message askTool =
        Message.assistant(
            null,
            List.of(
                ToolCall.function("c1", "lookup_order", "{\"order_id\":\"1042\"}"),
                ToolCall.function("c2", "calculate", "{\"expression\":\"4890 * 0.19\"}")));
    when(router.complete(any()))
        .thenReturn(new Router.Routed<>(route, reply(askTool, "tool_calls", 100, 20), List.of()))
        .thenReturn(
            new Router.Routed<>(
                route, reply(Message.assistant("VAT is 929.10", null), "stop", 300, 10),
                List.of()));

    List<AgentEvent> events = new ArrayList<>();
    AgentResponse res =
        runner.run(new AgentRequest("fast", "What is the VAT on order 1042?", null, null, 5, false),
            events::add);

    assertThat(res.output()).isEqualTo("VAT is 929.10");
    assertThat(res.steps()).hasSize(2);
    assertThat(res.steps().get(0).tool()).isEqualTo("lookup_order");
    assertThat(res.steps().get(1).result()).asString().contains("929.1");
    assertThat(res.modelCalls()).isEqualTo(2);
    assertThat(res.promptTokens()).isEqualTo(400);
    assertThat(res.completionTokens()).isEqualTo(30);
    assertThat(res.costUsd()).isEqualByComparingTo(new BigDecimal("0.000460"));
    assertThat(events).extracting(AgentEvent::type)
        .containsExactly(
            "model_call", "tool_call", "tool_result", "tool_call", "tool_result", "model_call",
            "done");

    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(router, times(2)).complete(captor.capture());
    List<Message> second = captor.getAllValues().get(1).messages();
    assertThat(second).extracting(Message::role)
        .containsExactly("system", "user", "assistant", "tool", "tool");
    assertThat(second.get(3).toolCallId()).isEqualTo("c1");
    assertThat(second.get(3).text()).contains("\"status\":\"shipped\"");
    assertThat(captor.getAllValues().getFirst().tools()).hasSize(2);
  }

  @Test
  void unknownToolAndToolErrorsAreReportedBackToTheModel() {
    Router router = mock(Router.class);
    ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()));
    AgentRunner runner = new AgentRunner(router, registry, TestSupport.mapper());

    Message askTool =
        Message.assistant(
            null,
            List.of(
                ToolCall.function("c1", "send_rocket", "{}"),
                ToolCall.function("c2", "calculate", "{\"expression\":\"1/0\"}")));
    when(router.complete(any()))
        .thenReturn(new Router.Routed<>(route, reply(askTool, "tool_calls", 1, 1), List.of()))
        .thenReturn(
            new Router.Routed<>(route, reply(Message.assistant("sorry", null), "stop", 1, 1),
                List.of()));

    AgentResponse res = runner.run(new AgentRequest("fast", "go", null, null, 5, false), e -> {});

    assertThat(res.steps().get(0).error()).contains("unknown tool");
    assertThat(res.steps().get(1).error()).contains("division by zero");
    assertThat(res.warning()).isNull();
  }

  @Test
  void stopsAtMaxStepsWithWarning() {
    Router router = mock(Router.class);
    ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()));
    AgentRunner runner = new AgentRunner(router, registry, TestSupport.mapper());
    Message loop =
        Message.assistant(
            "thinking", List.of(ToolCall.function("c", "calculate", "{\"expression\":\"1\"}")));
    when(router.complete(any()))
        .thenReturn(new Router.Routed<>(route, reply(loop, "tool_calls", 1, 1), List.of()));

    AgentResponse res = runner.run(new AgentRequest("fast", "go", null, null, 2, false), e -> {});

    assertThat(res.modelCalls()).isEqualTo(2);
    assertThat(res.warning()).contains("2 model calls");
    assertThat(res.output()).isEqualTo("thinking");
  }
}
