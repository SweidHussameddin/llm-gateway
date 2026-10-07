package de.sweid.gateway.provider;

import de.sweid.gateway.config.GatewayProperties;
import de.sweid.gateway.config.GatewayProperties.Provider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Builds one client per configured provider at startup and hands them out by name. */
@Component
public class ProviderRegistry {

  private final Map<String, ProviderClient> clients = new LinkedHashMap<>();
  private final Map<String, Provider> providers;

  public ProviderRegistry(
      GatewayProperties properties, RestClient.Builder builder, ObjectMapper json) {
    this.providers = properties.providers();
    properties
        .providers()
        .forEach(
            (name, provider) -> {
              ProviderClient client =
                  switch (provider.type()) {
                    case "openai-compatible" -> new OpenAiCompatibleClient(builder, provider, json);
                    case "anthropic" -> new AnthropicClient(builder, provider, json);
                    default ->
                        throw new IllegalArgumentException(
                            "unknown provider type '" + provider.type() + "' for " + name);
                  };
              clients.put(name, client);
            });
  }

  public ProviderClient get(String name) {
    ProviderClient client = clients.get(name);
    if (client == null) {
      throw new IllegalArgumentException("no provider named '" + name + "'");
    }
    return client;
  }

  /** True when the provider exists and, for hosted providers, has an API key. */
  public boolean usable(String name) {
    Provider p = providers.get(name);
    return p != null && (p.configured() || p.baseUrl().contains("localhost"));
  }
}
