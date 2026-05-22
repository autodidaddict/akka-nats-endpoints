package blog.kevinhoffman.akka.nats.internal;

import blog.kevinhoffman.akka.nats.NatsHandlerException;
import blog.kevinhoffman.akka.nats.NatsRequest;
import io.nats.client.Connection;
import io.nats.service.ServiceMessage;
import io.nats.service.ServiceMessageHandler;
import java.lang.reflect.InvocationTargetException;

/**
 * Adapts an incoming NATS micro {@link ServiceMessage} to a developer's handler method and
 * replies with the bytes the handler produced.
 *
 * <p>Error handling:
 *
 * <ul>
 *   <li>An ordinary exception thrown by a handler propagates out of {@link #onMessage}; the
 *       NATS micro framework turns it into a NATS-native error response with the generic
 *       code {@code 500} and records the error in the endpoint statistics.
 *   <li>A {@link NatsHandlerException} is answered here with the developer-chosen error code,
 *       then rethrown so the framework still records the error in its statistics.
 * </ul>
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class HandlerDispatcher implements ServiceMessageHandler {

  private final Connection connection;
  private final Object instance;
  private final EndpointDescriptor descriptor;

  public HandlerDispatcher(Connection connection, Object instance, EndpointDescriptor descriptor) {
    this.connection = connection;
    this.instance = instance;
    this.descriptor = descriptor;
  }

  @Override
  public void onMessage(ServiceMessage message) {
    byte[] data = message.getData();
    byte[] result;
    try {
      result =
          invoke(descriptor, instance, data == null ? new byte[0] : data, message.getSubject());
    } catch (NatsHandlerException e) {
      // Explicit rejection: reply with the developer-chosen code, then rethrow so the NATS
      // micro framework records the error in the endpoint statistics. The framework's own
      // error reply that follows goes to an inbox the requester has already stopped reading.
      String text = e.getMessage() == null ? "" : e.getMessage();
      message.respondStandardError(connection, text, e.code());
      throw e;
    }
    message.respond(connection, result);
  }

  /**
   * Invokes the handler method described by {@code descriptor} on {@code instance}.
   *
   * <p>Builds a {@link NatsRequest} when the handler declares one, otherwise passes the raw
   * payload. Any exception thrown by the handler is rethrown to the caller.
   */
  public static byte[] invoke(
      EndpointDescriptor descriptor, Object instance, byte[] payload, String concreteSubject) {
    Object argument;
    if (descriptor.acceptsRequestContext()) {
      SubjectMatcher.Match match =
          SubjectMatcher.match(descriptor.declaredSubject(), concreteSubject);
      argument = new NatsRequest(concreteSubject, payload, match.tokens(), match.namedTokens());
    } else {
      argument = payload;
    }
    try {
      Object result = descriptor.method().invoke(instance, argument);
      return result == null ? new byte[0] : (byte[]) result;
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to invoke handler " + descriptor.method(), e);
    }
  }
}
