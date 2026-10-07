package de.sweid.gateway.agent.tools;

import de.sweid.gateway.agent.Tool;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Current date and time, optionally in a given time zone. Models cannot know this on their own. */
@Component
public class ClockTool implements Tool {

  private final Clock clock;

  public ClockTool(Clock clock) {
    this.clock = clock;
  }

  @Override
  public String name() {
    return "current_time";
  }

  @Override
  public String description() {
    return "The current date and time. Pass an IANA time zone such as Europe/Berlin; default UTC.";
  }

  @Override
  public Map<String, Object> parameters() {
    return Map.of(
        "type", "object",
        "properties", Map.of("timezone", Map.of("type", "string")));
  }

  @Override
  public Object execute(Map<String, Object> arguments) {
    Object tz = arguments.get("timezone");
    ZoneId zone = tz == null || String.valueOf(tz).isBlank() ? ZoneId.of("UTC")
        : ZoneId.of(String.valueOf(tz));
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
    return Map.of(
        "iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "weekday", now.getDayOfWeek().toString(),
        "timezone", zone.getId());
  }
}
