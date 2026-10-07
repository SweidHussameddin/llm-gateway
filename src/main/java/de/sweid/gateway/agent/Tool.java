package de.sweid.gateway.agent;

import java.util.Map;

/**
 * A server-side tool the agent may call. Any Spring bean implementing this is picked up by the
 * registry. {@code parameters} is a JSON schema object; {@code execute} returns something Jackson
 * can serialise, which goes back to the model as the tool result.
 */
public interface Tool {

  String name();

  String description();

  Map<String, Object> parameters();

  Object execute(Map<String, Object> arguments);
}
