package blog.kevinhoffman.akka.nats.internal;

import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

/**
 * Opens an {@link io.nats.client.Connection} from {@link NatsConnectionSettings}.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 */
public final class NatsConnectionFactory {

  private NatsConnectionFactory() {}

  /**
   * Connects to the NATS server described by {@code settings}.
   *
   * @throws NatsConnectionException if the connection cannot be established
   */
  public static Connection connect(NatsConnectionSettings settings) {
    Options.Builder builder =
        new Options.Builder().server(settings.url()).connectionName(settings.connectionName());

    settings.token().ifPresent(token -> builder.token(token.toCharArray()));
    settings.credentialsFile().ifPresent(path -> builder.authHandler(Nats.credentials(path)));

    try {
      if (settings.tlsEnabled()) {
        builder.secure();
      }
      return Nats.connect(builder.build());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new NatsConnectionException("Interrupted while connecting to NATS at " + settings.url(), e);
    } catch (Exception e) {
      throw new NatsConnectionException("Failed to connect to NATS at " + settings.url(), e);
    }
  }

  /** Thrown when a NATS connection cannot be established. */
  public static final class NatsConnectionException extends RuntimeException {
    NatsConnectionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
