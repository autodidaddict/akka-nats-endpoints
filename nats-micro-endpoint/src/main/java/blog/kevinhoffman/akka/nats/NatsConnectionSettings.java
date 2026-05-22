package blog.kevinhoffman.akka.nats;

import com.typesafe.config.Config;
import java.time.Duration;
import java.util.Optional;

/**
 * Immutable NATS connection configuration.
 *
 * <p>Built from the service {@link Config} via {@link #fromConfig(Config)}, which reads the
 * {@code nats { ... }} block. Credentials always come from configuration or the environment,
 * never hard-coded.
 *
 * @param url             the NATS server URL(s)
 * @param token           optional token authentication
 * @param credentialsFile optional path to an NKey/JWT {@code .creds} file
 * @param tlsEnabled      whether to connect using TLS
 * @param connectionName  a connection name reported to the server (aids debugging)
 * @param drainTimeout    maximum time to drain in-flight requests during shutdown
 */
public record NatsConnectionSettings(
    String url,
    Optional<String> token,
    Optional<String> credentialsFile,
    boolean tlsEnabled,
    String connectionName,
    Duration drainTimeout) {

  public NatsConnectionSettings {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("nats.url must be non-blank");
    }
    token = token == null ? Optional.empty() : token;
    credentialsFile = credentialsFile == null ? Optional.empty() : credentialsFile;
    if (connectionName == null || connectionName.isBlank()) {
      connectionName = "akka-nats-endpoint";
    }
    drainTimeout = drainTimeout == null ? Duration.ofSeconds(5) : drainTimeout;
  }

  /**
   * Builds settings from the {@code nats { ... }} block of the supplied configuration.
   *
   * @throws com.typesafe.config.ConfigException if the {@code nats.url} value is missing
   */
  public static NatsConnectionSettings fromConfig(Config config) {
    Config nats = config.getConfig("nats");
    String url = nats.getString("url");
    Optional<String> token = optionalString(nats, "token");
    Optional<String> credentialsFile = optionalString(nats, "credentials-file");
    boolean tlsEnabled = nats.hasPath("tls-enabled") && nats.getBoolean("tls-enabled");
    String connectionName = nats.hasPath("connection-name") ? nats.getString("connection-name") : null;
    Duration drainTimeout =
        nats.hasPath("drain-timeout") ? nats.getDuration("drain-timeout") : Duration.ofSeconds(5);
    return new NatsConnectionSettings(
        url, token, credentialsFile, tlsEnabled, connectionName, drainTimeout);
  }

  private static Optional<String> optionalString(Config config, String path) {
    if (!config.hasPath(path)) {
      return Optional.empty();
    }
    String value = config.getString(path);
    return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
  }
}
