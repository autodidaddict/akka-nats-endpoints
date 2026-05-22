package blog.kevinhoffman.akka.nats;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method of a {@link NatsMicroService}-annotated class to a NATS subject.
 *
 * <p>The annotated method must return {@code byte[]} and accept exactly one parameter, either
 * {@code byte[]} (the raw request payload) or {@link NatsRequest} (a request context exposing
 * the concrete subject and matched wildcard tokens). The subject may contain NATS wildcards
 * ({@code *}, trailing {@code >}) or {@code {name}} token labels.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NatsSubject {

  /** The subject pattern this handler is bound to. Must be a valid NATS subject. */
  String value();

  /** Optional endpoint name within the micro-service. Defaults to the method name. */
  String name() default "";

  /** Optional per-endpoint description advertised via {@code nats micro info}. */
  String description() default "";
}
