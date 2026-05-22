package blog.kevinhoffman.akka.nats.synadia;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a plain class is a Synadia agent.
 *
 * <p>A class annotated with {@code @SynadiaAgent} is registered with a
 * {@link blog.kevinhoffman.akka.nats.NatsMicroRuntime}; the runtime reflects its single
 * {@link PromptHandler}-annotated method and registers a NATS micro-service named
 * {@code agents} so that standard Synadia/NATS tooling can discover and call it.
 *
 * <p>The {@link #agent()}, {@link #owner()} and {@link #name()} triple is the agent identity
 * from which the protocol's {@code prompt}, {@code status} and {@code hb} subjects are
 * derived; all three are mandatory, non-blank, and must be subject-token safe (no {@code .},
 * {@code *}, {@code >} or whitespace).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SynadiaAgent {

  /** The canonical agent identifier. Mandatory, non-blank, subject-token safe. */
  String agent();

  /** The owning operator/account. Mandatory, non-blank, subject-token safe. */
  String owner();

  /** The instance name. Mandatory, non-blank, subject-token safe. */
  String name();

  /** The harness version advertised via discovery. */
  String version() default "0.1.0";

  /**
   * Optional session label. When left empty the agent is session-unaware and the label is
   * omitted from heartbeats and status replies entirely.
   */
  String session() default "";

  /** The heartbeat cadence in seconds. Must be greater than zero. */
  int heartbeat() default 30;

  /**
   * The maximum request payload size advertised at discovery and enforced on requests, e.g.
   * {@code "1MB"}, {@code "512KB"} or a plain byte count.
   */
  String maxPayload() default "1MB";

  /**
   * The number of seconds the library waits for a caller's reply to a mid-stream query
   * before surfacing a timeout to the handler. Must be greater than zero.
   */
  int queryTimeout() default 60;
}
