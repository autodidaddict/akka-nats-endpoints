# Quickstart: Expose an Akka Component as a NATS Micro-Service

**Feature**: 001-nats-micro-endpoint | **Date**: 2026-05-22 (refreshed to the as-built design)

This walkthrough shows a developer adding the library to their Akka service project and
exposing a class on the NATS fabric. It doubles as the manual acceptance script.

## Repository layout

The repository is a multi-module Maven build:

- `nats-micro-endpoint` — the published library (artifact `blog.kevinhoffman:nats-micro-endpoint`).
- `nats-micro-sample` — a runnable Akka service that exercises the library.

## Prerequisites

- An Akka service project (`akka-javasdk-parent` 3.4+).
- A reachable NATS server (`nats-server`) running locally on `:4222`.
- The `nats` CLI installed.

## Step 1 — Add the dependency

In your service module's `pom.xml`:

```xml
<dependency>
  <groupId>blog.kevinhoffman</groupId>
  <artifactId>nats-micro-endpoint</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

## Step 2 — Configure the NATS connection

In `src/main/resources/application.conf`:

```hocon
nats {
  url = "nats://localhost:4222"
  url = ${?NATS_URL}
  token = ${?NATS_TOKEN}
  drain-timeout = 5s
}
```

## Step 3 — Write a NATS micro-service class

```java
package blog.kevinhoffman.sample;

import blog.kevinhoffman.akka.nats.NatsHandlerException;
import blog.kevinhoffman.akka.nats.NatsMicroService;
import blog.kevinhoffman.akka.nats.NatsRequest;
import blog.kevinhoffman.akka.nats.NatsSubject;

@NatsMicroService(name = "echo-service", version = "1.0.0")
public class EchoNatsService {

  @NatsSubject("echo.upper")
  public byte[] upper(byte[] payload) {
    return new String(payload).toUpperCase().getBytes();
  }

  // Wildcard subject with a {count} token; the error code is an int.
  @NatsSubject("echo.repeat.{count}")
  public byte[] repeat(NatsRequest request) {
    int n = Integer.parseInt(request.token("count"));
    if (n < 0) {
      throw new NatsHandlerException(400, "count must be >= 0");
    }
    return new String(request.payload()).repeat(n).getBytes();
  }
}
```

A handler that needs to reach other Akka components takes a `ComponentClient` through its own
constructor — pass it in when you register the instance (Step 4). The library itself depends
on nothing from the Akka SDK.

## Step 4 — Wire the runtime in your `@Setup` class

A service has exactly one `@Setup` class, so the library does not ship its own — drive
`NatsMicroRuntime` from yours:

```java
package blog.kevinhoffman.sample;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import blog.kevinhoffman.akka.nats.NatsConnectionSettings;
import blog.kevinhoffman.akka.nats.NatsMicroRuntime;
import com.typesafe.config.Config;

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

> An Akka service will not start unless it discovers at least one Akka component. If your
> service has no HTTP/gRPC components of its own, add a minimal one (the `nats-micro-sample`
> module includes a tiny `HealthEndpoint` for exactly this reason).

## Step 5 — Run the service

Start a NATS server, then run the service. In this repository the sample is run as:

```shell
nats-server &
mvn -pl nats-micro-sample -am compile exec:java
```

The NATS connection opens during startup and `echo-service` registers on the fabric.

## Step 6 — Verify with the `nats` CLI

Handler endpoints are ordinary NATS subjects, so they are called with `nats request` — there
is no `nats micro request` subcommand.

```shell
nats micro ls                         # echo-service appears
nats micro info echo-service          # version 1.0.0, endpoint subjects, queue group echo-service
nats micro ping echo-service          # one reply per running instance
nats request echo.upper "hello"       # -> HELLO
nats request echo.repeat.3 "ab"       # -> ababab   (wildcard + token)
nats micro stats echo-service         # num_requests increases
```

Trigger a failure to see the error contract:

```shell
nats request echo.repeat.-1 "x"       # NATS error: code 400, "count must be >= 0"
nats micro stats echo-service         # num_errors incremented
```

## Step 7 — Verify multi-instance single delivery

Run two instances of the service against the same NATS server, then send M requests to
`echo.upper`. Total handler invocations across both instances equal M (not 2M) — the queue
group (defaulting to the service name) load-balances delivery.

## Step 8 — Clean shutdown

Stop the service. `onShutdown()` drains in-flight requests and closes the connection;
`nats micro ls` no longer shows `echo-service`. Restarting re-registers it automatically.

## Acceptance mapping

| Step | Spec criterion |
|------|----------------|
| 6 — `ls` / `info` / `ping` | US2, FR-006, FR-007 |
| 6 — `nats request echo.upper` | US1, SC-002 |
| 6 — `nats request echo.repeat.3` | US3, SC-005 |
| 6 — `stats` | FR-008 |
| 6 — failure request | US5, SC-006 |
| 7 — multi-instance | US4, SC-004 |
| 8 — shutdown / restart | SC-007 |

All rows are also exercised automatically by the integration test suite (`mvn verify`),
which runs the same scenarios against an embedded `nats-server`.
