package blog.kevinhoffman.akka.nats.synadia.internal;

import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgentException;
import io.nats.client.Connection;
import io.nats.service.ServiceMessage;
import io.nats.service.ServiceMessageHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Adapts an incoming prompt-endpoint {@link ServiceMessage} to an agent's
 * {@code @PromptHandler} method and frames the result as a protocol reply stream.
 *
 * <p>The flow per request: enforce the declared max payload, decode via {@link RequestDecoder},
 * emit the {@code ack} chunk, invoke the developer's handler (single-shot or streaming), then
 * emit the stream terminator. A {@link ProtocolError} (validation), a
 * {@link SynadiaAgentException} (explicit rejection) or any other exception (internal error,
 * code {@code 500}) is written as a NATS error-headed reply followed by the terminator, so
 * the caller's stream always ends cleanly.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class PromptMessageHandler implements ServiceMessageHandler {

  private final Connection connection;
  private final AgentDescriptor descriptor;
  private final long maxPayloadBytes;
  private final Duration queryTimeout;

  public PromptMessageHandler(Connection connection, AgentDescriptor descriptor) {
    this.connection = connection;
    this.descriptor = descriptor;
    this.maxPayloadBytes = descriptor.maxPayloadBytes();
    this.queryTimeout = Duration.ofSeconds(descriptor.queryTimeoutSeconds());
  }

  @Override
  public void onMessage(ServiceMessage message) {
    String replyTo = message.getReplyTo();
    if (replyTo == null || replyTo.isBlank()) {
      // No reply subject — the caller is not expecting a response; nothing to do.
      return;
    }
    ResponseStream stream = new ResponseStream(connection, replyTo, queryTimeout);
    byte[] data = message.getData() == null ? new byte[0] : message.getData();
    try {
      if (data.length > maxPayloadBytes) {
        throw new ProtocolError(
            400, "request payload exceeds the declared max_payload of " + descriptor.maxPayload());
      }
      PromptRequest request = RequestDecoder.decode(data, message.getSubject());
      stream.ack();
      invokeHandler(request, stream);
      stream.terminate();
    } catch (ProtocolError e) {
      stream.error(e.code, e.getMessage());
      stream.terminate();
    } catch (SynadiaAgentException e) {
      stream.error(e.code(), e.getMessage() == null ? "" : e.getMessage());
      stream.terminate();
    } catch (RuntimeException e) {
      stream.error(500, "internal agent error");
      stream.terminate();
    }
  }

  private void invokeHandler(PromptRequest request, ResponseStream stream) {
    Method method = descriptor.promptMethod();
    try {
      switch (descriptor.promptForm()) {
        case STRING_IN -> {
          Object result = method.invoke(descriptor.instance(), request.prompt());
          stream.emit(result == null ? "" : (String) result);
        }
        case REQUEST_IN -> {
          Object result = method.invoke(descriptor.instance(), request);
          stream.emit(result == null ? "" : (String) result);
        }
        case STREAMING -> method.invoke(descriptor.instance(), request, stream);
      }
    } catch (IllegalAccessException e) {
      throw new RuntimeException("unable to invoke @PromptHandler " + method, e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof SynadiaAgentException synadiaAgentException) {
        throw synadiaAgentException;
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    }
  }
}
