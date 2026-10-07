package de.sweid.gateway.agent.tools;

import de.sweid.gateway.agent.Tool;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Evaluates arithmetic with + - * / and parentheses. No scripting engine, nothing to escape. */
@Component
public class CalculatorTool implements Tool {

  @Override
  public String name() {
    return "calculate";
  }

  @Override
  public String description() {
    return "Evaluate an arithmetic expression such as (4890 * 0.19) + 12.5. "
        + "Supports + - * / and parentheses.";
  }

  @Override
  public Map<String, Object> parameters() {
    return Map.of(
        "type", "object",
        "properties", Map.of("expression", Map.of("type", "string")),
        "required", List.of("expression"));
  }

  @Override
  public Object execute(Map<String, Object> arguments) {
    String expr = String.valueOf(arguments.get("expression"));
    double value = new Parser(expr).parse();
    return Map.of("expression", expr, "result", value);
  }

  /** Recursive-descent parser for the four operations. */
  static final class Parser {
    private final String src;
    private int pos;

    Parser(String expression) {
      this.src = expression.replace(",", "");
    }

    double parse() {
      double v = expression();
      skipSpaces();
      if (pos < src.length()) {
        throw new IllegalArgumentException("unexpected '" + src.charAt(pos) + "' at " + pos);
      }
      return v;
    }

    private double expression() {
      double v = term();
      while (true) {
        skipSpaces();
        if (match('+')) {
          v += term();
        } else if (match('-')) {
          v -= term();
        } else {
          return v;
        }
      }
    }

    private double term() {
      double v = factor();
      while (true) {
        skipSpaces();
        if (match('*')) {
          v *= factor();
        } else if (match('/')) {
          double d = factor();
          if (d == 0) {
            throw new IllegalArgumentException("division by zero");
          }
          v /= d;
        } else {
          return v;
        }
      }
    }

    private double factor() {
      skipSpaces();
      if (match('-')) {
        return -factor();
      }
      if (match('(')) {
        double v = expression();
        skipSpaces();
        if (!match(')')) {
          throw new IllegalArgumentException("missing ')'");
        }
        return v;
      }
      int start = pos;
      while (pos < src.length()
          && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
        pos++;
      }
      if (start == pos) {
        throw new IllegalArgumentException("number expected at " + pos);
      }
      return Double.parseDouble(src.substring(start, pos));
    }

    private boolean match(char c) {
      if (pos < src.length() && src.charAt(pos) == c) {
        pos++;
        return true;
      }
      return false;
    }

    private void skipSpaces() {
      while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
        pos++;
      }
    }
  }
}
