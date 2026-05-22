package blog.kevinhoffman.akka.nats;

/**
 * Thrown by a handler to explicitly reject a request with a developer-chosen NATS service
 * error code and message.
 *
 * <p>When a handler throws this exception the caller receives a NATS-native error response
 * carrying {@link #code()} and the exception message. Any other exception thrown by a handler
 * is mapped to the generic error code {@code 500}.
 *
 * <p>The error code is an {@code int}, matching the NATS micro-service protocol's
 * {@code Nats-Service-Error-Code} field.
 */
public class NatsHandlerException extends RuntimeException {

  private final int code;

  /**
   * @param code    the NATS service error code to return to the caller
   * @param message the human-readable error message
   */
  public NatsHandlerException(int code, String message) {
    super(message);
    this.code = code;
  }

  /** The NATS service error code returned to the caller. */
  public int code() {
    return code;
  }
}
