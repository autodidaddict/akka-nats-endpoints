package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates that a {@code @NatsMicroService}-annotated class ({@link CounterNatsService})
 * can be constructed with an Akka {@link akka.javasdk.client.ComponentClient} and that a NATS
 * handler can use it to call an Akka component ({@link CounterEntity}).
 *
 * <p>This test boots the full Akka service via {@link TestKitSupport}, so {@link Bootstrap}
 * runs for real: the runtime injects a {@code ComponentClient} into {@code Bootstrap}, which
 * passes it to {@code CounterNatsService}. The embedded NATS server is started in
 * {@link #testKitSettings()} (before the service boots) and its URL is supplied as
 * {@code nats.url} so {@code Bootstrap} connects to it.
 */
class ComponentClientHandlerIntegrationTest extends TestKitSupport {

  private static NatsServerRunner natsServer;

  @Override
  protected TestKit.Settings testKitSettings() {
    try {
      natsServer = new NatsServerRunner();
    } catch (Exception e) {
      throw new RuntimeException("failed to start embedded nats-server", e);
    }
    // Close the server only at JVM exit, so it outlives the TestKit shutdown — otherwise
    // Bootstrap.onShutdown would drain its NATS connection against an already-closed server.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    natsServer.close();
                  } catch (Exception ignored) {
                    // best-effort cleanup at JVM exit
                  }
                }));
    return TestKit.Settings.DEFAULT.withAdditionalConfig(
        "nats.url = \"" + natsServer.getURI() + "\"");
  }

  @Test
  void natsHandlerReachesAnAkkaEntityViaTheInjectedComponentClient() throws Exception {
    try (Connection client = Nats.connect(natsServer.getURI())) {
      Message first =
          client.request("counter.increment.abc", new byte[0], Duration.ofSeconds(5));
      assertThat(first).isNotNull();
      assertThat(new String(first.getData(), StandardCharsets.UTF_8))
          .isEqualTo("counter abc = 1");

      Message second =
          client.request("counter.increment.abc", new byte[0], Duration.ofSeconds(5));
      assertThat(new String(second.getData(), StandardCharsets.UTF_8))
          .isEqualTo("counter abc = 2");

      // A different entity id has its own independent state.
      Message other =
          client.request("counter.increment.xyz", new byte[0], Duration.ofSeconds(5));
      assertThat(new String(other.getData(), StandardCharsets.UTF_8))
          .isEqualTo("counter xyz = 1");
    }
  }
}
