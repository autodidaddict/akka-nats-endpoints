package blog.kevinhoffman.akka.nats.internal;

import java.lang.reflect.Method;

/**
 * Reflected, validated metadata for one {@code @NatsSubject} handler method.
 *
 * <p>Internal implementation detail of the library; not part of the supported public API.
 *
 * @param endpointName          the endpoint name advertised via discovery
 * @param declaredSubject       the subject as written in {@code @NatsSubject}, which may
 *                              contain {@code {name}} token labels
 * @param natsSubject           the actual NATS subscription subject ({@code {name}} labels
 *                              replaced by {@code *})
 * @param description           optional endpoint description
 * @param method                the handler method to invoke
 * @param acceptsRequestContext {@code true} if the handler parameter is
 *                              {@code NatsRequest}, {@code false} if it is {@code byte[]}
 */
public record EndpointDescriptor(
    String endpointName,
    String declaredSubject,
    String natsSubject,
    String description,
    Method method,
    boolean acceptsRequestContext) {}
