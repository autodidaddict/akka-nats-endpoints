package blog.kevinhoffman.akka.nats.synadia.internal;

import io.nats.client.support.JsonParseException;
import io.nats.client.support.JsonParser;
import io.nats.client.support.JsonValue;
import io.nats.client.support.JsonValueUtils;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes and decodes the Synadia Agent Protocol's JSON payloads — request envelopes, typed
 * response chunks, heartbeat beacons and error bodies — using the JSON support bundled with
 * {@code io.nats:jnats}, so no additional dependency is required.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class ProtocolJson {

  private ProtocolJson() {}

  /** A decoded request envelope. */
  public record Envelope(String prompt, boolean hasAttachments) {}

  /** The mandatory first chunk: {@code {"type":"status","data":"ack"}}. */
  public static byte[] ackChunk() {
    return chunk("status", "ack");
  }

  /** A content chunk: {@code {"type":"response","data":<text>}}. */
  public static byte[] responseChunk(String text) {
    return chunk("response", text == null ? "" : text);
  }

  /** A mid-stream query chunk: {@code {"type":"query","data":{id,reply_subject,prompt}}}. */
  public static byte[] queryChunk(String id, String replySubject, String prompt) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("reply_subject", replySubject);
    data.put("prompt", prompt);
    String json = JsonValueUtils.mapBuilder().put("type", "query").put("data", data).toJson();
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] chunk(String type, String data) {
    String json = JsonValueUtils.mapBuilder().put("type", type).put("data", data).toJson();
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Builds a liveness beacon payload. The {@code session} field is omitted entirely when
   * {@code session} is {@code null} or blank (the agent is session-unaware).
   */
  public static byte[] beacon(
      String agent,
      String owner,
      String session,
      String instanceId,
      String ts,
      int intervalSeconds) {
    JsonValueUtils.MapBuilder builder =
        JsonValueUtils.mapBuilder().put("agent", agent).put("owner", owner);
    if (session != null && !session.isBlank()) {
      builder.put("session", session);
    }
    builder.put("instance_id", instanceId).put("ts", ts).put("interval_s", intervalSeconds);
    return builder.toJson().getBytes(StandardCharsets.UTF_8);
  }

  /** An optional error body: {@code {"error":"<code>","message":"<message>"}}. */
  public static byte[] errorBody(int code, String message) {
    String json =
        JsonValueUtils.mapBuilder()
            .put("error", String.valueOf(code))
            .put("message", message == null ? "" : message)
            .toJson();
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Parses a JSON request envelope.
   *
   * @throws JsonParseException if the payload is not well-formed JSON
   */
  public static Envelope parseEnvelope(byte[] payload) throws JsonParseException {
    JsonValue root = JsonParser.parse(payload);
    String prompt = JsonValueUtils.readString(root, "prompt");
    JsonValue attachments = JsonValueUtils.readValue(root, "attachments");
    boolean hasAttachments =
        attachments != null && attachments.array != null && !attachments.array.isEmpty();
    return new Envelope(prompt, hasAttachments);
  }
}
