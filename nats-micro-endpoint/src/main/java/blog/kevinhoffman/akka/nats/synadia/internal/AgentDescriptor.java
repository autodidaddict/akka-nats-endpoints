package blog.kevinhoffman.akka.nats.synadia.internal;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reflected, validated metadata for one registered {@code @SynadiaAgent} instance — the
 * agent-side analogue of {@code ServiceDescriptor}.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 *
 * @param instance            the developer's registered agent instance
 * @param agent               the canonical agent identifier
 * @param owner               the owning operator/account
 * @param name                the instance name
 * @param version             the advertised harness version
 * @param session             the optional session label ({@code ""} when session-unaware)
 * @param heartbeatSeconds     the validated heartbeat cadence in seconds
 * @param maxPayload           the advertised/enforced maximum request payload size spec
 * @param queryTimeoutSeconds  the validated mid-stream query reply timeout in seconds
 * @param promptMethod         the reflected {@code @PromptHandler} method (accessible)
 * @param promptForm           which supported handler signature {@code promptMethod} matches
 */
public record AgentDescriptor(
    Object instance,
    String agent,
    String owner,
    String name,
    String version,
    String session,
    int heartbeatSeconds,
    String maxPayload,
    int queryTimeoutSeconds,
    Method promptMethod,
    PromptForm promptForm) {

  /** The supported {@code @PromptHandler} method signatures. */
  public enum PromptForm {
    /** {@code String handle(String prompt)}. */
    STRING_IN,
    /** {@code String handle(PromptRequest request)}. */
    REQUEST_IN,
    /** {@code void handle(PromptRequest request, AgentResponse response)}. */
    STREAMING
  }

  /** The enforced maximum request payload size in bytes, parsed from {@link #maxPayload()}. */
  public long maxPayloadBytes() {
    return parsePayloadSize(maxPayload);
  }

  /**
   * Parses a payload-size spec such as {@code "1MB"}, {@code "512KB"} or a plain byte count
   * into a number of bytes.
   *
   * @throws IllegalArgumentException if the spec cannot be parsed
   */
  public static long parsePayloadSize(String spec) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("maxPayload must be non-blank");
    }
    String s = spec.trim().toUpperCase(Locale.ROOT);
    long multiplier = 1;
    if (s.endsWith("KB")) {
      multiplier = 1024L;
      s = s.substring(0, s.length() - 2);
    } else if (s.endsWith("MB")) {
      multiplier = 1024L * 1024L;
      s = s.substring(0, s.length() - 2);
    } else if (s.endsWith("GB")) {
      multiplier = 1024L * 1024L * 1024L;
      s = s.substring(0, s.length() - 2);
    } else if (s.endsWith("B")) {
      s = s.substring(0, s.length() - 1);
    }
    try {
      long value = Long.parseLong(s.trim());
      if (value <= 0) {
        throw new IllegalArgumentException("maxPayload must be positive: '" + spec + "'");
      }
      return value * multiplier;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("invalid maxPayload: '" + spec + "'");
    }
  }
}
