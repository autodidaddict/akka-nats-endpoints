package blog.kevinhoffman.akka.nats.synadia.internal;

/**
 * Derives the Synadia Agent Protocol subjects from an agent identity and validates that
 * identity elements are safe to embed in a NATS subject.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class SynadiaSubjects {

  private SynadiaSubjects() {}

  /** The protocol's micro-service discovery name and shared queue group. */
  public static final String SERVICE_NAME = "agents";

  /** The protocol version this library implements. */
  public static final String PROTOCOL_VERSION = "0.3";

  /** The {@code prompt} endpoint subject: {@code agents.prompt.{agent}.{owner}.{name}}. */
  public static String prompt(String agent, String owner, String name) {
    return join("prompt", agent, owner, name);
  }

  /** The {@code status} endpoint subject: {@code agents.status.{agent}.{owner}.{name}}. */
  public static String status(String agent, String owner, String name) {
    return join("status", agent, owner, name);
  }

  /** The protocol-fixed heartbeat subject: {@code agents.hb.{agent}.{owner}.{name}}. */
  public static String heartbeat(String agent, String owner, String name) {
    return join("hb", agent, owner, name);
  }

  private static String join(String verb, String agent, String owner, String name) {
    return SERVICE_NAME + "." + verb + "." + agent + "." + owner + "." + name;
  }

  /**
   * Validates that an identity element is non-blank and safe to embed as a single NATS
   * subject token.
   *
   * @param label a human-readable label for the value, used in the error message
   * @param value the identity element to validate
   * @throws IllegalArgumentException if the value is blank or contains {@code .}, {@code *},
   *     {@code >} or whitespace
   */
  public static void validateToken(String label, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must be non-blank");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '.' || c == '*' || c == '>' || Character.isWhitespace(c)) {
        throw new IllegalArgumentException(
            label + " '" + value + "' must not contain '.', '*', '>' or whitespace");
      }
    }
  }
}
