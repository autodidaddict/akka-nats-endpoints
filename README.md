# akka-nats-endpoints

Expose an Akka service as a **NATS micro-service**. Annotate a plain class, register it, and
its methods handle requests arriving on NATS subjects — discoverable and callable with the
stock `nats` command-line tools, no Akka-specific client or custom protocol.

## What it does

- Declare a class with `@NatsMicroService` (name + version) and its methods with
  `@NatsSubject` (the subject to handle).
- Handlers receive the raw request payload (`byte[]`) — or a `NatsRequest` context for
  wildcard subjects — and return raw response bytes. No serialization is imposed.
- The service registers using the standard NATS micro-service mechanisms, so
  `nats micro list / info / ping / stats` recognize it.
- Each request is handled exactly once across a cluster of instances (NATS queue group).
- Handler failures become NATS-native error responses with an error code.

## Module layout

This is a multi-module Maven build:

| Module | Description |
|--------|-------------|
| `nats-micro-endpoint` | The reusable library — the **published artifact**. Depends only on `jnats` + typesafe-config. |
| `nats-micro-sample` | A runnable Akka service that exercises the library. Not published. |

## Requirements

- Java 21
- A reachable NATS server (`nats-server`) for running the sample
- The `nats` CLI for manual interaction

## Using the library

Add the dependency:

```xml
<dependency>
    <groupId>blog.kevinhoffman</groupId>
    <artifactId>nats-micro-endpoint</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Declare a NATS micro-service:

```java
import blog.kevinhoffman.akka.nats.*;

@NatsMicroService(name = "echo-service", version = "1.0.0")
public class EchoNatsService {

  @NatsSubject("echo.upper")
  public byte[] upper(byte[] payload) {
    return new String(payload).toUpperCase().getBytes();
  }

  // Wildcard subject — recover matched tokens from a NatsRequest
  @NatsSubject("echo.repeat.{count}")
  public byte[] repeat(NatsRequest request) {
    int count = Integer.parseInt(request.token("count"));
    if (count < 0) {
      throw new NatsHandlerException(400, "count must be >= 0"); // explicit rejection
    }
    return new String(request.payload()).repeat(count).getBytes();
  }
}
```

Drive the runtime from your service's single `@Setup` class (a service may have only one,
so the library does not ship its own):

```java
@Setup
public class Bootstrap implements ServiceSetup {
  private final NatsMicroRuntime nats;

  public Bootstrap(Config config) {
    this.nats = NatsMicroRuntime.create(NatsConnectionSettings.fromConfig(config));
    this.nats.register(new EchoNatsService());
  }

  @Override public void onStartup()  { nats.start(); }
  @Override public void onShutdown() { nats.stop(); }
}
```

## Configuration

The connection is read from the `nats { ... }` block of `application.conf`; every value
supports an environment-variable override so credentials are never hard-coded:

```hocon
nats {
  url = "nats://localhost:4222"
  url = ${?NATS_URL}
  token = ${?NATS_TOKEN}            # optional
  credentials-file = ${?NATS_CREDS} # optional NKey/JWT .creds file
  tls-enabled = false
  drain-timeout = 5s
}
```

## Build and test

```shell
mvn verify          # builds both modules, runs unit + integration tests
```

Integration tests start an embedded `nats-server` (via `jnats-server-runner`), so a
`nats-server` binary must be available on the `PATH`.

## Run the sample

Start a NATS server, then run the sample service (the `-am` flag also builds the library):

```shell
nats-server &
mvn -pl nats-micro-sample -am compile exec:java
```

Then interact with it using the `nats` CLI:

```shell
nats micro ls                       # echo-service is listed
nats micro info echo-service        # version + endpoint subjects + queue group
nats micro ping echo-service        # one reply per running instance
nats micro stats echo-service       # request / error counters

nats request echo.upper "hello"           # -> HELLO
nats request echo.repeat.3 "ab"           # -> ababab  (wildcard + token)
nats request echo.subject.orders.created  # -> echo.subject.orders.created
```

> Endpoints are ordinary NATS subjects, so they are invoked with `nats request <subject>` —
> there is no `nats micro request` subcommand.

## Known limitation

This library runs entirely in **user space**, on the supported `ServiceSetup` lifecycle hook.
It is **not** a runtime-recognized, first-class endpoint type like the SDK's HTTP/gRPC/MCP
endpoints: there is no compile-time validation, no component-registry visibility, and no
built-in test client.

A consequence worth knowing: an Akka service will not start unless it discovers at least one
Akka component. A service whose *only* purpose is NATS endpoints therefore still needs one
real component (e.g. a minimal HTTP endpoint) to boot. In practice this is a non-issue — the
library is added to a service that already has its own components. The `nats-micro-sample`
module includes a tiny `HealthEndpoint` purely to satisfy this requirement.

A first-class `@NatsEndpoint` SDK type is a candidate future SDK extension point, recorded as
a finding rather than delivered here.
