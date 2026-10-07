package de.sweid.gateway.model;

/** A tool call the model asked for. {@code arguments} is a JSON string, as in the OpenAI API. */
public record ToolCall(String id, String type, FunctionCall function) {

  public static ToolCall function(String id, String name, String argumentsJson) {
    return new ToolCall(id, "function", new FunctionCall(name, argumentsJson));
  }

  public record FunctionCall(String name, String arguments) {}
}
