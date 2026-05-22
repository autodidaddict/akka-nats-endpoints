package blog.kevinhoffman.sample;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;

/**
 * A minimal HTTP endpoint.
 *
 * <p>The NATS micro-service endpoint library runs entirely in user space via {@link Bootstrap}
 * (a {@code ServiceSetup}); its classes are not Akka components. An Akka service, however,
 * refuses to start unless it discovers at least one component. This endpoint provides that
 * required component so the sample service can boot — in a real project the library would be
 * added to a service that already has its own HTTP/gRPC components, so no extra component is
 * needed there.
 */
@HttpEndpoint("/health")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class HealthEndpoint {

  @Get
  public String health() {
    return "OK";
  }
}
