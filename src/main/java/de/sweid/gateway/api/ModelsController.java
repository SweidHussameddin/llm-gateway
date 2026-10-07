package de.sweid.gateway.api;

import de.sweid.gateway.config.GatewayProperties.ModelAlias;
import de.sweid.gateway.config.GatewayProperties.Route;
import de.sweid.gateway.provider.ProviderRegistry;
import de.sweid.gateway.routing.CircuitBreaker;
import de.sweid.gateway.routing.Router;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Model aliases with their routes and live breaker state. OpenAI list shape, extra fields. */
@RestController
public class ModelsController {

  private final Router router;
  private final ProviderRegistry providers;

  public ModelsController(Router router, ProviderRegistry providers) {
    this.router = router;
    this.providers = providers;
  }

  @GetMapping("/v1/models")
  public Map<String, Object> models() {
    List<Map<String, Object>> data = new ArrayList<>();
    for (ModelAlias alias : router.aliases()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", alias.alias());
      m.put("object", "model");
      m.put("description", alias.description());
      List<Map<String, Object>> routes = new ArrayList<>();
      for (Route r : alias.routes()) {
        CircuitBreaker b = router.breaker(r);
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("provider", r.provider());
        rm.put("model", r.model());
        rm.put("configured", providers.usable(r.provider()));
        rm.put("breaker", b.state().name());
        rm.put("failures", b.failures());
        rm.put("input_usd_per_mtok", r.inputUsdPerMtok());
        rm.put("output_usd_per_mtok", r.outputUsdPerMtok());
        routes.add(rm);
      }
      m.put("routes", routes);
      data.add(m);
    }
    return Map.of("object", "list", "data", data);
  }
}
