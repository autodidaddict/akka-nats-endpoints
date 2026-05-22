package blog.kevinhoffman.akka.nats.synadia.internal;

/**
 * An internal failure outcome carrying a Synadia Agent Protocol §9 status code and a
 * human-readable message. Raised by request validation and decoding; translated by
 * {@link PromptMessageHandler} into a NATS-native error reply.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
final class ProtocolError extends RuntimeException {

  /** The protocol §9 status code (e.g. 400). */
  final int code;

  ProtocolError(int code, String message) {
    super(message);
    this.code = code;
  }
}
