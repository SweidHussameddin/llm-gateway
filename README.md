# llm-gateway

A small LLM gateway for teams that want **one endpoint in their own backend** instead of provider SDKs scattered across services. Clients speak the OpenAI chat-completions API; the gateway picks a route, fails over when a provider is down, prices every request, enforces per-key budgets and runs tool-calling agents against server-side tools.

Spring Boot 4, Java 25, no database, no Redis, no message broker. One process, a YAML file and a JSON-lines ledger.

```
client ──POST /v1/chat/completions──▶ gateway ──▶ local  (Ollama, llama.cpp, vLLM)
        ──POST /v1/agent/runs───────▶   │     ──▶ OpenRouter / OpenAI / Groq / Mistral …
        ──GET  /v1/usage────────────▶   │     ──▶ Anthropic (native Messages API, translated)
                                        └── breaker per route · cost ledger · budgets · tools
```

## What it does

| Capability | How |
|---|---|
| One API shape | OpenAI chat completions in and out, sync or SSE streaming. Works with any OpenAI SDK by changing `base_url`. |
| Model aliases | Clients ask for `fast` or `smart`; YAML maps an alias to an ordered list of provider/model routes with prices. |
| Failover | Transport errors, 429, 5xx, bad keys or model names move on to the next route. Client errors (400) are returned as is. Streams fail over only before the first chunk reached the client. |
| Circuit breaker per route | N consecutive failures open the route for a cooldown, then one probe call decides. State is visible in `GET /v1/models`. |
| Providers | `openai-compatible` (OpenRouter, Ollama, llama.cpp, vLLM, Groq …) and a native `anthropic` adapter that rewrites messages, tools, tool results and stream events in both directions. |
| Cost ledger | Tokens × price per route, written to `data/usage.jsonl` and summed per key. Missing usage blocks are estimated and flagged. |
| Budgets | Each API key has a USD ceiling. Over budget → `402 budget_exceeded`. Spend survives restarts. |
| Agent runs | `POST /v1/agent/runs` loops model ↔ tools until the model answers or the step cap hits. Tools are plain Spring beans. Progress streams as SSE events. |
| Console | `/` is a small page to try all of it: chat, agent steps, breaker states, budget bar, ledger. |

## Run it

Requirements: Java 25, Maven. For the local route, [Ollama](https://ollama.com) with a tool-capable model (`ollama pull qwen3:8b`). For hosted routes an OpenRouter key; the free models listed in `application.yml` cost nothing.

```bash
cp .env.example .env            # put OPENROUTER_API_KEY in there; .env is git-ignored
mvn spring-boot:run             # http://localhost:8090
```

```bash
# sync
curl -s localhost:8090/v1/chat/completions \
  -H "Authorization: Bearer demo-key" -H "Content-Type: application/json" \
  -d '{"model":"fast","messages":[{"role":"user","content":"One sentence on circuit breakers."}]}'

# streaming
curl -N localhost:8090/v1/chat/completions \
  -H "Authorization: Bearer demo-key" -H "Content-Type: application/json" \
  -d '{"model":"fast","stream":true,"messages":[{"role":"user","content":"Count to five."}]}'

# agent run with server-side tools
curl -s localhost:8090/v1/agent/runs \
  -H "Authorization: Bearer demo-key" -H "Content-Type: application/json" \
  -d '{"model":"fast","input":"Has order 1042 shipped? What is the total in USD?"}'

# what did that cost
curl -s localhost:8090/v1/usage -H "Authorization: Bearer demo-key"
```

Every response carries `X-Gateway-Route` (which provider/model answered) and `X-Gateway-Cost-Usd`; the `usage` block includes `route`, `cost_usd` and `estimated`.

With the OpenAI Python SDK:

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8090/v1", api_key="demo-key")
r = client.chat.completions.create(model="fast", messages=[{"role": "user", "content": "hi"}])
```

## Configuration

Everything lives in `src/main/resources/application.yml`. Secrets come from `.env` or the environment.

```yaml
gateway:
  api-keys:
    - key: ${GATEWAY_KEY_DEMO:demo-key}
      name: demo
      budget-usd: 5.00
  providers:
    local:      { type: openai-compatible, base-url: http://localhost:11434/v1, api-key: ollama }
    openrouter: { type: openai-compatible, base-url: https://openrouter.ai/api/v1, api-key: ${OPENROUTER_API_KEY:} }
    anthropic:  { type: anthropic, base-url: https://api.anthropic.com, api-key: ${ANTHROPIC_API_KEY:} }
  breaker: { failure-threshold: 3, open-seconds: 30 }
  models:
    - alias: fast
      routes:
        - { provider: local,      model: qwen3:8b,                               input-usd-per-mtok: 0, output-usd-per-mtok: 0 }
        - { provider: openrouter, model: nvidia/nemotron-3-super-120b-a12b:free, input-usd-per-mtok: 0, output-usd-per-mtok: 0 }
```

Adding a provider that speaks the OpenAI API is a YAML entry, no code. Adding a tool is one class:

```java
@Component
public class WeatherTool implements Tool {
  public String name() { return "get_weather"; }
  public String description() { return "Current weather for a city"; }
  public Map<String, Object> parameters() { return Map.of("type", "object",
      "properties", Map.of("city", Map.of("type", "string")), "required", List.of("city")); }
  public Object execute(Map<String, Object> args) { return weatherClient.now((String) args.get("city")); }
}
```

## Endpoints

All under `Authorization: Bearer <key>`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI chat completions, `stream: true` for SSE |
| `POST` | `/v1/agent/runs` | `{model, input, instructions?, tools?, max_steps?, stream?}` → answer plus every tool step |
| `GET` | `/v1/models` | Aliases, routes, prices, breaker state |
| `GET` | `/v1/tools` | Tool definitions the agent can use |
| `GET` | `/v1/usage` | Spend, request count, per-route totals, recent requests for the calling key |
| `GET` | `/actuator/health` | Liveness, no auth |

Errors use the OpenAI shape, `{"error": {"type", "message", "attempts"?}}`, so SDKs raise the right exception classes. `attempts` lists what each route answered when all of them failed.

## Design notes

- **Why not an SDK per provider.** Provider SDKs pull in HTTP stacks, retries and auth logic you now have to tune three times. One `RestClient` on the JDK `HttpClient` (HTTP/2, virtual threads) and one `ProviderClient` interface keep the surface small, and the Anthropic adapter shows what a non-OpenAI provider costs: one class, fully unit-tested, no network.
- **Breaker state is per process on purpose.** Each instance learns on its own which routes are down; that is enough for most deployments and keeps Redis out of the picture. The ledger file is the same trade-off. Both have a one-class seam if you outgrow them.
- **Streams and failover.** A stream can be retried on another route only until the first byte reached the client. After that the gateway sends an `error` event and ends the stream instead of silently restarting the answer.
- **Costs are config, not a price database.** Prices live next to the route that incurs them, so a price change is a diff in review, not a migration.
- **Thinking models.** Models that reason before answering (qwen3, o-series) spend `max_tokens` on thoughts. Leave `max_tokens` unset or generous for them.

## Development

```bash
mvn verify                    # checkstyle (Google style) + tests, no network needed
mvn test -Dtest=RouterTest    # one class
```

Tests cover failover and breaker behaviour against a mock HTTP server, both provider adapters including streaming, the agent loop with a mocked router, the ledger, and the HTTP contract (401, 402, 404, 400, error shape).

## What is deliberately out

Multi-tenant auth beyond static keys, persistent storage beyond the ledger file, embeddings and image endpoints, prompt caching, and retries within a single route. Each is a bounded addition; none is needed to show the shape.
