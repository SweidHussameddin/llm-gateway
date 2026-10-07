package de.sweid.gateway.model;

import java.util.List;

/** Non-streaming chat completion response in the OpenAI shape. */
public record ChatResponse(
    String id, String object, Long created, String model, List<Choice> choices, Usage usage) {

  public record Choice(int index, Message message, String finishReason) {}

  public Message firstMessage() {
    return choices == null || choices.isEmpty() ? null : choices.getFirst().message();
  }

  public String firstFinishReason() {
    return choices == null || choices.isEmpty() ? null : choices.getFirst().finishReason();
  }

  public ChatResponse withModelAndUsage(String newModel, Usage newUsage) {
    return new ChatResponse(id, object, created, newModel, choices, newUsage);
  }
}
