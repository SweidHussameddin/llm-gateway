package de.sweid.gateway.provider;

import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import java.util.function.Consumer;

/**
 * Talks to one upstream API. Implementations translate between the OpenAI shape used by clients
 * and whatever the provider speaks, and throw {@link ProviderException} for anything that is not a
 * usable answer, so the router can decide whether to try the next route.
 */
public interface ProviderClient {

  ChatResponse complete(ChatRequest request, Route route);

  /** Streams deltas to {@code onChunk}. The last chunk carries usage when the provider sends it. */
  void stream(ChatRequest request, Route route, Consumer<ChatChunk> onChunk);
}
