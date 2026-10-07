package de.sweid.gateway.provider;

import de.sweid.gateway.model.ChatRequest;
import de.sweid.gateway.model.Message;

/** Rough token counts for providers that send no usage block. Four characters per token. */
public final class TokenEstimate {

  private TokenEstimate() {}

  public static long ofText(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return Math.max(1, (text.length() + 3) / 4);
  }

  public static long ofRequest(ChatRequest request) {
    long total = 0;
    for (Message m : request.messages()) {
      total += ofText(String.valueOf(m.content())) + 4;
    }
    if (request.tools() != null) {
      total += ofText(request.tools().toString());
    }
    return total;
  }
}
