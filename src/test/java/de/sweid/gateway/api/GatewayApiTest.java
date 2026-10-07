package de.sweid.gateway.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sweid.gateway.cost.UsageLedger;
import de.sweid.gateway.cost.UsageRecord;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract: auth, budgets, validation and error shape. No provider is ever called. */
@SpringBootTest(
    properties = {
      "gateway.ledger-file=target/test-usage-${random.uuid}.jsonl",
      "gateway.api-keys[0].key=k-demo",
      "gateway.api-keys[0].name=demo",
      "gateway.api-keys[0].budget-usd=1.00",
      "gateway.api-keys[1].key=k-trial",
      "gateway.api-keys[1].name=trial",
      "gateway.api-keys[1].budget-usd=0.001",
      "gateway.providers.openrouter.api-key=",
      "gateway.providers.anthropic.api-key="
    })
@AutoConfigureMockMvc
class GatewayApiTest {

  @Autowired MockMvc mvc;
  @Autowired UsageLedger ledger;

  @Test
  void rejectsMissingAndUnknownKeys() throws Exception {
    mvc.perform(get("/v1/models"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.type").value("authentication_error"));
    mvc.perform(get("/v1/models").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listsModelsWithRoutesAndBreakerState() throws Exception {
    mvc.perform(get("/v1/models").header("Authorization", "Bearer k-demo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("fast"))
        .andExpect(jsonPath("$.data[0].routes[0].provider").value("local"))
        .andExpect(jsonPath("$.data[0].routes[0].breaker").value("CLOSED"))
        .andExpect(jsonPath("$.data[0].routes[1].configured").value(false));
  }

  @Test
  void unknownModelIs404InOpenAiErrorShape() throws Exception {
    mvc.perform(
            post("/v1/chat/completions")
                .header("Authorization", "Bearer k-demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"nope\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type").value("model_not_found"));
  }

  @Test
  void aliasWithoutConfiguredProviderIs503() throws Exception {
    mvc.perform(
            post("/v1/chat/completions")
                .header("Authorization", "Bearer k-demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"claude\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("claude")));
  }

  @Test
  void validatesRequestBody() throws Exception {
    mvc.perform(
            post("/v1/chat/completions")
                .header("Authorization", "Bearer k-demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"fast\",\"messages\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
  }

  @Test
  void blocksKeyOverBudgetWith402() throws Exception {
    ledger.record(
        new UsageRecord(
            Instant.now(), "trial", "chat", "fast", "local/x", 10, 10, new BigDecimal("0.002"),
            5, false));

    mvc.perform(
            post("/v1/chat/completions")
                .header("Authorization", "Bearer k-trial")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"fast\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.error.type").value("budget_exceeded"));

    mvc.perform(get("/v1/usage").header("Authorization", "Bearer k-trial"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spent_usd").value(0.002))
        .andExpect(jsonPath("$.recent[0].route").value("local/x"));
  }

  @Test
  void listsToolsAndRejectsUnknownToolNames() throws Exception {
    mvc.perform(get("/v1/tools").header("Authorization", "Bearer k-demo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].function.name").value(org.hamcrest.Matchers.hasItem("lookup_order")));
    mvc.perform(
            post("/v1/agent/runs")
                .header("Authorization", "Bearer k-demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"fast\",\"input\":\"hi\",\"tools\":[\"teleport\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(ChatController.COST_HEADER));
  }
}
