package de.sweid.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.sweid.gateway.TestSupport;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Message;
import de.sweid.gateway.model.ToolCall;
import de.sweid.gateway.model.ToolDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class AnthropicClientTest {

  private static final String BASE = "http://anthropic";
  private final Route route = TestSupport.route("anthropic", "claude-x", "3", "15");
  private MockRestServiceServer server;
  private AnthropicClient client;

  @BeforeEach
  void setUp() {
    JsonMapper mapper = TestSupport.mapper();
    RestClient.Builder builder = TestSupport.restClientBuilder(mapper);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new AnthropicClient(builder, TestSupport.anthropicProvider(BASE), mapper);
  }

  @Test
  @SuppressWarnings("unchecked")
  void translatesSystemToolsAndToolResultsIntoMessagesApiShape() {
    ToolDef tool =
        ToolDef.function(
            "lookup_order",
            "Look up an order",
            Map.of("type", "object", "properties", Map.of("order_id", Map.of("type", "string"))));
    ChatRequest req =
        new ChatRequest(
            "claude",
            List.of(
                Message.system("Be brief."),
                Message.user("Where is order 1042?"),
                Message.assistant(
                    null, List.of(ToolCall.function("call_1", "lookup_order", "{\"order_id\":\"1042\"}"))),
                Message.tool("call_1", "{\"status\":\"shipped\"}")),
            0.1,
            300,
            false,
            List.of(tool),
            "auto",
            null);

    Map<String, Object> body = client.toAnthropicRequest(req, route, false);

    assertThat(body).containsEntry("model", "claude-x").containsEntry("max_tokens", 300);
    assertThat(body).containsEntry("system", "Be brief.");
    List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
    assertThat(messages).hasSize(3);
    assertThat(messages.get(0)).containsEntry("role", "user");
    List<Map<String, Object>> assistantBlocks =
        (List<Map<String, Object>>) messages.get(1).get("content");
    assertThat(assistantBlocks.getFirst())
        .containsEntry("type", "tool_use")
        .containsEntry("id", "call_1")
        .containsEntry("input", Map.of("order_id", "1042"));
    List<Map<String, Object>> resultBlocks = (List<Map<String, Object>>) messages.get(2).get("content");
    assertThat(resultBlocks.getFirst())
        .containsEntry("type", "tool_result")
        .containsEntry("tool_use_id", "call_1");
    List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
    assertThat(tools.getFirst()).containsEntry("name", "lookup_order").containsKey("input_schema");
    assertThat(body).doesNotContainKey("tool_choice");
  }

  @Test
  void mapsToolUseResponseBackToOpenAiToolCalls() {
    server
        .expect(requestTo(BASE + "/v1/messages"))
        .andExpect(header("x-api-key", "test-key"))
        .andExpect(header("anthropic-version", "2023-06-01"))
        .andExpect(jsonPath("$.messages[0].role").value("user"))
        .andRespond(
            withSuccess(
                """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-x",
                 "content":[{"type":"text","text":"Let me check."},
                            {"type":"tool_use","id":"toolu_1","name":"lookup_order","input":{"order_id":"1042"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":40,"output_tokens":12}}
                """,
                MediaType.APPLICATION_JSON));

    ChatResponse res =
        client.complete(
            new ChatRequest("claude", List.of(Message.user("hi")), null, null, false, null, null, null),
            route);

    assertThat(res.firstFinishReason()).isEqualTo("tool_calls");
    assertThat(res.firstMessage().text()).isEqualTo("Let me check.");
    ToolCall call = res.firstMessage().toolCalls().getFirst();
    assertThat(call.id()).isEqualTo("toolu_1");
    assertThat(call.function().name()).isEqualTo("lookup_order");
    assertThat(call.function().arguments()).isEqualTo("{\"order_id\":\"1042\"}");
    assertThat(res.usage().promptTokens()).isEqualTo(40);
    assertThat(res.usage().completionTokens()).isEqualTo(12);
  }

  @Test
  void foldsStreamEventsIntoOpenAiChunks() {
    String sse =
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_s","usage":{"input_tokens":9,"output_tokens":1}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"calculate","input":{}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"expression\\":"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"1+1\\"}"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":14}}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    server
        .expect(requestTo(BASE + "/v1/messages"))
        .andExpect(jsonPath("$.stream").value(true))
        .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

    List<ChatChunk> chunks = new ArrayList<>();
    client.stream(
        new ChatRequest("claude", List.of(Message.user("hi")), null, null, true, null, null, null),
        route,
        chunks::add);

    String text =
        chunks.stream()
            .map(ChatChunk::contentDelta)
            .filter(s -> s != null)
            .reduce("", String::concat);
    assertThat(text).isEqualTo("Hello");
    String args =
        chunks.stream()
            .filter(c -> c.choices().getFirst().delta().toolCalls() != null)
            .map(c -> c.choices().getFirst().delta().toolCalls().getFirst().function().arguments())
            .reduce("", String::concat);
    assertThat(args).isEqualTo("{\"expression\":\"1+1\"}");
    ChatChunk last = chunks.getLast();
    assertThat(last.choices().getFirst().finishReason()).isEqualTo("tool_calls");
    assertThat(last.usage().promptTokens()).isEqualTo(9);
    assertThat(last.usage().completionTokens()).isEqualTo(14);
    assertThat(last.id()).isEqualTo("msg_s");
  }
}
