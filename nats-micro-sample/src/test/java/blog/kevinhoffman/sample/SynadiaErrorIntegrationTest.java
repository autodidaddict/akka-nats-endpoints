package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import blog.kevinhoffman.akka.nats.synadia.PromptHandler;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgent;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgentException;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import io.nats.service.ServiceMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for User Story 3 — malformed, oversized, rejected and failing requests all
 * produce protocol-conformant errors. Runs against an embedded {@code nats-server}.
 */
class SynadiaErrorIntegrationTest {

  private static NatsServerRunner server;
  private static NatsMicroRuntime runtime;

  /** Agent with a tiny declared max payload so a normal request is oversized. */
  @SynadiaAgent(agent = "tiny", owner = "acme", name = "tiny-1", maxPayload = "16")
  public static class TinyPayloadAgent {
    @PromptHandler
    public String handle(String prompt) {
      return prompt;
    }
  }

  /** Agent that always rejects the request with a developer-chosen status. */
  @SynadiaAgent(agent = "guard", owner = "acme", name = "guard-1")
  public static class RejectingAgent {
    @PromptHandler
    public String handle(String prompt) {
      throw new SynadiaAgentException(403, "not allowed");
    }
  }

  /** Agent whose handler always fails with an unexpected exception. */
  @SynadiaAgent(agent = "broken", owner = "acme", name = "broken-1")
  public static class FailingAgent {
    @PromptHandler
    public String handle(String prompt) {
      throw new IllegalStateException("handler failed on purpose");
    }
  }

  @BeforeAll
  static void startServiceAgainstEmbeddedNats() throws Exception {
    server = new NatsServerRunner();
    runtime = NatsMicroRuntime.create(settings());
    runtime.register(new EchoSynadiaAgent());
    runtime.register(new TinyPayloadAgent());
    runtime.register(new RejectingAgent());
    runtime.register(new FailingAgent());
    runtime.start();
  }

  @AfterAll
  static void stopService() throws Exception {
    if (runtime != null) {
      runtime.stop();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void rejectsAMalformedJsonRequestWithABadRequestError() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(client, "agents.prompt.echo.acme.echo-1", bytes("{not valid json"));

      assertErrorWithCode(chunks, "400");
    }
  }

  @Test
  void rejectsAnOversizedRequestWithABadRequestError() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(
              client,
              "agents.prompt.tiny.acme.tiny-1",
              bytes("this prompt is well beyond the sixteen byte limit"));

      assertErrorWithCode(chunks, "400");
    }
  }

  @Test
  void surfacesAnExplicitRejectionWithTheDeveloperChosenStatusAndMessage() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(client, "agents.prompt.guard.acme.guard-1", bytes("hello"));

      Message error = assertErrorWithCode(chunks, "403");
      assertThat(error.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR))
          .contains("not allowed");
    }
  }

  @Test
  void translatesAnUnexpectedHandlerFailureIntoAnInternalError() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(client, "agents.prompt.broken.acme.broken-1", bytes("hello"));

      assertErrorWithCode(chunks, "500");
    }
  }

  // ---- helpers ------------------------------------------------------------

  /** Asserts the stream ended with an error-headed message carrying {@code code}, and returns it. */
  private static Message assertErrorWithCode(List<Message> chunks, String code) {
    Message error =
        chunks.stream()
            .filter(Message::hasHeaders)
            .filter(m -> m.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR_CODE) != null)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no error-headed message in the reply stream"));
    assertThat(error.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR_CODE))
        .isEqualTo(code);
    assertThat(error.getHeaders().getFirst(ServiceMessage.NATS_SERVICE_ERROR)).isNotBlank();
    return error;
  }

  /** Publishes a request and collects the reply chunk stream up to the zero-byte terminator. */
  private static List<Message> exchange(Connection client, String subject, byte[] payload)
      throws Exception {
    String inbox = client.createInbox();
    Subscription sub = client.subscribe(inbox);
    client.publish(subject, inbox, payload);
    List<Message> chunks = new ArrayList<>();
    Message m;
    boolean sawTerminator = false;
    while ((m = sub.nextMessage(Duration.ofSeconds(5))) != null) {
      if (m.getData().length == 0 && !m.hasHeaders()) {
        sawTerminator = true;
        break; // zero-byte headerless terminator
      }
      chunks.add(m);
    }
    sub.unsubscribe();
    assertThat(sawTerminator).as("the reply stream must end with a terminator").isTrue();
    return chunks;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static NatsConnectionSettings settings() {
    return new NatsConnectionSettings(
        server.getURI(),
        Optional.empty(),
        Optional.empty(),
        false,
        "error-it",
        Duration.ofSeconds(2));
  }
}
