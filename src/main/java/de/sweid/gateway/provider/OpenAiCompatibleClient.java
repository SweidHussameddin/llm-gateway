package de.sweid.gateway.provider;

import de.sweid.gateway.config.GatewayProperties.Provider;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Usage;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Client for anything that speaks the OpenAI chat-completions API: OpenRouter, Ollama, llama.cpp,
 * vLLM, Groq, Mistral and the real thing. The request is forwarded as is, with the route's model
 * substituted; usage is normalised and estimated when missing.
 */
public class OpenAiCompatibleClient implements ProviderClient {

  private final RestClient http;
  private final ObjectMapper json;

  public OpenAiCompatibleClient(RestClient.Builder builder, Provider provider, ObjectMapper json) {
    this.json = json;
    RestClient.Builder b = builder.clone().baseUrl(provider.baseUrl());
    if (provider.configured()) {
      b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
    }
    this.http = b.build();
  }

  @Override
  public ChatResponse complete(ChatRequest request, Route route) {
    ChatRequest upstream = request.withModel(route.model()).withStream(false);
    try {
      ChatResponse response =
          http.post()
              .uri("/chat/completions")
              .contentType(MediaType.APPLICATION_JSON)
              .body(upstream)
              .retrieve()
              .body(ChatResponse.class);
      if (response == null || response.choices() == null || response.choices().isEmpty()) {
        throw new ProviderException(502, "provider returned no choices");
      }
      Usage usage = response.usage();
      if (usage == null) {
        usage =
            Usage.estimated(
                TokenEstimate.ofRequest(upstream),
                TokenEstimate.ofText(response.firstMessage().text()));
      }
      return response.withModelAndUsage(route.model(), usage);
    } catch (RestClientResponseException e) {
      throw new ProviderException(e.getStatusCode().value(), errorMessage(e));
    } catch (ResourceAccessException e) {
      throw new ProviderException("cannot reach provider: " + e.getMessage(), e);
    }
  }

  @Override
  public void stream(ChatRequest request, Route route, Consumer<ChatChunk> onChunk) {
    Map<String, Object> upstream = new LinkedHashMap<>();
    upstream.put("model", route.model());
    upstream.put("messages", request.messages());
    upstream.put("stream", true);
    upstream.put("stream_options", Map.of("include_usage", true));
    if (request.temperature() != null) {
      upstream.put("temperature", request.temperature());
    }
    if (request.maxTokens() != null) {
      upstream.put("max_tokens", request.maxTokens());
    }
    if (request.tools() != null) {
      upstream.put("tools", request.tools());
    }
    if (request.toolChoice() != null) {
      upstream.put("tool_choice", request.toolChoice());
    }
    if (request.responseFormat() != null) {
      upstream.put("response_format", request.responseFormat());
    }

    StringBuilder seen = new StringBuilder();
    boolean[] usageSeen = {false};
    try {
      int events =
          http.post()
              .uri("/chat/completions")
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.TEXT_EVENT_STREAM)
              .body(upstream)
              .exchange(
                  (req, res) -> {
                    if (res.getStatusCode().isError()) {
                      throw new ProviderException(
                          res.getStatusCode().value(), bodyAsText(res.getBody()));
                    }
                    return SseReader.read(
                        res.getBody(),
                        (event, data) -> {
                          if ("[DONE]".equals(data)) {
                            return;
                          }
                          ChatChunk chunk = json.readValue(data, ChatChunk.class);
                          if (chunk.contentDelta() != null) {
                            seen.append(chunk.contentDelta());
                          }
                          if (chunk.usage() != null) {
                            usageSeen[0] = true;
                          }
                          onChunk.accept(chunk.withModelAndUsage(route.model(), chunk.usage()));
                        });
                  },
                  false);
      if (events == 0) {
        throw new ProviderException(502, "provider sent an empty stream");
      }
    } catch (ResourceAccessException e) {
      throw new ProviderException("cannot reach provider: " + e.getMessage(), e);
    }
    if (!usageSeen[0]) {
      Usage estimated =
          Usage.estimated(
              TokenEstimate.ofRequest(request), TokenEstimate.ofText(seen.toString()));
      onChunk.accept(new ChatChunk(null, "chat.completion.chunk", null, route.model(),
          java.util.List.of(), estimated));
    }
  }

  private String errorMessage(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    return body.isBlank() ? e.getStatusText() : body.length() > 500 ? body.substring(0, 500) : body;
  }

  private static String bodyAsText(InputStream body) {
    try {
      byte[] bytes = body.readNBytes(2000);
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      return "";
    }
  }
}
