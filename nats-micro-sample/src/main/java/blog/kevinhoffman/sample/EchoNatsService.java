package blog.kevinhoffman.sample;

import blog.kevinhoffman.akka.nats.NatsHandlerException;
import blog.kevinhoffman.akka.nats.NatsMicroService;
import blog.kevinhoffman.akka.nats.NatsRequest;
import blog.kevinhoffman.akka.nats.NatsSubject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sample NATS micro-service that exercises the {@code blog.kevinhoffman.akka.nats} library.
 *
 * <p>It is registered with a {@code NatsMicroRuntime} by {@link Bootstrap} and is also used by
 * the library's integration tests.
 */
@NatsMicroService(
    name = "echo-service",
    version = "1.0.0",
    description = "Echo NATS micro-service sample")
public class EchoNatsService {

  /** Returns the request payload uppercased. */
  @NatsSubject(value = "echo.upper", description = "Uppercases the request payload")
  public byte[] upper(byte[] payload) {
    return new String(payload, StandardCharsets.UTF_8)
        .toUpperCase(Locale.ROOT)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns the payload repeated {@code count} times. Bound to a wildcard subject with a
   * {@code {count}} token, so one handler serves {@code echo.repeat.<any-number>}.
   */
  @NatsSubject(value = "echo.repeat.{count}", description = "Repeats the payload {count} times")
  public byte[] repeat(NatsRequest request) {
    int count = Integer.parseInt(request.token("count"));
    return new String(request.payload(), StandardCharsets.UTF_8)
        .repeat(count)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns the concrete subject the request arrived on. Bound to a {@code >} tail wildcard,
   * demonstrating recovery of the concrete subject inside a wildcard handler.
   */
  @NatsSubject(value = "echo.subject.>", description = "Echoes the concrete request subject")
  public byte[] whichSubject(NatsRequest request) {
    return request.concreteSubject().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Greets whoever is named by the {@code {name}} wildcard token. The reply text embeds the
   * matched token — e.g. a request on {@code echo.greet.kevin} replies {@code Hello, kevin!}.
   */
  @NatsSubject(value = "echo.greet.{name}", description = "Greets the {name} wildcard token")
  public byte[] greet(NatsRequest request) {
    String name = request.token("name");
    return ("Hello, " + name + "!").getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Always throws an ordinary exception, demonstrating that an unhandled failure becomes a
   * NATS-native error with the generic code {@code 500}.
   */
  @NatsSubject(value = "echo.fail", description = "Always fails with an unhandled exception")
  public byte[] fail(byte[] payload) {
    throw new IllegalStateException("handler failed on purpose");
  }

  /**
   * Always rejects the request, demonstrating an explicit rejection with a developer-chosen
   * error code.
   */
  @NatsSubject(value = "echo.reject", description = "Always rejects with error code 400")
  public byte[] reject(byte[] payload) {
    throw new NatsHandlerException(400, "request rejected by handler");
  }
}
