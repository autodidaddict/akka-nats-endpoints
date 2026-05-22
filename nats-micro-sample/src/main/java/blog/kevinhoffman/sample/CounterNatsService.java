package blog.kevinhoffman.sample;

import akka.javasdk.client.ComponentClient;
import blog.kevinhoffman.akka.nats.NatsMicroService;
import blog.kevinhoffman.akka.nats.NatsRequest;
import blog.kevinhoffman.akka.nats.NatsSubject;
import java.nio.charset.StandardCharsets;

/**
 * Sample NATS micro-service demonstrating that a {@code @NatsMicroService}-annotated class can
 * use an Akka {@link ComponentClient}.
 *
 * <p>The library never injects anything itself — it simply reflects and registers the
 * instance handed to it. The {@code ComponentClient} here is supplied by the Akka runtime to
 * {@link Bootstrap} (the {@code @Setup} class), which passes it into this class's constructor
 * before registering the instance with the {@code NatsMicroRuntime}.
 */
@NatsMicroService(
    name = "counter-service",
    version = "1.0.0",
    description = "Demonstrates calling an Akka component from a NATS handler")
public class CounterNatsService {

  private final ComponentClient componentClient;

  public CounterNatsService(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /**
   * Increments the {@link CounterEntity} identified by the {@code {id}} subject token, using
   * the injected {@code ComponentClient}, and replies with the new value.
   */
  @NatsSubject(
      value = "counter.increment.{id}",
      description = "Increments counter {id} via the Akka ComponentClient")
  public byte[] increment(NatsRequest request) {
    String id = request.token("id");
    int value =
        componentClient.forKeyValueEntity(id).method(CounterEntity::increment).invoke();
    return ("counter " + id + " = " + value).getBytes(StandardCharsets.UTF_8);
  }
}
