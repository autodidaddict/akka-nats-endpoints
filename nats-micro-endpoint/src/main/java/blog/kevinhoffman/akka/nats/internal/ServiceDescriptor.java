package blog.kevinhoffman.akka.nats.internal;

import java.util.List;

/**
 * Reflected, validated metadata for one registered {@code @NatsMicroService} instance.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 *
 * @param instance    the developer's registered endpoint instance
 * @param name        the service name
 * @param version     the service version
 * @param queueGroup  the resolved queue group (override, or the service name by default)
 * @param description optional service description
 * @param endpoints   the handler endpoints declared by the instance (never empty)
 */
public record ServiceDescriptor(
    Object instance,
    String name,
    String version,
    String queueGroup,
    String description,
    List<EndpointDescriptor> endpoints) {}
