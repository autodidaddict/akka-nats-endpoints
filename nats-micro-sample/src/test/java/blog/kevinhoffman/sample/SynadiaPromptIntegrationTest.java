package blog.kevinhoffman.sample;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import blog.kevinhoffman.akka.nats.synadia.AgentResponse;
import blog.kevinhoffman.akka.nats.synadia.PromptHandler;
import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import blog.kevinhoffman.akka.nats.synadia.QueryReply;
import blog.kevinhoffman.akka.nats.synadia.SynadiaAgent;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import io.nats.client.support.JsonParser;
import io.nats.client.support.JsonValue;
import io.nats.client.support.JsonValueUtils;
import io.nats.service.Discovery;
import io.nats.service.Endpoint;
import io.nats.service.InfoResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for User Story 1 — a Synadia agent is discoverable and answers plain,
 * structured, streamed and interactive prompts. Runs against an embedded {@code nats-server}.
 */
class SynadiaPromptIntegrationTest {

  private static NatsServerRunner server;
  private static NatsMicroRuntime runtime;

  /** Streaming agent that emits each whitespace-separated word of the prompt as its own chunk. */
  @SynadiaAgent(agent = "stream", owner = "acme", name = "stream-1")
  public static class WordStreamAgent {
    @PromptHandler
    public void handle(PromptRequest request, AgentResponse response) {
      for (String word : request.prompt().split("\\s+")) {
        response.emit(word);
      }
    }
  }

  /** Interactive agent that asks the caller a question, then echoes the answer. */
  @SynadiaAgent(agent = "asker", owner = "acme", name = "asker-1", queryTimeout = 1)
  public static class AskingAgent {
    @PromptHandler
    public void handle(PromptRequest request, AgentResponse response) {
      QueryReply reply = response.query("confirm?");
      response.emit("you said: " + (reply.timedOut() ? "<timeout>" : reply.answer()));
    }
  }

  @BeforeAll
  static void startServiceAgainstEmbeddedNats() throws Exception {
    server = new NatsServerRunner();
    runtime = NatsMicroRuntime.create(settings("prompt-it"));
    runtime.register(new EchoSynadiaAgent());
    runtime.register(new WordStreamAgent());
    runtime.register(new AskingAgent());
    runtime.register(new EchoNatsService()); // a @NatsMicroService alongside the agents
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
  void agentIsDiscoverableAsTheAgentsServiceWithItsPromptEndpointMetadata() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<InfoResponse> infos = new Discovery(client).info("agents");

      Endpoint prompt =
          infos.stream()
              .flatMap(info -> info.getEndpoints().stream())
              .filter(e -> e.getSubject().equals("agents.prompt.echo.acme.echo-1"))
              .findFirst()
              .orElseThrow();
      assertThat(prompt.getName()).isEqualTo("prompt");
      assertThat(prompt.getQueueGroup()).isEqualTo("agents");
      assertThat(prompt.getMetadata()).containsEntry("attachments_ok", "false");
      assertThat(prompt.getMetadata()).containsKey("max_payload");
    }
  }

  @Test
  void answersAPlainTextPromptWithAFramedReplyStream() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(client, "agents.prompt.echo.acme.echo-1", bytes("hello"), null);

      assertThat(chunkType(chunks.get(0))).isEqualTo("status");
      assertThat(chunkData(chunks.get(0))).isEqualTo("ack");
      assertThat(chunkType(chunks.get(1))).isEqualTo("response");
      assertThat(chunkData(chunks.get(1))).isEqualTo("echo: hello");
      assertThat(chunks).hasSize(2);
    }
  }

  @Test
  void answersAStructuredEnvelopePromptTheSameWayAsPlainText() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(
              client, "agents.prompt.echo.acme.echo-1", bytes("{\"prompt\":\"hello\"}"), null);

      assertThat(chunkData(chunks.get(0))).isEqualTo("ack");
      assertThat(chunkData(chunks.get(1))).isEqualTo("echo: hello");
    }
  }

  @Test
  void streamsAMultiChunkResponseInOrder() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(client, "agents.prompt.stream.acme.stream-1", bytes("one two three"), null);

      assertThat(chunkData(chunks.get(0))).isEqualTo("ack");
      assertThat(chunks.subList(1, chunks.size()))
          .extracting(SynadiaPromptIntegrationTest::chunkData)
          .containsExactly("one", "two", "three");
    }
  }

  @Test
  void roundTripsAMidStreamQueryWithTheCallersAnswer() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      List<Message> chunks =
          exchange(client, "agents.prompt.asker.acme.asker-1", bytes("go"), data -> "yes");

      assertThat(chunkType(chunks.get(1))).isEqualTo("query");
      Message last = chunks.get(chunks.size() - 1);
      assertThat(chunkData(last)).isEqualTo("you said: yes");
    }
  }

  @Test
  void surfacesAQueryTimeoutToTheHandlerWhenTheCallerDoesNotAnswer() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      // No query responder: the agent's 1s query timeout elapses and the handler continues.
      List<Message> chunks =
          exchange(client, "agents.prompt.asker.acme.asker-1", bytes("go"), null);

      Message last = chunks.get(chunks.size() - 1);
      assertThat(chunkData(last)).isEqualTo("you said: <timeout>");
    }
  }

  @Test
  void loadBalancesPromptsAcrossInstancesOfTheSameAgentIdentity() throws Exception {
    NatsMicroRuntime secondInstance = NatsMicroRuntime.create(settings("prompt-it-2"));
    secondInstance.register(new EchoSynadiaAgent());
    secondInstance.start();
    try (Connection client = Nats.connect(server.getURI())) {
      String inbox = client.createInbox();
      Subscription sub = client.subscribe(inbox);
      client.publish("agents.prompt.echo.acme.echo-1", inbox, bytes("hello"));

      // Drain for a fixed window — exactly one instance in the 'agents' queue group answers.
      List<Message> received = new ArrayList<>();
      Message m;
      while ((m = sub.nextMessage(Duration.ofMillis(500))) != null) {
        received.add(m);
      }
      sub.unsubscribe();

      long acks =
          received.stream()
              .filter(msg -> msg.getData().length > 0 && !msg.hasHeaders())
              .filter(msg -> "ack".equals(chunkData(msg)))
              .count();
      assertThat(acks).isEqualTo(1);
    } finally {
      secondInstance.stop();
    }
  }

  @Test
  void anAgentAndANatsMicroServiceShareOneRuntimeAndConnection() throws Exception {
    try (Connection client = Nats.connect(server.getURI())) {
      Message microReply =
          client.request("echo.upper", bytes("hello"), Duration.ofSeconds(2));
      assertThat(new String(microReply.getData(), StandardCharsets.UTF_8)).isEqualTo("HELLO");

      List<Message> agentChunks =
          exchange(client, "agents.prompt.echo.acme.echo-1", bytes("hello"), null);
      assertThat(chunkData(agentChunks.get(1))).isEqualTo("echo: hello");
    }
  }

  // ---- helpers ------------------------------------------------------------

  private static NatsConnectionSettings settings(String name) {
    return new NatsConnectionSettings(
        server.getURI(), Optional.empty(), Optional.empty(), false, name, Duration.ofSeconds(2));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Publishes {@code payload} to {@code subject} and collects the reply chunk stream up to the
   * zero-byte terminator. When a {@code query} chunk arrives and {@code queryResponder} is
   * non-null, the responder's answer is published to the query's reply subject.
   */
  private static List<Message> exchange(
      Connection client,
      String subject,
      byte[] payload,
      Function<JsonValue, String> queryResponder)
      throws Exception {
    String inbox = client.createInbox();
    Subscription sub = client.subscribe(inbox);
    client.publish(subject, inbox, payload);
    List<Message> chunks = new ArrayList<>();
    Message m;
    while ((m = sub.nextMessage(Duration.ofSeconds(5))) != null) {
      if (m.getData().length == 0 && !m.hasHeaders()) {
        break; // zero-byte headerless terminator
      }
      chunks.add(m);
      if (queryResponder != null && !m.hasHeaders() && "query".equals(chunkType(m))) {
        JsonValue data = JsonValueUtils.readObject(JsonParser.parseUnchecked(m.getData()), "data");
        String answer = queryResponder.apply(data);
        if (answer != null) {
          client.publish(JsonValueUtils.readString(data, "reply_subject"), bytes(answer));
        }
      }
    }
    sub.unsubscribe();
    return chunks;
  }

  private static String chunkType(Message m) {
    return JsonValueUtils.readString(JsonParser.parseUnchecked(m.getData()), "type");
  }

  private static String chunkData(Message m) {
    return JsonValueUtils.readString(JsonParser.parseUnchecked(m.getData()), "data");
  }
}
