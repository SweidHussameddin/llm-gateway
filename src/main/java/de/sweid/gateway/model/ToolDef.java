package de.sweid.gateway.model;

import java.util.Map;

/** A tool definition offered to the model (OpenAI "function" tool). */
public record ToolDef(String type, Function function) {

  public static ToolDef function(String name, String description, Map<String, Object> parameters) {
    return new ToolDef("function", new Function(name, description, parameters));
  }

  public record Function(String name, String description, Map<String, Object> parameters) {}
}
