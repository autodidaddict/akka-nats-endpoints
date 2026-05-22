/**
 * Public API of the NATS micro-service endpoint library.
 *
 * <p>These types form the supported surface for consumers:
 *
 * <ul>
 *   <li>{@link blog.kevinhoffman.akka.nats.NatsMicroService} — class-level annotation
 *       declaring a NATS micro-service.
 *   <li>{@link blog.kevinhoffman.akka.nats.NatsSubject} — method-level annotation binding a
 *       handler to a NATS subject.
 *   <li>{@link blog.kevinhoffman.akka.nats.NatsRequest} — optional handler parameter exposing
 *       the concrete subject and matched wildcard tokens.
 *   <li>{@link blog.kevinhoffman.akka.nats.NatsHandlerException} — thrown by a handler to
 *       reject a request with a chosen error code.
 *   <li>{@link blog.kevinhoffman.akka.nats.NatsConnectionSettings} — immutable connection
 *       configuration.
 *   <li>{@link blog.kevinhoffman.akka.nats.NatsMicroRuntime} — the entry point: register
 *       endpoint instances and drive the lifecycle from a {@code ServiceSetup}.
 * </ul>
 *
 * <p>The {@code blog.kevinhoffman.akka.nats.internal} sub-package is not part of this API and
 * may change without notice.
 */
package blog.kevinhoffman.akka.nats;
