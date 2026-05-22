package blog.kevinhoffman.akka.nats.synadia;

/**
 * The result of an {@link AgentResponse#query(String) mid-stream query}.
 *
 * @param answer   the caller's reply text; an empty string when {@link #timedOut()} is
 *                 {@code true}
 * @param timedOut {@code true} if no reply arrived within the configured query timeout
 */
public record QueryReply(String answer, boolean timedOut) {

  public QueryReply {
    answer = answer == null ? "" : answer;
  }

  /** A reply carrying the caller's answer. */
  public static QueryReply of(String answer) {
    return new QueryReply(answer, false);
  }

  /** A reply indicating the caller did not answer within the query timeout. */
  public static QueryReply noReply() {
    return new QueryReply("", true);
  }
}
