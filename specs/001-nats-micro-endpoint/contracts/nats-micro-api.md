# Contract: NATS Micro-Service Endpoint Library

**Feature**: 001-nats-micro-endpoint | **Date**: 2026-05-22

This feature is a **library**. It has two contracts:
- **A. Library API contract** — what developers consume when they add the dependency.
- **B. External `nats micro` CLI contract** — the wire/discovery behavior the running
  service must satisfy for stock NATS tooling.

---

## A. Library API contract (`blog.kevinhoffman.akka.nats`)

The supported public surface, published as the Maven artifact
`blog.kevinhoffman:nats-micro-endpoint`. The `blog.kevinhoffman.akka.nats.internal` package is
**not** part of the contract and may change.

### A.1 Declaring a micro-service

```java
@NatsMicroService(name = "echo-service", version = "1.0.0")
public class EchoNatsService {

  // raw-bytes handler — fixed subject
  @NatsSubject("echo.upper")
  public byte[] upper(byte[] payload) {
    return new String(payload).toUpperCase().getBytes();
  }

  // request-context handler — wildcard subject with a named token
  @NatsSubject("echo.repeat.{count}")
  public byte[] repeat(NatsRequest request) {
    int n = Integer.parseInt(request.token("count"));
    return new String(request.payload()).repeat(n).getBytes();
  }
}
```

**Contract rules** (validated at `register(...)`):
- The class MUST carry `@NatsMicroService` with non-blank `name` and `version`.
- Each `@NatsSubject` method MUST return `byte[]` and accept exactly one parameter, either
  `byte[]` or `NatsRequest`.
- A class MUST declare at least one `@NatsSubject` method.
- Violations throw `IllegalArgumentException` naming the offending class/method.

### A.2 Wiring the runtime (from the developer's single `@Setup` class)

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

The library itself depends on nothing from the Akka SDK. A handler that needs to reach other
Akka components takes a `ComponentClient` through its own constructor — the developer obtains
it via injection into the `@Setup` class and passes it to the endpoint instance at
`register(...)` time.

**`NatsMicroRuntime` contract**:

| Method | Precondition | Postcondition |
|--------|--------------|---------------|
| `static create(NatsConnectionSettings)` | — | Returns a runtime in state `NEW`. |
| `register(Object endpoint)` | state `NEW` | Endpoint reflected & validated; descriptors stored. Throws `IllegalStateException` if not `NEW`. |
| `start()` | state `NEW` | Opens the NATS connection; registers one micro `Service` per endpoint with queue-group subscriptions. → `RUNNING`. On failure → `STOPPED`, rethrows. |
| `stop()` | any state | Drains in-flight requests, closes the connection. → `STOPPED`. No-op if not `RUNNING`. |

### A.3 Handler success / failure contract

- Returning `byte[]` ⇒ caller receives exactly those bytes (FR-005). Zero-length allowed.
- Throwing `NatsHandlerException(int code, String message)` ⇒ caller receives a NATS error
  with that code and message (FR-014). The code is an `int`, matching the NATS micro
  `Nats-Service-Error-Code` field.
- Throwing any other exception ⇒ caller receives a NATS error with the generic code `500`
  and the exception message (FR-013).

### A.4 Configuration contract (`application.conf`)

```hocon
nats {
  url = "nats://localhost:4222"
  url = ${?NATS_URL}
  token = ${?NATS_TOKEN}            # optional
  credentials-file = ${?NATS_CREDS} # optional, NKey/JWT .creds path
  tls-enabled = false
  drain-timeout = 5s
}
```

Credentials MUST come from config/environment; the library never hard-codes them (FR-015).

---

## B. External `nats` CLI contract

Against a running service that registered `EchoNatsService` above, the following MUST hold.
These map directly to the spec's acceptance criteria and are verified by integration tests.

Discovery uses the `nats micro` subcommands. Handler endpoints are ordinary NATS subjects, so
they are invoked with `nats request <subject>` — there is no `nats micro request` subcommand.

| Command | Expected behavior | Spec ref |
|---------|-------------------|----------|
| `nats micro ls` | Lists `echo-service`. After shutdown, it disappears. | US2 / SC-007 |
| `nats micro info echo-service` | Reports version `1.0.0` and the endpoint subjects (`echo.upper`, `echo.repeat.*`, …) and queue group `echo-service`. | FR-007 |
| `nats micro ping echo-service` | One response per running instance. | US2 / FR-006 |
| `nats request echo.upper "hello"` | Returns `HELLO` (exact bytes). | SC-002 |
| `nats request echo.repeat.3 "ab"` | Routed to the wildcard handler; returns `ababab`; handler reads token `count`=`3`. | US3 / SC-005 |
| `nats micro stats echo-service` | `num_requests` and `num_errors` increase as requests/failures occur. | FR-008 / SC-006 |
| (failure) `nats request echo.fail` | Caller receives a NATS error response with code `500`; `num_errors` +1. | US5 / SC-006 |
| (rejection) `nats request echo.reject` | Caller receives a NATS error response with the developer code `400`; `num_errors` +1. | US5 / FR-014 |
| (cluster) N instances, M requests | Exactly M handler invocations total across the cluster. | US4 / SC-004 |
| (shutdown) stop the service | Connection drained then closed; service removed from `nats micro ls`. | SC-007 |

---

## C. Known limitation (documented gap, per FR-018)

This library delivers the capability **in user space**. It is NOT a runtime-recognized
first-class endpoint type: it has no compile-time validation, does not appear in the Akka
component registry, and has no built-in SDK test-client. A first-class
`@NatsEndpoint` SDK type is recorded as a **candidate future SDK extension point**, not
delivered here. Consumers must wire `NatsMicroRuntime` through their own `ServiceSetup`.

A further consequence found during implementation: an Akka service refuses to start unless it
discovers at least one Akka component. A service whose only purpose is NATS endpoints
therefore still needs one real component (e.g. a minimal HTTP endpoint) to boot. In practice
this is a non-issue — the library is added to a service that already has components — but it
is part of the same gap.

## D. Delivery structure

The repository is a multi-module Maven build:

- `nats-micro-endpoint` — the published library module (artifact
  `blog.kevinhoffman:nats-micro-endpoint`). Depends only on `io.nats:jnats` and
  typesafe-config; the JAR contains no sample or `@Setup` code.
- `nats-micro-sample` — a runnable Akka service module exercising the library; not published.
