package blog.kevinhoffman.akka.nats.synadia;

/**
 * The writer passed to a streaming {@link PromptHandler} method
 * ({@code void handle(PromptRequest, AgentResponse)}).
 *
 * <p>The library frames the protocol stream around the developer's calls: it emits the
 * mandatory acknowledgement chunk before the handler runs and the zero-byte stream terminator
 * after it returns. The developer only emits content and, optionally, asks the caller
 * mid-stream questions.
 */
public interface AgentResponse {

  /**
   * Emits one chunk of response content to the caller. Successive calls appear to the caller
   * as ordered {@code response} chunks.
   */
  void emit(String text);

  /**
   * Asks the caller a clarifying question mid-response and blocks until the caller replies or
   * the configured query timeout elapses.
   *
   * @return the caller's reply, or a {@linkplain QueryReply#timedOut() timed-out} reply if no
   *     answer arrived in time — the handler decides how to proceed in that case
   */
  QueryReply query(String prompt);
}
