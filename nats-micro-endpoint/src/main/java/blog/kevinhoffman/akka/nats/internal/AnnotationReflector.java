package blog.kevinhoffman.akka.nats.internal;

import blog.kevinhoffman.akka.nats.NatsMicroService;
import blog.kevinhoffman.akka.nats.NatsRequest;
import blog.kevinhoffman.akka.nats.NatsSubject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds and validates a {@link ServiceDescriptor} from a registered {@code @NatsMicroService}
 * instance using reflection.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class AnnotationReflector {

  private AnnotationReflector() {}

  /**
   * Reflects {@code instance} into a validated {@link ServiceDescriptor}.
   *
   * @throws IllegalArgumentException if the class is not a valid NATS micro-service: missing
   *     {@code @NatsMicroService}, blank name/version, no {@code @NatsSubject} methods, or a
   *     handler method with an unsupported signature
   */
  public static ServiceDescriptor reflect(Object instance) {
    Objects.requireNonNull(instance, "endpoint instance must not be null");
    Class<?> type = instance.getClass();

    NatsMicroService service = type.getAnnotation(NatsMicroService.class);
    if (service == null) {
      throw new IllegalArgumentException(
          type.getName() + " is not annotated with @NatsMicroService");
    }
    String name = service.name();
    String version = service.version();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          type.getName() + ": @NatsMicroService name must be non-blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException(
          type.getName() + ": @NatsMicroService version must be non-blank");
    }
    String queueGroup = service.queueGroup().isBlank() ? name : service.queueGroup();

    List<EndpointDescriptor> endpoints = new ArrayList<>();
    for (Method method : type.getDeclaredMethods()) {
      NatsSubject subject = method.getAnnotation(NatsSubject.class);
      if (subject != null) {
        endpoints.add(toEndpoint(type, method, subject));
      }
    }
    if (endpoints.isEmpty()) {
      throw new IllegalArgumentException(
          type.getName() + ": no @NatsSubject handler methods found");
    }
    return new ServiceDescriptor(
        instance, name, version, queueGroup, service.description(), List.copyOf(endpoints));
  }

  private static EndpointDescriptor toEndpoint(Class<?> type, Method method, NatsSubject subject) {
    String where = type.getName() + "." + method.getName();
    String declaredSubject = subject.value();
    if (declaredSubject == null || declaredSubject.isBlank()) {
      throw new IllegalArgumentException(where + ": @NatsSubject value must be non-blank");
    }
    if (!method.getReturnType().equals(byte[].class)) {
      throw new IllegalArgumentException(where + ": handler method must return byte[]");
    }
    Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length != 1) {
      throw new IllegalArgumentException(
          where + ": handler method must accept exactly one parameter (byte[] or NatsRequest)");
    }
    boolean acceptsRequestContext;
    if (parameters[0].equals(byte[].class)) {
      acceptsRequestContext = false;
    } else if (parameters[0].equals(NatsRequest.class)) {
      acceptsRequestContext = true;
    } else {
      throw new IllegalArgumentException(
          where + ": handler parameter must be byte[] or NatsRequest");
    }
    validateSubject(where, declaredSubject);

    method.setAccessible(true);
    String endpointName = subject.name().isBlank() ? method.getName() : subject.name();
    String natsSubject = SubjectMatcher.toNatsSubject(declaredSubject);
    return new EndpointDescriptor(
        endpointName, declaredSubject, natsSubject, subject.description(), method, acceptsRequestContext);
  }

  private static void validateSubject(String where, String subject) {
    String[] tokens = subject.split("\\.", -1);
    for (int i = 0; i < tokens.length; i++) {
      String token = tokens[i];
      if (token.isEmpty()) {
        throw new IllegalArgumentException(
            where + ": invalid subject '" + subject + "' (empty token)");
      }
      if (token.equals(">") && i != tokens.length - 1) {
        throw new IllegalArgumentException(
            where + ": '>' may only appear as the final token in subject '" + subject + "'");
      }
    }
  }
}
