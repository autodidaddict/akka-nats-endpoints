package blog.kevinhoffman.akka.nats;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The optional request context a handler method may declare as its parameter.
 *
 * <p>Handlers bound to a fixed subject can simply accept {@code byte[]}. Handlers bound to a
 * wildcard subject can accept a {@code NatsRequest} to recover the concrete subject the
 * request arrived on and the matched wildcard tokens.
 *
 * @param concreteSubject the actual subject the request arrived on
 * @param payload         the raw request payload (never {@code null}; may be zero-length)
 * @param tokens          the wildcard tokens of the concrete subject, in order
 * @param namedTokens     tokens bound to {@code {name}} labels in the declared subject
 */
public record NatsRequest(
    String concreteSubject,
    byte[] payload,
    List<String> tokens,
    Map<String, String> namedTokens) {

  public NatsRequest {
    if (concreteSubject == null || concreteSubject.isBlank()) {
      throw new IllegalArgumentException("concreteSubject must be non-blank");
    }
    payload = payload == null ? new byte[0] : payload;
    tokens = tokens == null ? List.of() : List.copyOf(tokens);
    namedTokens = namedTokens == null ? Map.of() : Map.copyOf(namedTokens);
  }

  /**
   * Returns the value of a named wildcard token.
   *
   * @throws IllegalArgumentException if no token with that name was matched
   */
  public String token(String name) {
    String value = namedTokens.get(name);
    if (value == null) {
      throw new IllegalArgumentException(
          "No token named '" + name + "' matched for subject '" + concreteSubject + "'");
    }
    return value;
  }

  /** Returns the value of a named wildcard token, or empty if it was not matched. */
  public Optional<String> tokenOpt(String name) {
    return Optional.ofNullable(namedTokens.get(name));
  }
}
