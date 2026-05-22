package blog.kevinhoffman.akka.nats.synadia;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the single mandatory prompt-handling method of a {@link SynadiaAgent}-annotated
 * class.
 *
 * <p>Exactly one method per agent class must carry this annotation. The method must match one
 * of the supported signatures:
 *
 * <ul>
 *   <li>{@code String handle(String prompt)} — simplest single-shot form.
 *   <li>{@code String handle(PromptRequest request)} — single-shot with structured request
 *       access.
 *   <li>{@code void handle(PromptRequest request, AgentResponse response)} — streaming and
 *       interactive form: emit content progressively and ask the caller mid-stream
 *       questions.
 * </ul>
 *
 * <p>Any other count of {@code @PromptHandler} methods, or an unsupported signature, is a
 * fail-fast error at registration.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PromptHandler {}
