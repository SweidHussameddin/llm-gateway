package de.sweid.gateway.model;

import java.util.List;

/** One streamed delta in the OpenAI shape ({@code object = chat.completion.chunk}). */
public record ChatChunk(
    String id, String object, Long created, String model, List<Choice> choices, Usage usage) {

  public record Choice(int index, Delta delta, String finishReason) {}

  /** Partial message. Tool-call deltas carry an {@code index} so clients can stitch arguments. */
  public record Delta(String role, String content, List<ToolCallDelta> toolCalls) {}

  public record ToolCallDelta(int index, String id, String type, ToolCall.FunctionCall function) {}

  public ChatChunk withModelAndUsage(String newModel, Usage newUsage) {
    return new ChatChunk(id, object, created, newModel, choices, newUsage);
  }

  public String contentDelta() {
    if (choices == null || choices.isEmpty() || choices.getFirst().delta() == null) {
      return null;
    }
    return choices.getFirst().delta().content();
  }
}
