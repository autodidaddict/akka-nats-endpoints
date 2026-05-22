package blog.kevinhoffman.akka.nats.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches a concrete NATS subject against a declared subject pattern and extracts the tokens
 * covered by wildcards.
 *
 * <p>A declared pattern may contain standard NATS wildcards ({@code *} for one token, a
 * trailing {@code >} for the remaining tokens) and {@code {name}} labels — a named single-token
 * wildcard. NATS itself routes the message; this matcher only recovers the token values.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class SubjectMatcher {

  private SubjectMatcher() {}

  /** The tokens extracted from a concrete subject by a declared pattern. */
  public record Match(List<String> tokens, Map<String, String> namedTokens) {}

  /**
   * Translates a declared subject into the subject to subscribe to on NATS by replacing every
   * {@code {name}} label with a {@code *} wildcard.
   */
  public static String toNatsSubject(String declaredSubject) {
    String[] declared = declaredSubject.split("\\.", -1);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < declared.length; i++) {
      if (i > 0) {
        result.append('.');
      }
      result.append(isNamedToken(declared[i]) ? "*" : declared[i]);
    }
    return result.toString();
  }

  /**
   * Extracts the wildcard tokens of {@code concreteSubject} as covered by {@code declaredSubject}.
   *
   * <p>{@code declaredSubject} is the pattern as written by the developer (it may contain
   * {@code {name}} labels). {@code concreteSubject} is the subject the request arrived on.
   */
  public static Match match(String declaredSubject, String concreteSubject) {
    String[] declared = declaredSubject.split("\\.", -1);
    String[] concrete = concreteSubject.split("\\.", -1);
    List<String> tokens = new ArrayList<>();
    Map<String, String> named = new LinkedHashMap<>();

    for (int i = 0; i < declared.length; i++) {
      String token = declared[i];
      if (token.equals(">")) {
        tokens.add(joinFrom(concrete, i));
        break;
      }
      String concreteToken = i < concrete.length ? concrete[i] : "";
      if (token.equals("*")) {
        tokens.add(concreteToken);
      } else if (isNamedToken(token)) {
        tokens.add(concreteToken);
        named.put(token.substring(1, token.length() - 1), concreteToken);
      }
      // literal tokens contribute nothing
    }
    return new Match(List.copyOf(tokens), Map.copyOf(named));
  }

  private static boolean isNamedToken(String token) {
    return token.length() > 2 && token.startsWith("{") && token.endsWith("}");
  }

  private static String joinFrom(String[] tokens, int from) {
    StringBuilder result = new StringBuilder();
    for (int j = from; j < tokens.length; j++) {
      if (j > from) {
        result.append('.');
      }
      result.append(tokens[j]);
    }
    return result.toString();
  }
}
