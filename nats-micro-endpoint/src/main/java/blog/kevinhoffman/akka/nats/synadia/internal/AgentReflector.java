package blog.kevinhoffman.akka.nats.synadia.internal;

import blog.kevinhoffman.akka.nats.synadia.AgentResponse;
import blog.kevinhoffman.akka.nats.synadia.PromptHandler;
import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds and validates an {@link AgentDescriptor} from a registered {@code @SynadiaAgent}
 * instance using reflection, failing fast on any misconfiguration.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class AgentReflector {

  private AgentReflector() {}

  /**
   * Reflects {@code instance} into a validated {@link AgentDescriptor}.
   *
   * @throws IllegalArgumentException if the class is not a valid Synadia agent: missing
   *     {@code @SynadiaAgent}, an incomplete or non-token-safe identity, a non-positive
   *     heartbeat/query timeout, an unparseable max payload, no or multiple
   *     {@code @PromptHandler} methods, or a handler with an unsupported signature
   */
  public static AgentDescriptor reflect(Object instance) {
    Objects.requireNonNull(instance, "agent instance must not be null");
    Class<?> type = instance.getClass();

    SynadiaAgent annotation = type.getAnnotation(SynadiaAgent.class);
    if (annotation == null) {
      throw new IllegalArgumentException(type.getName() + " is not annotated with @SynadiaAgent");
    }

    SynadiaSubjects.validateToken(type.getName() + ": @SynadiaAgent agent", annotation.agent());
    SynadiaSubjects.validateToken(type.getName() + ": @SynadiaAgent owner", annotation.owner());
    SynadiaSubjects.validateToken(type.getName() + ": @SynadiaAgent name", annotation.name());

    if (annotation.version() == null || annotation.version().isBlank()) {
      throw new IllegalArgumentException(
          type.getName() + ": @SynadiaAgent version must be non-blank");
    }
    if (annotation.heartbeat() <= 0) {
      throw new IllegalArgumentException(
          type.getName() + ": @SynadiaAgent heartbeat must be greater than zero");
    }
    if (annotation.queryTimeout() <= 0) {
      throw new IllegalArgumentException(
          type.getName() + ": @SynadiaAgent queryTimeout must be greater than zero");
    }
    try {
      AgentDescriptor.parsePayloadSize(annotation.maxPayload());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          type.getName() + ": @SynadiaAgent " + e.getMessage());
    }

    Method promptMethod = findPromptHandler(type);
    AgentDescriptor.PromptForm promptForm = resolvePromptForm(type, promptMethod);
    promptMethod.setAccessible(true);

    return new AgentDescriptor(
        instance,
        annotation.agent(),
        annotation.owner(),
        annotation.name(),
        annotation.version(),
        annotation.session() == null ? "" : annotation.session(),
        annotation.heartbeat(),
        annotation.maxPayload(),
        annotation.queryTimeout(),
        promptMethod,
        promptForm);
  }

  private static Method findPromptHandler(Class<?> type) {
    List<Method> handlers = new ArrayList<>();
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(PromptHandler.class)) {
        handlers.add(method);
      }
    }
    if (handlers.isEmpty()) {
      throw new IllegalArgumentException(
          type.getName() + ": exactly one @PromptHandler method is required, found none");
    }
    if (handlers.size() > 1) {
      throw new IllegalArgumentException(
          type.getName()
              + ": exactly one @PromptHandler method is required, found "
              + handlers.size());
    }
    return handlers.get(0);
  }

  private static AgentDescriptor.PromptForm resolvePromptForm(Class<?> type, Method method) {
    String where = type.getName() + "." + method.getName();
    Class<?>[] params = method.getParameterTypes();
    Class<?> returnType = method.getReturnType();

    if (params.length == 1
        && params[0].equals(String.class)
        && returnType.equals(String.class)) {
      return AgentDescriptor.PromptForm.STRING_IN;
    }
    if (params.length == 1
        && params[0].equals(PromptRequest.class)
        && returnType.equals(String.class)) {
      return AgentDescriptor.PromptForm.REQUEST_IN;
    }
    if (params.length == 2
        && params[0].equals(PromptRequest.class)
        && params[1].equals(AgentResponse.class)
        && returnType.equals(void.class)) {
      return AgentDescriptor.PromptForm.STREAMING;
    }
    throw new IllegalArgumentException(
        where
            + ": unsupported @PromptHandler signature; must be one of"
            + " 'String handle(String)', 'String handle(PromptRequest)' or"
            + " 'void handle(PromptRequest, AgentResponse)'");
  }
}
