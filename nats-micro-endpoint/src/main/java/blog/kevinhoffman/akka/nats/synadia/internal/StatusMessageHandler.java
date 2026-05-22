package blog.kevinhoffman.akka.nats.synadia.internal;

import io.nats.client.Connection;
import io.nats.service.ServiceMessage;
import io.nats.service.ServiceMessageHandler;

/**
 * Serves the agent's {@code status} endpoint, replying on demand with a liveness payload
 * equivalent to the heartbeat beacon so callers can bootstrap liveness tracking without
 * waiting a full interval.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class StatusMessageHandler implements ServiceMessageHandler {

  private final Connection connection;
  private final HeartbeatPublisher heartbeat;

  public StatusMessageHandler(Connection connection, HeartbeatPublisher heartbeat) {
    this.connection = connection;
    this.heartbeat = heartbeat;
  }

  @Override
  public void onMessage(ServiceMessage message) {
    message.respond(connection, heartbeat.payload());
  }
}
