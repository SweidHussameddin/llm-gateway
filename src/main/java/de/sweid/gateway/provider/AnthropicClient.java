package de.sweid.gateway.provider;

import de.sweid.gateway.config.GatewayProperties.Provider;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Message;
import de.sweid.gateway.model.ToolCall;
import de.sweid.gateway.model.ToolDef;
import de.sweid.gateway.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Native client for the Anthropic Messages API. Clients keep talking OpenAI; this class rewrites
 * messages, tools and tool results into Anthropic content blocks on the way in, and content blocks,
 * stop reasons and usage back into the OpenAI shape on the way out, for both sync and streaming.
 */
public class AnthropicClient implements ProviderClient {

  private static final String VERSION = "2023-06-01";
  private static final int DEFAULT_MAX_TOKENS = 1024;

  private final RestClient http;
  private final ObjectMapper json;

  public AnthropicClient(RestClient.Builder builder, Provider provider, ObjectMapper json) {
    this.json = json;
    this.http =
        builder
            .clone()
            .baseUrl(provider.baseUrl())
            .defaultHeader("x-api-key", provider.apiKey() == null ? "" : provider.apiKey())
            .defaultHeader("anthropic-version", VERSION)
            .build();
  }

  // ---- request translation ---------------------------------------------------------------

  Map<String, Object> toAnthropicRequest(ChatRequest request, Route route, boolean stream) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", route.model());
    body.put("max_tokens", request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens());
    if (request.temperature() != null) {
      body.put("temperature", request.temperature());
    }
    if (stream) {
      body.put("stream", true);
    }

    StringBuilder system = new StringBuilder();
    List<Map<String, Object>> messages = new ArrayList<>();
    for (Message m : request.messages()) {
      switch (m.role()) {
        case "system", "developer" -> {
          if (!system.isEmpty()) {
            system.append("\n\n");
          }
          system.append(m.text());
        }
        case "user" -> messages.add(Map.of("role", "user", "content", m.content()));
        case "assistant" -> {
          List<Map<String, Object>> blocks = new ArrayList<>();
          if (!m.text().isBlank()) {
            blocks.add(Map.of("type", "text", "text", m.text()));
          }
          if (m.toolCalls() != null) {
            for (ToolCall call : m.toolCalls()) {
              blocks.add(
                  Map.of(
                      "type", "tool_use",
                      "id", call.id(),
                      "name", call.function().name(),
                      "input", parseArguments(call.function().arguments())));
            }
          }
          messages.add(Map.of("role", "assistant", "content", blocks));
        }
        case "tool" -> {
          Map<String, Object> result =
              Map.of(
                  "type", "tool_result",
                  "tool_use_id", m.toolCallId(),
                  "content", m.text());
          // Consecutive tool results must share one user message.
          Map<String, Object> last = messages.isEmpty() ? null : messages.getLast();
          if (last != null
              && "user".equals(last.get("role"))
              && last.get("content") instanceof List<?> l
              && !l.isEmpty()
              && l.getFirst() instanceof Map<?, ?> first
              && "tool_result".equals(first.get("type"))) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) l;
            content.add(result);
          } else {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(result);
            messages.add(Map.of("role", "user", "content", content));
          }
        }
        default -> throw new ProviderException(400, "unsupported role: " + m.role());
      }
    }
    if (!system.isEmpty()) {
      body.put("system", system.toString());
    }
    body.put("messages", messages);

    if (request.tools() != null && !request.tools().isEmpty()) {
      List<Map<String, Object>> tools = new ArrayList<>();
      for (ToolDef t : request.tools()) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", t.function().name());
        if (t.function().description() != null) {
          tool.put("description", t.function().description());
        }
        tool.put(
            "input_schema",
            t.function().parameters() == null
                ? Map.of("type", "object", "properties", Map.of())
                : t.function().parameters());
        tools.add(tool);
      }
      body.put("tools", tools);
      if ("required".equals(request.toolChoice())) {
        body.put("tool_choice", Map.of("type", "any"));
      } else if ("none".equals(request.toolChoice())) {
        body.remove("tools");
      }
    }
    return body;
  }

  private Map<String, Object> parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(arguments, new tools.jackson.core.type.TypeReference<>() {});
    } catch (RuntimeException e) {
      return Map.of("_raw", arguments);
    }
  }

  // ---- sync -------------------------------------------------------------------------------

  @Override
  public ChatResponse complete(ChatRequest request, Route route) {
    try {
      JsonNode res =
          http.post()
              .uri("/v1/messages")
              .contentType(MediaType.APPLICATION_JSON)
              .body(toAnthropicRequest(request, route, false))
              .retrieve()
              .body(JsonNode.class);
      return toChatResponse(res, route.model());
    } catch (RestClientResponseException e) {
      throw new ProviderException(e.getStatusCode().value(), e.getResponseBodyAsString());
    } catch (ResourceAccessException e) {
      throw new ProviderException("cannot reach provider: " + e.getMessage(), e);
    }
  }

  ChatResponse toChatResponse(JsonNode res, String model) {
    StringBuilder text = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    for (JsonNode block : res.path("content")) {
      switch (block.path("type").asString()) {
        case "text" -> text.append(block.path("text").asString());
        case "tool_use" ->
            calls.add(
                ToolCall.function(
                    block.path("id").asString(),
                    block.path("name").asString(),
                    json.writeValueAsString(block.path("input"))));
        default -> {
          // thinking and other block types are not surfaced
        }
      }
    }
    Message message =
        Message.assistant(text.isEmpty() ? null : text.toString(), calls.isEmpty() ? null : calls);
    Usage usage =
        Usage.of(
            res.path("usage").path("input_tokens").asLong(),
            res.path("usage").path("output_tokens").asLong());
    return new ChatResponse(
        res.path("id").asString(),
        "chat.completion",
        Instant.now().getEpochSecond(),
        model,
        List.of(new ChatResponse.Choice(0, message, finishReason(res.path("stop_reason")))),
        usage);
  }

  private static String finishReason(JsonNode stopReason) {
    return switch (stopReason.asString()) {
      case "tool_use" -> "tool_calls";
      case "max_tokens" -> "length";
      default -> "stop";
    };
  }

  // ---- streaming --------------------------------------------------------------------------

  @Override
  public void stream(ChatRequest request, Route route, Consumer<ChatChunk> onChunk) {
    StreamState state = new StreamState(route.model(), onChunk);
    try {
      int events =
          http.post()
              .uri("/v1/messages")
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.TEXT_EVENT_STREAM)
              .body(toAnthropicRequest(request, route, true))
              .exchange(
                  (req, res) -> {
                    if (res.getStatusCode().isError()) {
                      throw new ProviderException(res.getStatusCode().value(), text(res.getBody()));
                    }
                    return SseReader.read(
                        res.getBody(), (event, data) -> state.on(json.readTree(data)));
                  },
                  false);
      if (events == 0) {
        throw new ProviderException(502, "provider sent an empty stream");
      }
    } catch (ResourceAccessException e) {
      throw new ProviderException("cannot reach provider: " + e.getMessage(), e);
    }
  }

  /** Folds Anthropic stream events into OpenAI chunks. */
  private final class StreamState {
    private final String model;
    private final Consumer<ChatChunk> out;
    private String id = "";
    private long inputTokens;
    private long outputTokens;
    private int toolIndex = -1;
    private String finish = "stop";

    StreamState(String model, Consumer<ChatChunk> out) {
      this.model = model;
      this.out = out;
    }

    void on(JsonNode ev) {
      switch (ev.path("type").asString()) {
        case "message_start" -> {
          id = ev.path("message").path("id").asString();
          inputTokens = ev.path("message").path("usage").path("input_tokens").asLong();
          emit(new ChatChunk.Delta("assistant", "", null), null, null);
        }
        case "content_block_start" -> {
          JsonNode block = ev.path("content_block");
          if ("tool_use".equals(block.path("type").asString())) {
            toolIndex++;
            emit(
                new ChatChunk.Delta(
                    null,
                    null,
                    List.of(
                        new ChatChunk.ToolCallDelta(
                            toolIndex,
                            block.path("id").asString(),
                            "function",
                            new ToolCall.FunctionCall(block.path("name").asString(), "")))),
                null,
                null);
          }
        }
        case "content_block_delta" -> {
          JsonNode delta = ev.path("delta");
          switch (delta.path("type").asString()) {
            case "text_delta" ->
                emit(new ChatChunk.Delta(null, delta.path("text").asString(), null), null, null);
            case "input_json_delta" ->
                emit(
                    new ChatChunk.Delta(
                        null,
                        null,
                        List.of(
                            new ChatChunk.ToolCallDelta(
                                toolIndex,
                                null,
                                null,
                                new ToolCall.FunctionCall(
                                    null, delta.path("partial_json").asString())))),
                    null,
                    null);
            default -> {
              // thinking deltas are not surfaced
            }
          }
        }
        case "message_delta" -> {
          finish = finishReason(ev.path("delta").path("stop_reason"));
          outputTokens = ev.path("usage").path("output_tokens").asLong();
        }
        case "message_stop" ->
            emit(
                new ChatChunk.Delta(null, null, null), finish, Usage.of(inputTokens, outputTokens));
        case "error" ->
            throw new ProviderException(502, ev.path("error").path("message").asString());
        default -> {
          // ping, content_block_stop
        }
      }
    }

    private void emit(ChatChunk.Delta delta, String finishReason, Usage usage) {
      out.accept(
          new ChatChunk(
              id,
              "chat.completion.chunk",
              Instant.now().getEpochSecond(),
              model,
              List.of(new ChatChunk.Choice(0, delta, finishReason)),
              usage));
    }
  }

  private static String text(InputStream body) {
    try {
      return new String(body.readNBytes(2000), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }
}
