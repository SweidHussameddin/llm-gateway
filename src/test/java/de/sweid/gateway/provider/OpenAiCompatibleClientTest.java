package de.sweid.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.sweid.gateway.TestSupport;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleClientTest {

  private static final String BASE = "http://provider/v1";
  private final Route route = TestSupport.route("p", "model-x", "0", "0");
  private MockRestServiceServer server;
  private OpenAiCompatibleClient client;

  @BeforeEach
  void setUp() {
    JsonMapper mapper = TestSupport.mapper();
    RestClient.Builder builder = TestSupport.restClientBuilder(mapper);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new OpenAiCompatibleClient(builder, TestSupport.openAiProvider(BASE), mapper);
  }

  private static ChatRequest request() {
    return new ChatRequest(
        "alias", List.of(Message.user("Say hi")), 0.2, 50, false, null, null, null);
  }

  @Test
  void forwardsRequestWithRouteModelAndBearer() {
    server
        .expect(requestTo(BASE + "/chat/completions"))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(jsonPath("$.model").value("model-x"))
        .andExpect(jsonPath("$.max_tokens").value(50))
        .andExpect(jsonPath("$.temperature").value(0.2))
        .andExpect(jsonPath("$.stream").value(false))
        .andRespond(
            withSuccess(TestSupport.chatJson("hi", "stop", 7, 1), MediaType.APPLICATION_JSON));

    ChatResponse res = client.complete(request(), route);

    assertThat(res.model()).isEqualTo("model-x");
    assertThat(res.firstMessage().text()).isEqualTo("hi");
    assertThat(res.usage().totalTokens()).isEqualTo(8);
    assertThat(res.usage().estimated()).isNull();
  }

  @Test
  void estimatesUsageWhenProviderSendsNone() {
    server
        .expect(requestTo(BASE + "/chat/completions"))
        .andRespond(
            withSuccess(
                """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"twelve chars"},
                 "finish_reason":"stop"}]}
                """,
                MediaType.APPLICATION_JSON));

    ChatResponse res = client.complete(request(), route);

    assertThat(res.usage().estimated()).isTrue();
    assertThat(res.usage().completionTokens()).isEqualTo(3);
    assertThat(res.usage().promptTokens()).isPositive();
  }

  @Test
  void rateLimitBecomesRetryableProviderException() {
    server
        .expect(requestTo(BASE + "/chat/completions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":\"slow down\"}"));

    assertThatThrownBy(() -> client.complete(request(), route))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            e -> {
              ProviderException pe = (ProviderException) e;
              assertThat(pe.status()).isEqualTo(429);
              assertThat(pe.retryable()).isTrue();
              assertThat(pe.getMessage()).contains("slow down");
            });
  }

  @Test
  void streamParsesChunksAndAppendsEstimatedUsageWhenMissing() {
    String sse =
        """
        data: {"id":"c1","object":"chat.completion.chunk","model":"model-x","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]}

        data: {"id":"c1","object":"chat.completion.chunk","model":"model-x","choices":[{"index":0,"delta":{"content":" there"},"finish_reason":"stop"}]}

        data: [DONE]

        """;
    server
        .expect(requestTo(BASE + "/chat/completions"))
        .andExpect(jsonPath("$.stream").value(true))
        .andExpect(jsonPath("$.stream_options.include_usage").value(true))
        .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

    List<ChatChunk> chunks = new ArrayList<>();
    client.stream(request().withStream(true), route, chunks::add);

    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).contentDelta()).isEqualTo("Hello");
    assertThat(chunks.get(1).choices().getFirst().finishReason()).isEqualTo("stop");
    assertThat(chunks.get(2).usage().estimated()).isTrue();
    assertThat(chunks.get(2).usage().completionTokens()).isEqualTo(3);
  }

  @Test
  void emptyStreamIsAnError() {
    server
        .expect(requestTo(BASE + "/chat/completions"))
        .andRespond(withSuccess("", MediaType.TEXT_EVENT_STREAM));

    assertThatThrownBy(() -> client.stream(request().withStream(true), route, c -> {}))
        .isInstanceOf(ProviderException.class)
        .hasMessageContaining("empty stream");
  }
}
