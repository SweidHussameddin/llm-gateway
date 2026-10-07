package de.sweid.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.sweid.gateway.config.GatewayProperties;
import de.sweid.gateway.config.GatewayProperties.Breaker;
import de.sweid.gateway.config.GatewayProperties.ModelAlias;
import de.sweid.gateway.config.GatewayProperties.Provider;
import de.sweid.gateway.config.GatewayProperties.Route;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Shared fixtures: a Boot-like snake_case mapper, a RestClient builder using it, config helpers. */
public final class TestSupport {

  private TestSupport() {}

  public static JsonMapper mapper() {
    return JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
        .build();
  }

  public static RestClient.Builder restClientBuilder(JsonMapper mapper) {
    return RestClient.builder().messageConverters(List.of(new JacksonJsonHttpMessageConverter(mapper)));
  }

  public static Provider openAiProvider(String baseUrl) {
    return new Provider("openai-compatible", baseUrl, "test-key", 5);
  }

  public static Provider anthropicProvider(String baseUrl) {
    return new Provider("anthropic", baseUrl, "test-key", 5);
  }

  public static Route route(String provider, String model, String inPrice, String outPrice) {
    return new Route(provider, model, new BigDecimal(inPrice), new BigDecimal(outPrice));
  }

  public static GatewayProperties properties(
      Map<String, Provider> providers, Breaker breaker, ModelAlias... aliases) {
    return new GatewayProperties(
        "target/test-ledger-unused.jsonl", List.of(), providers, breaker, List.of(aliases));
  }

  public static String chatJson(String content, String finishReason, int prompt, int completion) {
    return """
        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"m",
         "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
                     "finish_reason":"%s"}],
         "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
        """
        .formatted(content, finishReason, prompt, completion, prompt + completion);
  }
}
