package blog.kevinhoffman.akka.nats.synadia;

/**
 * Thrown by an agent's {@link PromptHandler} method to explicitly reject a request with a
 * developer-chosen Synadia Agent Protocol status code and message.
 *
 * <p>When a handler throws this exception the caller receives a protocol error response
 * carrying {@link #code()} and the exception message (for example {@code 403} forbidden or
 * {@code 429} rate limited). Any other exception thrown by a handler is mapped to the generic
 * internal-error code {@code 500}.
 *
 * <p>It is the agent-side analogue of {@link blog.kevinhoffman.akka.nats.NatsHandlerException}.
 */
public class SynadiaAgentException extends RuntimeException {

  private final int code;

  /**
   * @param code    the protocol status code to return to the caller (the §9 taxonomy: 400,
   *                401, 403, 404, 409, 429, 500)
   * @param message the human-readable error message
   */
  public SynadiaAgentException(int code, String message) {
    super(message);
    this.code = code;
  }

  /** The protocol status code returned to the caller. */
  public int code() {
    return code;
  }
}
