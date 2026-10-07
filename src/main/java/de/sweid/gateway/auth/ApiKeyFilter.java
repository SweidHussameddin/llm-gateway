package de.sweid.gateway.auth;

import de.sweid.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-key auth for {@code /v1/**}. Keys come from configuration; the filter writes the 401
 * itself because exceptions thrown here never reach the controller advice.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

  private final Map<String, Caller> callersByKey = new HashMap<>();

  public ApiKeyFilter(GatewayProperties properties) {
    for (GatewayProperties.ApiKey k : properties.apiKeys()) {
      if (k.key() != null && !k.key().isBlank()) {
        callersByKey.put(k.key(), new Caller(k.name(), k.budgetUsd()));
      }
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    Caller caller = null;
    if (header != null && header.startsWith("Bearer ")) {
      caller = lookup(header.substring(7).trim());
    }
    if (caller == null) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"error\":{\"type\":\"authentication_error\","
                  + "\"message\":\"missing or unknown API key\"}}");
      return;
    }
    request.setAttribute(Caller.ATTRIBUTE, caller);
    chain.doFilter(request, response);
  }

  private Caller lookup(String presented) {
    byte[] a = presented.getBytes(StandardCharsets.UTF_8);
    for (Map.Entry<String, Caller> e : callersByKey.entrySet()) {
      if (MessageDigest.isEqual(a, e.getKey().getBytes(StandardCharsets.UTF_8))) {
        return e.getValue();
      }
    }
    return null;
  }
}
