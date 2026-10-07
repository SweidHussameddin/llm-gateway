package de.sweid.gateway.model;

import java.util.List;

/**
 * One chat message. {@code content} is a String for the normal case and may be a list of content
 * parts for multimodal clients; this gateway passes it through untouched.
 */
public record Message(String role, Object content, List<ToolCall> toolCalls, String toolCallId) {

  public static Message system(String text) {
    return new Message("system", text, null, null);
  }

  public static Message user(String text) {
    return new Message("user", text, null, null);
  }

  public static Message assistant(String text, List<ToolCall> toolCalls) {
    return new Message("assistant", text, toolCalls, null);
  }

  public static Message tool(String toolCallId, String result) {
    return new Message("tool", result, null, toolCallId);
  }

  /** Text content, or an empty string when the content is null or a list of parts. */
  public String text() {
    return content instanceof String s ? s : "";
  }
}
