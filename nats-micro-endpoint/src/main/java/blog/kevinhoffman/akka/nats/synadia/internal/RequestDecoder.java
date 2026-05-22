package blog.kevinhoffman.akka.nats.synadia.internal;

import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import io.nats.client.support.JsonParseException;
import java.nio.charset.StandardCharsets;

/**
 * Decodes a raw request payload into a {@link PromptRequest}, applying the Synadia Agent
 * Protocol §5 discrimination rule.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class RequestDecoder {

  private RequestDecoder() {}

  /**
   * Decodes {@code payload} into a {@link PromptRequest}: leading whitespace is skipped; if
   * the next byte is <code>{</code> the payload is parsed as a JSON envelope, otherwise the
   * whole payload is treated as UTF-8 text and promoted to a prompt.
   *
   * @throws ProtocolError (code 400) if the payload is malformed JSON, an envelope missing a
   *     non-empty {@code prompt}, carries attachments, or is empty plain text
   */
  public static PromptRequest decode(byte[] payload, String subject) {
    int i = 0;
    while (i < payload.length && isWhitespace(payload[i])) {
      i++;
    }
    if (i < payload.length && payload[i] == '{') {
      ProtocolJson.Envelope envelope;
      try {
        envelope = ProtocolJson.parseEnvelope(payload);
      } catch (JsonParseException e) {
        throw new ProtocolError(400, "malformed JSON request envelope");
      }
      if (envelope.hasAttachments()) {
        throw new ProtocolError(400, "attachments are not accepted by this agent");
      }
      if (envelope.prompt() == null || envelope.prompt().isBlank()) {
        throw new ProtocolError(400, "request envelope is missing a non-empty 'prompt'");
      }
      return new PromptRequest(envelope.prompt(), true, subject);
    }

    String text = new String(payload, StandardCharsets.UTF_8);
    if (text.isBlank()) {
      throw new ProtocolError(400, "request prompt is empty");
    }
    return new PromptRequest(text, false, subject);
  }

  private static boolean isWhitespace(byte b) {
    return b == ' ' || b == '\t' || b == '\n' || b == '\r';
  }
}
