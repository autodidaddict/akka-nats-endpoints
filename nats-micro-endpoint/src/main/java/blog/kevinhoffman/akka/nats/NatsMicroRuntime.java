package blog.kevinhoffman.akka.nats;

import blog.kevinhoffman.akka.nats.internal.AnnotationReflector;
import blog.kevinhoffman.akka.nats.internal.EndpointDescriptor;
import blog.kevinhoffman.akka.nats.internal.HandlerDispatcher;
import blog.kevinhoffman.akka.nats.internal.NatsConnectionFactory;
import blog.kevinhoffman.akka.nats.internal.ServiceDescriptor;
import io.nats.client.Connection;
import io.nats.service.Service;
import io.nats.service.ServiceBuilder;
import io.nats.service.ServiceEndpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * The entry point of the NATS micro-service endpoint library.
 *
 * <p>A developer creates a runtime from {@link NatsConnectionSettings}, registers one or more
 * {@code @NatsMicroService}-annotated instances, and drives its lifecycle from their own
 * {@code @Setup ServiceSetup} class — {@link #start()} from {@code onStartup()} and
 * {@link #stop()} from {@code onShutdown()}.
 *
 * <p>The runtime is single-threaded with respect to its lifecycle: {@code register} is only
 * valid before {@code start}, and {@code start} may be called once.
 */
public final class NatsMicroRuntime {

  /** Lifecycle state of the runtime. */
  enum State {
    NEW,
    RUNNING,
    STOPPED
  }

  private final NatsConnectionSettings settings;
  private final List<ServiceDescriptor> descriptors = new ArrayList<>();
  private final List<Service> services = new ArrayList<>();
  private Connection connection;
  private State state = State.NEW;

  private NatsMicroRuntime(NatsConnectionSettings settings) {
    this.settings = settings;
  }

  /** Creates a runtime in the {@code NEW} state. */
  public static NatsMicroRuntime create(NatsConnectionSettings settings) {
    if (settings == null) {
      throw new IllegalArgumentException("settings must not be null");
    }
    return new NatsMicroRuntime(settings);
  }

  /**
   * Registers a {@code @NatsMicroService}-annotated instance. Only valid before {@link #start()}.
   *
   * @throws IllegalStateException    if the runtime has already been started
   * @throws IllegalArgumentException if the instance is not a valid NATS micro-service
   */
  public NatsMicroRuntime register(Object endpoint) {
    if (state != State.NEW) {
      throw new IllegalStateException("register() is only allowed before start()");
    }
    descriptors.add(AnnotationReflector.reflect(endpoint));
    return this;
  }

  /**
   * Opens the NATS connection and registers every micro-service. Transitions to {@code RUNNING}.
   *
   * @throws IllegalStateException if {@code start()} has already been called
   */
  public void start() {
    if (state != State.NEW) {
      throw new IllegalStateException("start() has already been called");
    }
    try {
      connection = NatsConnectionFactory.connect(settings);
      for (ServiceDescriptor descriptor : descriptors) {
        Service service = buildService(descriptor);
        service.startService();
        services.add(service);
      }
      state = State.RUNNING;
    } catch (RuntimeException e) {
      state = State.STOPPED;
      cleanup();
      throw e;
    }
  }

  /**
   * Drains in-flight requests and closes the NATS connection. Transitions to {@code STOPPED}.
   * Safe to call from any state; a no-op if the runtime is not running.
   */
  public void stop() {
    if (state != State.RUNNING) {
      state = State.STOPPED;
      return;
    }
    state = State.STOPPED;
    cleanup();
  }

  private Service buildService(ServiceDescriptor descriptor) {
    ServiceBuilder serviceBuilder =
        new ServiceBuilder()
            .connection(connection)
            .name(descriptor.name())
            .version(descriptor.version());
    if (!descriptor.description().isBlank()) {
      serviceBuilder.description(descriptor.description());
    }
    for (EndpointDescriptor endpoint : descriptor.endpoints()) {
      HandlerDispatcher handler =
          new HandlerDispatcher(connection, descriptor.instance(), endpoint);
      ServiceEndpoint.Builder endpointBuilder =
          ServiceEndpoint.builder()
              .endpointName(endpoint.endpointName())
              .endpointSubject(endpoint.natsSubject())
              .endpointQueueGroup(descriptor.queueGroup())
              .handler(handler);
      if (!endpoint.description().isBlank()) {
        endpointBuilder.endpointMetadata(Map.of("description", endpoint.description()));
      }
      serviceBuilder.addServiceEndpoint(endpointBuilder.build());
    }
    return serviceBuilder.build();
  }

  private void cleanup() {
    for (Service service : services) {
      try {
        service.stop();
      } catch (RuntimeException ignored) {
        // best-effort shutdown
      }
    }
    services.clear();
    if (connection != null) {
      try {
        connection.drain(settings.drainTimeout());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (TimeoutException | RuntimeException ignored) {
        // best-effort drain
      }
      try {
        connection.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException ignored) {
        // best-effort close
      }
      connection = null;
    }
  }
}
