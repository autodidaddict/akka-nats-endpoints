package blog.kevinhoffman.akka.nats.synadia.internal;

import io.nats.client.Connection;
import java.time.Instant;
import java.util.UUID;

/**
 * Publishes liveness beacons for one agent instance on the protocol-fixed heartbeat subject,
 * and supplies the same payload to the {@code status} endpoint.
 *
 * <p>Runs as a scheduled task: each {@link #run()} publishes one beacon. The
 * {@code instance_id} is a per-process UUID generated once at construction; the {@code ts} is
 * the current UTC instant per beacon.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class HeartbeatPublisher implements Runnable {

  private final Connection connection;
  private final AgentDescriptor descriptor;
  private final String heartbeatSubject;
  private final String instanceId;

  public HeartbeatPublisher(Connection connection, AgentDescriptor descriptor) {
    this.connection = connection;
    this.descriptor = descriptor;
    this.heartbeatSubject =
        SynadiaSubjects.heartbeat(descriptor.agent(), descriptor.owner(), descriptor.name());
    this.instanceId = UUID.randomUUID().toString();
  }

  @Override
  public void run() {
    try {
      connection.publish(heartbeatSubject, payload());
    } catch (RuntimeException ignored) {
      // Best-effort beacon — a failed publish must not kill the scheduled task.
    }
  }

  /** Builds the current liveness payload, shared by the beacon and the status endpoint. */
  public byte[] payload() {
    return ProtocolJson.beacon(
        descriptor.agent(),
        descriptor.owner(),
        descriptor.session(),
        instanceId,
        Instant.now().toString(),
        descriptor.heartbeatSeconds());
  }
}
