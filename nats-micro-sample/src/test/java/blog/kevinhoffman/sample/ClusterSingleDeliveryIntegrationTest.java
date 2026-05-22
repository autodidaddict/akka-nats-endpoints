package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroService;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import blog.kevinhoffman.akka.nats.NatsSubject;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for User Story 4 — with several identical instances running, each request
 * is handled exactly once across the cluster (load-balanced via the NATS queue group), not
 * once per instance.
 */
class ClusterSingleDeliveryIntegrationTest {

  /** A counting micro-service; each instance increments its own counter when invoked. */
  @NatsMicroService(name = "cluster-counter", version = "1.0.0")
  static class CountingService {
    private final AtomicInteger counter;

    CountingService(AtomicInteger counter) {
      this.counter = counter;
    }

    @NatsSubject("cluster.count")
    public byte[] count(byte[] payload) {
      counter.incrementAndGet();
      return new byte[0];
    }
  }

  private static final AtomicInteger instanceOneCount = new AtomicInteger();
  private static final AtomicInteger instanceTwoCount = new AtomicInteger();

  private static NatsServerRunner server;
  private static NatsMicroRuntime instanceOne;
  private static NatsMicroRuntime instanceTwo;

  @BeforeAll
  static void startTwoIdenticalInstances() throws Exception {
    server = new NatsServerRunner();
    instanceOne = startInstance(new CountingService(instanceOneCount));
    instanceTwo = startInstance(new CountingService(instanceTwoCount));
  }

  private static NatsMicroRuntime startInstance(CountingService service) {
    NatsConnectionSettings settings =
        new NatsConnectionSettings(
            server.getURI(),
            Optional.empty(),
            Optional.empty(),
            false,
            "cluster-test",
            Duration.ofSeconds(2));
    NatsMicroRuntime runtime = NatsMicroRuntime.create(settings);
    runtime.register(service);
    runtime.start();
    return runtime;
  }

  @AfterAll
  static void stopInstances() throws Exception {
    if (instanceOne != null) {
      instanceOne.stop();
    }
    if (instanceTwo != null) {
      instanceTwo.stop();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void everyRequestIsHandledExactlyOnceAndLoadBalancedAcrossTheCluster() throws Exception {
    int requestCount = 20;
    try (Connection client = Nats.connect(server.getURI())) {
      for (int i = 0; i < requestCount; i++) {
        Message reply = client.request("cluster.count", new byte[0], Duration.ofSeconds(2));
        assertThat(reply).isNotNull();
      }
    }

    // Single delivery: M requests produce exactly M handler invocations across 2 instances,
    // not M x 2.
    assertThat(instanceOneCount.get() + instanceTwoCount.get()).isEqualTo(requestCount);
    // Load balanced: both instances handled some of the requests.
    assertThat(instanceOneCount.get()).isPositive();
    assertThat(instanceTwoCount.get()).isPositive();
  }
}
