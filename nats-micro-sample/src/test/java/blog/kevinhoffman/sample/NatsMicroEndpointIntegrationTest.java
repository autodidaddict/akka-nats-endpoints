package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.service.Discovery;
import io.nats.service.Endpoint;
import io.nats.service.EndpointStats;
import io.nats.service.InfoResponse;
import io.nats.service.PingResponse;
import io.nats.service.ServiceMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for User Story 1 — declare a class as a NATS micro-service and serve
 * requests. Runs against an embedded {@code nats-server}.
 */
class NatsMicroEndpointIntegrationTest {

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
            "integration-test",
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
  void requestReturnsExactlyTheBytesTheHandlerProduced() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply =
          client.request(
              "echo.upper", "hello".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).isEqualTo("HELLO");
    }
  }

  @Test
  void handlesAnEmptyRequestPayload() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply = client.request("echo.upper", new byte[0], Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(reply.getData()).isEmpty();
    }
  }

  @Test
  void serviceIsDiscoverableWithItsDeclaredMetadata() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<InfoResponse> infos = new Discovery(client).info("echo-service");

      assertThat(infos).hasSize(1);
      InfoResponse info = infos.get(0);
      assertThat(info.getName()).isEqualTo("echo-service");
      assertThat(info.getVersion()).isEqualTo("1.0.0");
      assertThat(info.getEndpoints()).extracting(Endpoint::getSubject).contains("echo.upper");
      assertThat(info.getEndpoints())
          .allSatisfy(endpoint -> assertThat(endpoint.getQueueGroup()).isEqualTo("echo-service"));
    }
  }

  @Test
  void pingRespondsFromTheRunningInstance() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<PingResponse> pings = new Discovery(client).ping("echo-service");

      assertThat(pings).isNotEmpty();
      assertThat(pings).extracting(PingResponse::getName).contains("echo-service");
    }
  }

  @Test
  void statsReflectRequestCounts() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Discovery discovery = new Discovery(client);
      long before = totalRequests(discovery);

      for (int i = 0; i < 3; i++) {
        client.request("echo.upper", "x".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));
      }

      assertThat(totalRequests(discovery)).isGreaterThanOrEqualTo(before + 3);
    }
  }

  @Test
  void aThrownExceptionYieldsAGenericNatsErrorResponse() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply = client.request("echo.fail", new byte[0], Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(reply.hasHeaders()).isTrue();
      assertThat(reply.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR_CODE))
          .isEqualTo("500");
      assertThat(reply.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR)).isNotBlank();
    }
  }

  @Test
  void anExplicitRejectionYieldsTheDeveloperChosenErrorCode() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message reply = client.request("echo.reject", new byte[0], Duration.ofSeconds(2));

      assertThat(reply).isNotNull();
      assertThat(reply.hasHeaders()).isTrue();
      assertThat(reply.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR_CODE))
          .isEqualTo("400");
      assertThat(reply.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR))
          .contains("rejected");
    }
  }

  @Test
  void failuresIncrementTheErrorCounter() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Discovery discovery = new Discovery(client);
      long before = totalErrors(discovery);

      client.request("echo.fail", new byte[0], Duration.ofSeconds(2));
      client.request("echo.reject", new byte[0], Duration.ofSeconds(2));

      assertThat(totalErrors(discovery)).isGreaterThanOrEqualTo(before + 2);
    }
  }

  private static long totalRequests(Discovery discovery) {
    return discovery.stats("echo-service").stream()
        .flatMap(stats -> stats.getEndpointStatsList().stream())
        .mapToLong(EndpointStats::getNumRequests)
        .sum();
  }

  private static long totalErrors(Discovery discovery) {
    return discovery.stats("echo-service").stream()
        .flatMap(stats -> stats.getEndpointStatsList().stream())
        .mapToLong(EndpointStats::getNumErrors)
        .sum();
  }
}
