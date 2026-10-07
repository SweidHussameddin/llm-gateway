package de.sweid.gateway.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes server-sent events straight to the servlet response. Blocking writes on a virtual thread
 * keep this simple: no emitter, no async dispatch, no second filter pass.
 */
public final class SseWriter {

  private final HttpServletResponse response;
  private final ObjectMapper json;
  private PrintWriter out;

  public SseWriter(HttpServletResponse response, ObjectMapper json) {
    this.response = response;
    this.json = json;
  }

  private PrintWriter out() {
    if (out == null) {
      response.setStatus(200);
      response.setContentType("text/event-stream");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "no-cache");
      response.setHeader("X-Accel-Buffering", "no");
      try {
        out = response.getWriter();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return out;
  }

  /** Lets callers set headers before the first byte goes out. */
  public boolean started() {
    return out != null;
  }

  public void data(Object payload) {
    PrintWriter w = out();
    w.write("data: " + json.writeValueAsString(payload) + "\n\n");
    w.flush();
  }

  public void event(String name, Object payload) {
    PrintWriter w = out();
    w.write("event: " + name + "\ndata: " + json.writeValueAsString(payload) + "\n\n");
    w.flush();
  }

  public void done() {
    PrintWriter w = out();
    w.write("data: [DONE]\n\n");
    w.flush();
  }
}
