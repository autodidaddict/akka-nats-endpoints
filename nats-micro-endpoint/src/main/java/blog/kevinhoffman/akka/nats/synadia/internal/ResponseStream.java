package blog.kevinhoffman.akka.nats.synadia.internal;

import blog.kevinhoffman.akka.nats.synadia.AgentResponse;
import blog.kevinhoffman.akka.nats.synadia.QueryReply;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import io.nats.service.ServiceMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * The {@link AgentResponse} implementation backing a single prompt request. It publishes the
 * protocol reply stream directly to the request's reply subject: the mandatory {@code ack}
 * chunk, ordered {@code response} chunks, optional {@code query} chunks, and the headerless
 * zero-byte terminator.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class ResponseStream implements AgentResponse {

  private final Connection connection;
  private final String replySubject;
  private final Duration queryTimeout;
  private boolean acked;

  public ResponseStream(Connection connection, String replySubject, Duration queryTimeout) {
    this.connection = connection;
    this.replySubject = replySubject;
    this.queryTimeout = queryTimeout;
  }

  /** Emits the mandatory {@code ack} chunk, once, before any latency-inducing work. */
  void ack() {
    if (!acked) {
      connection.publish(replySubject, ProtocolJson.ackChunk());
      acked = true;
    }
  }

  @Override
  public void emit(String text) {
    connection.publish(replySubject, ProtocolJson.responseChunk(text));
  }

  @Override
  public QueryReply query(String prompt) {
    String inbox = connection.createInbox();
    Subscription subscription = connection.subscribe(inbox);
    try {
      connection.publish(
          replySubject, ProtocolJson.queryChunk(UUID.randomUUID().toString(), inbox, prompt));
      Message reply = subscription.nextMessage(queryTimeout);
      if (reply == null) {
        return QueryReply.noReply();
      }
      return QueryReply.of(new String(reply.getData(), StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return QueryReply.noReply();
    } finally {
      subscription.unsubscribe();
    }
  }

  /** Publishes an error-headed message carrying the protocol §9 error code and message. */
  void error(int code, String message) {
    Headers headers = new Headers();
    headers.add(ServiceMessage.NATS_SERVICE_ERROR_CODE, String.valueOf(code));
    headers.add(ServiceMessage.NATS_SERVICE_ERROR, message == null ? "" : message);
    connection.publish(replySubject, headers, ProtocolJson.errorBody(code, message));
  }

  /** Publishes the headerless zero-byte stream terminator. */
  void terminate() {
    connection.publish(replySubject, new byte[0]);
  }
}
