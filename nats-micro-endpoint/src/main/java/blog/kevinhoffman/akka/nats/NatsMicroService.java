package blog.kevinhoffman.akka.nats;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a plain class is a NATS micro-service.
 *
 * <p>A class annotated with {@code @NatsMicroService} is registered with a
 * {@link NatsMicroRuntime}; the runtime reflects its {@link NatsSubject}-annotated methods and
 * registers the service on the NATS fabric so that stock {@code nats micro} tooling can
 * discover and call it. Each annotated class exposes exactly one NATS micro-service.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NatsMicroService {

  /** The service name advertised via {@code nats micro info}. Must be non-blank. */
  String name();

  /** The service version advertised via discovery. Must be non-blank. */
  String version();

  /**
   * Overrides the queue group used for load-balanced single delivery across cluster
   * instances. When left blank the queue group defaults to {@link #name()}.
   */
  String queueGroup() default "";

  /** Optional human-readable service description advertised via {@code nats micro info}. */
  String description() default "";
}
