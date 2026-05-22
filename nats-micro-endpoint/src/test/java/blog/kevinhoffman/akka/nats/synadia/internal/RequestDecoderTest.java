package blog.kevinhoffman.akka.nats.synadia.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import blog.kevinhoffman.akka.nats.synadia.PromptRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestDecoder} — the protocol §5 discrimination rule. */
class RequestDecoderTest {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void promotesPlainTextToAnUnstructuredPrompt() {
    PromptRequest request = RequestDecoder.decode(bytes("hello there"), "agents.prompt.x.y.z");

    assertThat(request.prompt()).isEqualTo("hello there");
    assertThat(request.structured()).isFalse();
  }

  @Test
  void decodesAJsonEnvelopeAsAStructuredPrompt() {
    PromptRequest request =
        RequestDecoder.decode(bytes("{\"prompt\":\"hello\"}"), "agents.prompt.x.y.z");

    assertThat(request.prompt()).isEqualTo("hello");
    assertThat(request.structured()).isTrue();
  }

  @Test
  void skipsLeadingWhitespaceBeforeDetectingAJsonEnvelope() {
    PromptRequest request =
        RequestDecoder.decode(bytes("  \n\t{\"prompt\":\"hello\"}"), "agents.prompt.x.y.z");

    assertThat(request.structured()).isTrue();
    assertThat(request.prompt()).isEqualTo("hello");
  }

  @Test
  void rejectsAMalformedJsonEnvelopeAsABadRequest() {
    ProtocolError error =
        catchThrowableOfType(
            () -> RequestDecoder.decode(bytes("{not valid json"), "s"), ProtocolError.class);

    assertThat(error).isNotNull();
    assertThat(error.code).isEqualTo(400);
  }

  @Test
  void rejectsAnEnvelopeWithoutAPromptAsABadRequest() {
    ProtocolError error =
        catchThrowableOfType(
            () -> RequestDecoder.decode(bytes("{\"other\":1}"), "s"), ProtocolError.class);

    assertThat(error).isNotNull();
    assertThat(error.code).isEqualTo(400);
  }

  @Test
  void rejectsAnEnvelopeCarryingAttachmentsAsABadRequest() {
    ProtocolError error =
        catchThrowableOfType(
            () ->
                RequestDecoder.decode(
                    bytes("{\"prompt\":\"hi\",\"attachments\":[{\"filename\":\"f\"}]}"), "s"),
            ProtocolError.class);

    assertThat(error).isNotNull();
    assertThat(error.code).isEqualTo(400);
    assertThat(error.getMessage()).contains("attachments");
  }

  @Test
  void rejectsAnEmptyPlainTextRequestAsABadRequest() {
    ProtocolError error =
        catchThrowableOfType(() -> RequestDecoder.decode(bytes("   "), "s"), ProtocolError.class);

    assertThat(error).isNotNull();
    assertThat(error.code).isEqualTo(400);
  }
}
