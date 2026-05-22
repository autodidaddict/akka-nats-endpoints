package blog.kevinhoffman.akka.nats.synadia;

/**
 * The inbound prompt handed to an agent's {@link PromptHandler} method.
 *
 * <p>The library applies the protocol's discrimination rule before building this record: a
 * plain-text request is promoted to a prompt ({@link #structured()} is {@code false}), and a
 * JSON request envelope has its {@code prompt} field extracted ({@link #structured()} is
 * {@code true}).
 *
 * @param prompt     the prompt text; never blank (a blank or missing prompt is rejected
 *                   before this record is built)
 * @param structured {@code true} if the caller sent a JSON envelope, {@code false} if plain
 *                   text was promoted to a prompt
 * @param subject    the concrete NATS subject the request arrived on
 */
public record PromptRequest(String prompt, boolean structured, String subject) {

  public PromptRequest {
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must be non-blank");
    }
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject must be non-blank");
    }
  }
}
