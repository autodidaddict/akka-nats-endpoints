package blog.kevinhoffman.akka.nats;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.internal.AnnotationReflector;
import blog.kevinhoffman.akka.nats.internal.EndpointDescriptor;
import blog.kevinhoffman.akka.nats.internal.HandlerDispatcher;
import blog.kevinhoffman.akka.nats.internal.ServiceDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class HandlerDispatcherTest {

  @NatsMicroService(name = "dispatch-service", version = "1.0.0")
  static class DispatchService {
    @NatsSubject("echo")
    public byte[] echo(byte[] payload) {
      return payload;
    }

    @NatsSubject("upper")
    public byte[] upper(byte[] payload) {
      return new String(payload, StandardCharsets.UTF_8)
          .toUpperCase(Locale.ROOT)
          .getBytes(StandardCharsets.UTF_8);
    }
  }

  private static EndpointDescriptor endpoint(ServiceDescriptor descriptor, String name) {
    return descriptor.endpoints().stream()
        .filter(endpoint -> endpoint.endpointName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void invokesAByteArrayHandlerAndReturnsExactBytes() {
    DispatchService instance = new DispatchService();
    ServiceDescriptor descriptor = AnnotationReflector.reflect(instance);

    byte[] result =
        HandlerDispatcher.invoke(
            endpoint(descriptor, "echo"), instance, "hello".getBytes(StandardCharsets.UTF_8), "echo");

    assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void invokesAHandlerThatTransformsThePayload() {
    DispatchService instance = new DispatchService();
    ServiceDescriptor descriptor = AnnotationReflector.reflect(instance);

    byte[] result =
        HandlerDispatcher.invoke(
            endpoint(descriptor, "upper"), instance, "hello".getBytes(StandardCharsets.UTF_8), "upper");

    assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("HELLO");
  }

  @Test
  void handlesAnEmptyPayload() {
    DispatchService instance = new DispatchService();
    ServiceDescriptor descriptor = AnnotationReflector.reflect(instance);

    byte[] result =
        HandlerDispatcher.invoke(endpoint(descriptor, "echo"), instance, new byte[0], "echo");

    assertThat(result).isEmpty();
  }
}
