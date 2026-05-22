package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for User Story 3 — a single handler bound to a wildcard subject serves a
 * family of concrete subjects, and can recover the concrete subject and matched tokens.
 */
class WildcardRoutingIntegrationTest {

  private static NatsServerRunner server;
  private static NatsMicroRuntime runtime;

  @BeforeAll
  static void startServiceAgainstEmbeddedNats() throws Exception {
    server = new NatsServerRunner();
    NatsConnectionSettings settings =
        new NatsConnectionSettings(
            server.getURI(),
            Optional.empty(),
            Optional.empty(),
            false,
            "wildcard-test",
            Duration.ofSeconds(2));
    runtime = NatsMicroRuntime.create(settings);
    runtime.register(new EchoNatsService());
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
  void wildcardHandlerRecoversANamedTokenFromTheConcreteSubject() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply =
          client.request(
              "echo.repeat.3", "ab".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).isEqualTo("ababab");
    }
  }

  @Test
  void oneWildcardHandlerServesAFamilyOfConcreteSubjects() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message two =
          client.request(
              "echo.repeat.2", "xy".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));
      Message four =
          client.request(
              "echo.repeat.4", "z".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));

      assertThat(new String(two.getData(), StandardCharsets.UTF_8)).isEqualTo("xyxy");
      assertThat(new String(four.getData(), StandardCharsets.UTF_8)).isEqualTo("zzzz");
    }
  }

  @Test
  void wildcardHandlerRecoversTheConcreteSubject() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply =
          client.request("echo.subject.orders.created", new byte[0], Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(new String(reply.getData(), StandardCharsets.UTF_8))
          .isEqualTo("echo.subject.orders.created");
    }
  }

  @Test
  void wildcardHandlerEmbedsTheMatchedTokenInTheReplyText() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply = client.request("echo.greet.kevin", new byte[0], Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).isEqualTo("Hello, kevin!");
    }
  }
}
