/**
 * Public API for exposing an Akka service as a Synadia agent.
 *
 * <p>These types form the supported surface for declaring a <a
 * href="https://github.com/synadia-ai/synadia-agent-sdk-docs">Synadia Agent Protocol</a>
 * (v0.3) agent over NATS:
 *
 * <ul>
 *   <li>{@link blog.kevinhoffman.akka.nats.synadia.SynadiaAgent} — class-level annotation
 *       carrying the agent identity and configuration.
 *   <li>{@link blog.kevinhoffman.akka.nats.synadia.PromptHandler} — method-level annotation
 *       marking the single mandatory prompt-handling method.
 *   <li>{@link blog.kevinhoffman.akka.nats.synadia.PromptRequest} — the inbound prompt handed
 *       to the handler.
 *   <li>{@link blog.kevinhoffman.akka.nats.synadia.AgentResponse} — the writer a streaming
 *       handler uses to emit content and ask the caller mid-stream questions.
 *   <li>{@link blog.kevinhoffman.akka.nats.synadia.QueryReply} — the result of a mid-stream
 *       query.
 *   <li>{@link blog.kevinhoffman.akka.nats.synadia.SynadiaAgentException} — thrown by a
 *       handler to reject a request with a chosen protocol status code.
 * </ul>
 *
 * <p>A {@code @SynadiaAgent}-annotated instance is registered with a
 * {@link blog.kevinhoffman.akka.nats.NatsMicroRuntime}, the same runtime used for ordinary
 * NATS micro-endpoints, so an agent and those endpoints share one NATS connection.
 *
 * <p>The {@code blog.kevinhoffman.akka.nats.synadia.internal} sub-package is not part of this
 * API and may change without notice.
 */
package blog.kevinhoffman.akka.nats.synadia;
