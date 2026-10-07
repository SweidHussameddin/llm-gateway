package de.sweid.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.sweid.gateway.TestSupport;
import de.sweid.gateway.config.GatewayProperties;
import de.sweid.gateway.config.GatewayProperties.Breaker;
import de.sweid.gateway.config.GatewayProperties.ModelAlias;
import de.sweid.gateway.model.ChatChunk;
import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.ChatResponse;
import de.sweid.gateway.model.Message;
import de.sweid.gateway.provider.ProviderRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RouterTest {

  private static final String P1 = "http://primary";
  private static final String P2 = "http://secondary";

  private MockRestServiceServer server;
  private Router router;

  @BeforeEach
  void setUp() {
    JsonMapper mapper = TestSupport.mapper();
    RestClient.Builder builder = TestSupport.restClientBuilder(mapper);
    server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    GatewayProperties props =
        TestSupport.properties(
            Map.of(
                "primary", TestSupport.openAiProvider(P1),
                "secondary", TestSupport.openAiProvider(P2)),
            new Breaker(2, 30),
            new ModelAlias(
                "fast",
                "test",
                List.of(
                    TestSupport.route("primary", "m1", "1", "2"),
                    TestSupport.route("secondary", "m2", "3", "4"))));
    router = new Router(props, new ProviderRegistry(props, builder, mapper), Clock.systemUTC());
  }

  private static ChatRequest ask(String model) {
    return new ChatRequest(model, List.of(Message.user("hi")), null, null, false, null, null, null);
  }

  @Test
  void failsOverToSecondRouteOn503() {
    server.expect(requestTo(P1 + "/chat/completions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(requestTo(P2 + "/chat/completions"))
        .andExpect(jsonPath("$.model").value("m2"))
        .andExpect(jsonPath("$.messages[0].content").value("hi"))
        .andRespond(
            withSuccess(TestSupport.chatJson("hello", "stop", 10, 5), MediaType.APPLICATION_JSON));

    Router.Routed<ChatResponse> routed = router.complete(ask("fast"));

    assertThat(routed.route().id()).isEqualTo("secondary/m2");
    assertThat(routed.value().firstMessage().text()).isEqualTo("hello");
    assertThat(routed.value().usage().promptTokens()).isEqualTo(10);
    assertThat(routed.attempts()).hasSize(1).first().asString().contains("HTTP 503");
    server.verify();
  }

  @Test
  void clientErrorIsReturnedWithoutFailover() {
    server
        .expect(requestTo(P1 + "/chat/completions"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"bad\"}"));

    assertThatThrownBy(() -> router.complete(ask("fast")))
        .isInstanceOf(RoutingException.class)
        .satisfies(e -> assertThat(((RoutingException) e).status()).isEqualTo(400));
    server.verify();
  }

  @Test
  void breakerOpensAfterRepeatedFailuresAndRouteIsSkipped() {
    server
        .expect(ExpectedCount.times(2), requestTo(P1 + "/chat/completions"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    server
        .expect(ExpectedCount.times(3), requestTo(P2 + "/chat/completions"))
        .andRespond(
            withSuccess(TestSupport.chatJson("ok", "stop", 1, 1), MediaType.APPLICATION_JSON));

    router.complete(ask("fast"));
    router.complete(ask("fast"));
    Router.Routed<ChatResponse> third = router.complete(ask("fast"));

    assertThat(third.attempts()).containsExactly("primary/m1: breaker open");
    assertThat(router.breaker(TestSupport.route("primary", "m1", "0", "0")).state())
        .isEqualTo(CircuitBreaker.State.OPEN);
    server.verify();
  }

  @Test
  void unknownModelIs404() {
    assertThatThrownBy(() -> router.complete(ask("nope")))
        .isInstanceOf(RoutingException.class)
        .satisfies(e -> assertThat(((RoutingException) e).status()).isEqualTo(404));
  }

  @Test
  void streamingFailsOverBeforeFirstChunk() {
    server.expect(requestTo(P1 + "/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    String sse =
        """
        data: {"id":"c","object":"chat.completion.chunk","model":"m2","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}

        data: {"id":"c","object":"chat.completion.chunk","model":"m2","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

        data: {"id":"c","object":"chat.completion.chunk","model":"m2","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

        data: [DONE]

        """;
    server
        .expect(requestTo(P2 + "/chat/completions"))
        .andExpect(jsonPath("$.stream").value(true))
        .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

    List<ChatChunk> chunks = new ArrayList<>();
    Router.Routed<Void> routed = router.stream(ask("fast").withStream(true), (r, c) -> chunks.add(c));

    assertThat(routed.route().id()).isEqualTo("secondary/m2");
    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).contentDelta()).isEqualTo("Hel");
    assertThat(chunks.get(2).usage().completionTokens()).isEqualTo(2);
    server.verify();
  }
}
