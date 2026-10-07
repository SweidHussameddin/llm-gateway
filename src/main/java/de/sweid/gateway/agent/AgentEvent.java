package de.sweid.gateway.agent;

/** Progress events emitted while an agent run executes; streamed to clients as SSE. */
public record AgentEvent(String type, Object data) {

  public static AgentEvent of(String type, Object data) {
    return new AgentEvent(type, data);
  }
}
