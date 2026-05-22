package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import blog.kevinhoffman.akka.nats.synadia.PromptHandler;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgent;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import io.nats.client.support.JsonParser;
import io.nats.client.support.JsonValue;
import io.nats.client.support.JsonValueUtils;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for User Story 2 — a running agent publishes liveness beacons and serves a
 * status endpoint, with no developer code. Runs against an embedded {@code nats-server}.
 */
class SynadiaLivenessIntegrationTest {

  private static NatsServerRunner server;
  private static NatsMicroRuntime runtime;

  /** Session-aware agent with a fast heartbeat so the test does not wait long. */
  @SynadiaAgent(
      agent = "beat",
      owner = "acme",
      name = "beat-1",
      heartbeat = 1,
      session = "claude-code")
  public static class HeartbeatAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  /** Session-unaware agent — its beacons and status replies must omit the session field. */
  @SynadiaAgent(agent = "quiet", owner = "acme", name = "quiet-1", heartbeat = 1)
  public static class NoSessionAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  /** Agent with a distinct identity, registered only by the short-lived runtime. */
  @SynadiaAgent(agent = "gone", owner = "acme", name = "gone-1", heartbeat = 1)
  public static class DistinctBeatAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @BeforeAll
  static void startServiceAgainstEmbeddedNats() throws Exception {
    server = new NatsServerRunner();
    runtime = NatsMicroRuntime.create(settings("liveness-it"));
    runtime.register(new HeartbeatAgent());
    runtime.register(new NoSessionAgent());
    runtime.start();
  }

  @AfterAll
  static void stopService() throws Exception {
    if (runtime != null) {
      runtime.stop();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void publishesHeartbeatBeaconsCarryingTheProtocolFields() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Subscription sub = client.subscribe("agents.hb.beat.acme.beat-1");
      Message beacon = sub.nextMessage(Duration.ofSeconds(3));

      assertThat(beacon).isNotNull();
      JsonValue payload = JsonParser.parseUnchecked(beacon.getData());
      assertThat(JsonValueUtils.readString(payload, "agent")).isEqualTo("beat");
      assertThat(JsonValueUtils.readString(payload, "owner")).isEqualTo("acme");
      assertThat(JsonValueUtils.readString(payload, "session")).isEqualTo("claude-code");
      assertThat(JsonValueUtils.readString(payload, "instance_id")).isNotBlank();
      assertThat(JsonValueUtils.readString(payload, "ts")).isNotBlank();
      assertThat(JsonValueUtils.readInteger(payload, "interval_s")).isEqualTo(1);
    }
  }

  @Test
  void omitsTheSessionFromBeaconsOfASessionUnawareAgent() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Subscription sub = client.subscribe("agents.hb.quiet.acme.quiet-1");
      Message beacon = sub.nextMessage(Duration.ofSeconds(3));

      assertThat(beacon).isNotNull();
      JsonValue payload = JsonParser.parseUnchecked(beacon.getData());
      assertThat(payload.map).doesNotContainKey("session");
    }
  }

  @Test
  void servesTheStatusEndpointWithAnEquivalentLivenessPayload() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply =
          client.request("agents.status.beat.acme.beat-1", new byte[0], Duration.ofSeconds(3));

      assertThat(reply).isNotNull();
      JsonValue payload = JsonParser.parseUnchecked(reply.getData());
      assertThat(JsonValueUtils.readString(payload, "agent")).isEqualTo("beat");
      assertThat(JsonValueUtils.readString(payload, "owner")).isEqualTo("acme");
      assertThat(JsonValueUtils.readString(payload, "session")).isEqualTo("claude-code");
      assertThat(JsonValueUtils.readString(payload, "instance_id")).isNotBlank();
      assertThat(JsonValueUtils.readInteger(payload, "interval_s")).isEqualTo(1);
    }
  }

  @Test
  void stopsPublishingBeaconsAfterTheAgentShutsDown() throws Exception {
    // A distinct identity registered only here, so the shared runtime never beats this subject.
    NatsMicroRuntime shortLived = NatsMicroRuntime.create(settings("liveness-it-stop"));
    shortLived.register(new DistinctBeatAgent());
    shortLived.start();
    try (Connection client = Nats.connect(server.getURI())) {
      Subscription sub = client.subscribe("agents.hb.gone.acme.gone-1");
      assertThat(sub.nextMessage(Duration.ofSeconds(3))).isNotNull(); // a beacon while running
      sub.unsubscribe();

      shortLived.stop();
      Thread.sleep(1200); // let any in-flight beacon settle

      Subscription quiet = client.subscribe("agents.hb.gone.acme.gone-1");
      assertThat(quiet.nextMessage(Duration.ofSeconds(2))).isNull(); // no more beacons
    }
  }

  private static NatsConnectionSettings settings(String name) {
    return new NatsConnectionSettings(
        server.getURI(), Optional.empty(), Optional.empty(), false, name, Duration.ofSeconds(2));
  }
}
