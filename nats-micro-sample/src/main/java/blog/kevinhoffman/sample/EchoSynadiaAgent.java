package blog.kevinhoffman.sample;

import blog.kevinhoffman.akka.nats.synadia.PromptHandler;
import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgent;

/**
 * Sample Synadia agent that exercises the {@code blog.kevinhoffman.akka.nats.synadia}
 * library.
 *
 * <p>It is registered with a {@code NatsMicroRuntime} by {@link Bootstrap} and exposes the
 * Synadia Agent Protocol over NATS: standard tooling discovers it as the {@code agents}
 * micro-service and prompts it on {@code agents.prompt.echo.acme.echo-1}.
 */
@SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1", version = "1.0.0")
public class EchoSynadiaAgent {

  /** Echoes the caller's prompt back, prefixed with {@code echo:}. */
  @PromptHandler
  public String handle(PromptRequest request) {
    return "echo: " + request.prompt();
  }
}
