package de.sweid.gateway.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Minimal server-sent-events reader. Calls {@code onEvent(eventName, data)} per event; multi-line
 * data is joined with newlines as the spec says. Blocking read, fine on a virtual thread.
 */
final class SseReader {

  private SseReader() {}

  /** Returns the number of data events delivered. */
  static int read(InputStream body, BiConsumer<String, String> onEvent) {
    int delivered = 0;
    String event = null;
    StringBuilder data = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          if (!data.isEmpty()) {
            onEvent.accept(event, data.toString());
            delivered++;
          }
          event = null;
          data.setLength(0);
        } else if (line.startsWith("event:")) {
          event = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          if (!data.isEmpty()) {
            data.append('\n');
          }
          data.append(line.substring(5).stripLeading());
        }
      }
      if (!data.isEmpty()) {
        onEvent.accept(event, data.toString());
        delivered++;
      }
    } catch (IOException e) {
      throw new ProviderException("stream read failed: " + e.getMessage(), e);
    }
    return delivered;
  }
}
