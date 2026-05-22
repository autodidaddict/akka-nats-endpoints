package blog.kevinhoffman.akka.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import blog.kevinhoffman.akka.nats.internal.AnnotationReflector;
import blog.kevinhoffman.akka.nats.internal.ServiceDescriptor;
import org.junit.jupiter.api.Test;

class AnnotationReflectorTest {

  @NatsMicroService(name = "test-service", version = "2.0.0")
  static class ValidService {
    @NatsSubject("a.b")
    public byte[] fixed(byte[] payload) {
      return payload;
    }

    @NatsSubject(value = "c.{id}", name = "wildcard")
    public byte[] wild(NatsRequest request) {
      return request.payload();
    }
  }

  static class NotAnnotated {
    @NatsSubject("x")
    public byte[] handler(byte[] payload) {
      return payload;
    }
  }

  @NatsMicroService(name = " ", version = "1.0.0")
  static class BlankName {
    @NatsSubject("x")
    public byte[] handler(byte[] payload) {
      return payload;
    }
  }

  @NatsMicroService(name = "n", version = "")
  static class BlankVersion {
    @NatsSubject("x")
    public byte[] handler(byte[] payload) {
      return payload;
    }
  }

  @NatsMicroService(name = "n", version = "1.0.0")
  static class BadReturnType {
    @NatsSubject("x")
    public String handler(byte[] payload) {
      return "";
    }
  }

  @NatsMicroService(name = "n", version = "1.0.0")
  static class BadParameterType {
    @NatsSubject("x")
    public byte[] handler(String payload) {
      return new byte[0];
    }
  }

  @NatsMicroService(name = "n", version = "1.0.0")
  static class NoHandlers {
    public byte[] notAHandler(byte[] payload) {
      return payload;
    }
  }

  @NatsMicroService(name = "queued", version = "1.0.0", queueGroup = "custom-group")
  static class WithQueueGroupOverride {
    @NatsSubject("x")
    public byte[] handler(byte[] payload) {
      return payload;
    }
  }

  @Test
  void reflectsAValidServiceIntoCorrectDescriptors() {
    ServiceDescriptor descriptor = AnnotationReflector.reflect(new ValidService());

    assertThat(descriptor.name()).isEqualTo("test-service");
    assertThat(descriptor.version()).isEqualTo("2.0.0");
    assertThat(descriptor.queueGroup()).isEqualTo("test-service");
    assertThat(descriptor.endpoints()).hasSize(2);
    assertThat(descriptor.endpoints())
        .anySatisfy(
            endpoint -> {
              assertThat(endpoint.endpointName()).isEqualTo("fixed");
              assertThat(endpoint.declaredSubject()).isEqualTo("a.b");
              assertThat(endpoint.natsSubject()).isEqualTo("a.b");
              assertThat(endpoint.acceptsRequestContext()).isFalse();
            })
        .anySatisfy(
            endpoint -> {
              assertThat(endpoint.endpointName()).isEqualTo("wildcard");
              assertThat(endpoint.declaredSubject()).isEqualTo("c.{id}");
              assertThat(endpoint.natsSubject()).isEqualTo("c.*");
              assertThat(endpoint.acceptsRequestContext()).isTrue();
            });
  }

  @Test
  void resolvesQueueGroupOverride() {
    ServiceDescriptor descriptor = AnnotationReflector.reflect(new WithQueueGroupOverride());
    assertThat(descriptor.queueGroup()).isEqualTo("custom-group");
  }

  @Test
  void rejectsAClassWithoutTheAnnotation() {
    assertThatThrownBy(() -> AnnotationReflector.reflect(new NotAnnotated()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("@NatsMicroService");
  }

  @Test
  void rejectsABlankServiceName() {
    assertThatThrownBy(() -> AnnotationReflector.reflect(new BlankName()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  @Test
  void rejectsABlankServiceVersion() {
    assertThatThrownBy(() -> AnnotationReflector.reflect(new BlankVersion()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  void rejectsAHandlerWithTheWrongReturnType() {
    assertThatThrownBy(() -> AnnotationReflector.reflect(new BadReturnType()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte[]");
  }

  @Test
  void rejectsAHandlerWithTheWrongParameterType() {
    assertThatThrownBy(() -> AnnotationReflector.reflect(new BadParameterType()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parameter");
  }

  @Test
  void rejectsAServiceWithNoHandlerMethods() {
    assertThatThrownBy(() -> AnnotationReflector.reflect(new NoHandlers()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no @NatsSubject");
  }
}
