package blog.kevinhoffman.akka.nats.synadia.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.support.JsonParser;
import io.nats.client.support.JsonValue;
import io.nats.client.support.JsonValueUtils;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProtocolJson} — chunk, beacon and envelope encoding/decoding. */
class ProtocolJsonTest {

  @Test
  void encodesTheMandatoryAckChunk() {
    JsonValue chunk = JsonParser.parseUnchecked(ProtocolJson.ackChunk());

    assertThat(JsonValueUtils.readString(chunk, "type")).isEqualTo("status");
    assertThat(JsonValueUtils.readString(chunk, "data")).isEqualTo("ack");
  }

  @Test
  void encodesAResponseChunkCarryingTheContent() {
    JsonValue chunk = JsonParser.parseUnchecked(ProtocolJson.responseChunk("hello"));

    assertThat(JsonValueUtils.readString(chunk, "type")).isEqualTo("response");
    assertThat(JsonValueUtils.readString(chunk, "data")).isEqualTo("hello");
  }

  @Test
  void encodesAQueryChunkWithIdReplySubjectAndPrompt() {
    JsonValue chunk =
        JsonParser.parseUnchecked(ProtocolJson.queryChunk("q-1", "_INBOX.x", "confirm?"));

    assertThat(JsonValueUtils.readString(chunk, "type")).isEqualTo("query");
    JsonValue data = JsonValueUtils.readObject(chunk, "data");
    assertThat(JsonValueUtils.readString(data, "id")).isEqualTo("q-1");
    assertThat(JsonValueUtils.readString(data, "reply_subject")).isEqualTo("_INBOX.x");
    assertThat(JsonValueUtils.readString(data, "prompt")).isEqualTo("confirm?");
  }

  @Test
  void encodesABeaconCarryingTheSessionWhenConfigured() {
    JsonValue beacon =
        JsonParser.parseUnchecked(
            ProtocolJson.beacon("echo", "acme", "claude-code", "id-1", "2026-05-22T00:00:00Z", 30));

    assertThat(JsonValueUtils.readString(beacon, "agent")).isEqualTo("echo");
    assertThat(JsonValueUtils.readString(beacon, "owner")).isEqualTo("acme");
    assertThat(JsonValueUtils.readString(beacon, "session")).isEqualTo("claude-code");
    assertThat(JsonValueUtils.readString(beacon, "instance_id")).isEqualTo("id-1");
    assertThat(JsonValueUtils.readString(beacon, "ts")).isEqualTo("2026-05-22T00:00:00Z");
    assertThat(JsonValueUtils.readInteger(beacon, "interval_s")).isEqualTo(30);
  }

  @Test
  void omitsTheSessionFromTheBeaconWhenNotConfigured() {
    JsonValue beacon =
        JsonParser.parseUnchecked(
            ProtocolJson.beacon("echo", "acme", "", "id-1", "2026-05-22T00:00:00Z", 30));

    assertThat(beacon.map).doesNotContainKey("session");
  }

  @Test
  void parsesThePromptOutOfAStructuredEnvelope() throws Exception {
    ProtocolJson.Envelope envelope =
        ProtocolJson.parseEnvelope("{\"prompt\":\"hello\"}".getBytes());

    assertThat(envelope.prompt()).isEqualTo("hello");
    assertThat(envelope.hasAttachments()).isFalse();
  }

  @Test
  void reportsAMissingPromptAsNull() throws Exception {
    ProtocolJson.Envelope envelope = ProtocolJson.parseEnvelope("{}".getBytes());

    assertThat(envelope.prompt()).isNull();
  }

  @Test
  void detectsAttachmentsInAnEnvelope() throws Exception {
    ProtocolJson.Envelope envelope =
        ProtocolJson.parseEnvelope(
            "{\"prompt\":\"hi\",\"attachments\":[{\"filename\":\"f\"}]}".getBytes());

    assertThat(envelope.hasAttachments()).isTrue();
  }
}
