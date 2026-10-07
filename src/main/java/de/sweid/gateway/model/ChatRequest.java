package de.sweid.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/** The OpenAI chat-completions request shape, the only shape clients ever speak. */
public record ChatRequest(
    @NotBlank String model,
    @NotEmpty List<Message> messages,
    Double temperature,
    Integer maxTokens,
    Boolean stream,
    List<ToolDef> tools,
    Object toolChoice,
    Map<String, Object> responseFormat) {

  public boolean streaming() {
    return Boolean.TRUE.equals(stream);
  }

  public ChatRequest withModel(String newModel) {
    return new ChatRequest(
        newModel, messages, temperature, maxTokens, stream, tools, toolChoice, responseFormat);
  }

  public ChatRequest withMessages(List<Message> newMessages) {
    return new ChatRequest(
        model, newMessages, temperature, maxTokens, stream, tools, toolChoice, responseFormat);
  }

  public ChatRequest withStream(boolean newStream) {
    return new ChatRequest(
        model, messages, temperature, maxTokens, newStream, tools, toolChoice, responseFormat);
  }
}
