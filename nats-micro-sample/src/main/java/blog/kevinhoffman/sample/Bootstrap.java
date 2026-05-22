package blog.kevinhoffman.sample;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service setup that wires the NATS micro-service endpoint library into the Akka service
 * lifecycle.
 *
 * <p>A service may have only one {@code @Setup} class, so the library is driven from here
 * rather than shipping its own {@code ServiceSetup}: the {@link NatsMicroRuntime} is started
 * on {@code onStartup} and stopped (draining the NATS connection) on {@code onShutdown}.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

  private final NatsMicroRuntime natsRuntime;

  public Bootstrap(Config config, ComponentClient componentClient) {
    this.natsRuntime = NatsMicroRuntime.create(NatsConnectionSettings.fromConfig(config));
    this.natsRuntime.register(new EchoNatsService());
    // The Akka runtime injects ComponentClient into this @Setup class; it is passed on to a
    // NATS micro-service so its handlers can call other Akka components.
    this.natsRuntime.register(new CounterNatsService(componentClient));
    // A Synadia agent registered with the same runtime — it shares the one NATS connection
    // with the NATS micro-services above.
    this.natsRuntime.register(new EchoSynadiaAgent());
  }

  @Override
  public void onStartup() {
    logger.info("Starting NATS micro-service endpoints");
    natsRuntime.start();
  }

  @Override
  public void onShutdown() {
    logger.info("Stopping NATS micro-service endpoints");
    natsRuntime.stop();
  }
}
