package blog.kevinhoffman.akka.nats.synadia.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import blog.kevinhoffman.akka.nats.synadia.AgentResponse;
import blog.kevinhoffman.akka.nats.synadia.PromptHandler;
import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgent;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AgentReflector} — fail-fast validation of {@code @SynadiaAgent} classes. */
class AgentReflectorTest {

  // ---- Valid agents -------------------------------------------------------

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1")
  static class StringInAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1", session = "claude-code")
  static class RequestInAgent {
    @PromptHandler
    public String handle(PromptRequest request) {
      return request.prompt();
    }
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1")
  static class StreamingAgent {
    @PromptHandler
    public void handle(PromptRequest request, AgentResponse response) {
      response.emit(request.prompt());
    }
  }

  @Test
  void reflectsTheStringInHandlerForm() {
    AgentDescriptor descriptor = AgentReflector.reflect(new StringInAgent());

    assertThat(descriptor.agent()).isEqualTo("echo");
    assertThat(descriptor.owner()).isEqualTo("acme");
    assertThat(descriptor.name()).isEqualTo("echo-1");
    assertThat(descriptor.session()).isEmpty();
    assertThat(descriptor.promptForm()).isEqualTo(AgentDescriptor.PromptForm.STRING_IN);
  }

  @Test
  void reflectsTheRequestInHandlerFormAndOptionalSession() {
    AgentDescriptor descriptor = AgentReflector.reflect(new RequestInAgent());

    assertThat(descriptor.session()).isEqualTo("claude-code");
    assertThat(descriptor.promptForm()).isEqualTo(AgentDescriptor.PromptForm.REQUEST_IN);
  }

  @Test
  void reflectsTheStreamingHandlerForm() {
    AgentDescriptor descriptor = AgentReflector.reflect(new StreamingAgent());

    assertThat(descriptor.promptForm()).isEqualTo(AgentDescriptor.PromptForm.STREAMING);
  }

  // ---- Invalid agents -----------------------------------------------------

  static class NotAnAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsAClassWithoutTheSynadiaAgentAnnotation() {
    assertThatThrownBy(() -> AgentReflector.reflect(new NotAnAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("@SynadiaAgent");
  }

  @SynadiaAgent(agent = "", owner = "acme", name = "echo-1")
  static class BlankAgentIdAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsABlankIdentityElement() {
    assertThatThrownBy(() -> AgentReflector.reflect(new BlankAgentIdAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
  }

  @SynadiaAgent(agent = "echo.1", owner = "acme", name = "echo-1")
  static class NonTokenIdentityAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsANonTokenSafeIdentityElement() {
    assertThatThrownBy(() -> AgentReflector.reflect(new NonTokenIdentityAgent()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1")
  static class NoPromptHandlerAgent {
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsAnAgentWithNoPromptHandler() {
    assertThatThrownBy(() -> AgentReflector.reflect(new NoPromptHandlerAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("found none");
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1")
  static class TwoPromptHandlersAgent {
    @PromptHandler
    public String first(String prompt) {
      return prompt;
    }

    @PromptHandler
    public String second(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsAnAgentWithMoreThanOnePromptHandler() {
    assertThatThrownBy(() -> AgentReflector.reflect(new TwoPromptHandlersAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("found 2");
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1")
  static class BadSignatureAgent {
    @PromptHandler
    public int handle(int prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsAPromptHandlerWithAnUnsupportedSignature() {
    assertThatThrownBy(() -> AgentReflector.reflect(new BadSignatureAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported @PromptHandler signature");
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1", heartbeat = 0)
  static class NonPositiveHeartbeatAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsANonPositiveHeartbeatCadence() {
    assertThatThrownBy(() -> AgentReflector.reflect(new NonPositiveHeartbeatAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("heartbeat");
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1", queryTimeout = 0)
  static class NonPositiveQueryTimeoutAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsANonPositiveQueryTimeout() {
    assertThatThrownBy(() -> AgentReflector.reflect(new NonPositiveQueryTimeoutAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queryTimeout");
  }

  @SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1", maxPayload = "huge")
  static class UnparseableMaxPayloadAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  @Test
  void rejectsAnUnparseableMaxPayload() {
    assertThatThrownBy(() -> AgentReflector.reflect(new UnparseableMaxPayloadAgent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxPayload");
  }
}
