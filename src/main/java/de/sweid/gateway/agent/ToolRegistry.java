package de.sweid.gateway.agent;

import de.sweid.gateway.model.ToolDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** All {@link Tool} beans by name, and their definitions in the shape the model expects. */
@Component
public class ToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();

  public ToolRegistry(List<Tool> beans) {
    for (Tool t : beans) {
      tools.put(t.name(), t);
    }
  }

  public Optional<Tool> get(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public List<String> names() {
    return List.copyOf(tools.keySet());
  }

  /** Definitions for the given names, or for every tool when {@code names} is null or empty. */
  public List<ToolDef> definitions(List<String> names) {
    List<ToolDef> defs = new ArrayList<>();
    List<String> wanted = names == null || names.isEmpty() ? names() : names;
    for (String n : wanted) {
      Tool t = tools.get(n);
      if (t == null) {
        throw new IllegalArgumentException("unknown tool '" + n + "'");
      }
      defs.add(ToolDef.function(t.name(), t.description(), t.parameters()));
    }
    return defs;
  }
}
