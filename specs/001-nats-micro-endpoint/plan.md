# Implementation Plan: NATS Micro-Service Endpoint for Akka Components

**Branch**: `001-nats-micro-endpoint` | **Date**: 2026-05-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-nats-micro-endpoint/spec.md`

## Summary

Deliver a reusable library that lets an Akka service expose logic as a standard NATS
micro-service. A developer annotates a plain class with `@NatsMicroService` (name + version,
optional queue group) and its methods with `@NatsSubject` (subject pattern). The library
reflects those annotations off registered instances, opens a NATS connection during service
startup, and registers a NATS micro `Service` per class whose endpoints subscribe to the
declared subjects under a shared queue group. Requests are dispatched to the developer's
methods, which receive raw bytes (or an optional `NatsRequest` context) and return raw bytes;
failures become NATS-native error responses. Discovery, ping, info, and stats are provided
by the NATS micro framework itself, so stock `nats micro` tooling recognizes the service.

The library is consumed as an ordinary build dependency. Because an Akka service permits only
one `@Setup` class, the library cannot ship its own — instead it exposes a `NatsMicroRuntime`
that the developer drives from their own `ServiceSetup`: register endpoint instances, then
`start()` in `onStartup()` and `stop()` in `onShutdown()`.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**:
- `io.nats:jnats` 2.21.1 — NATS Java client, including the `io.nats.service` micro framework
- `com.typesafe:config` — read by the library to build `NatsConnectionSettings` (`provided`
  scope; the hosting Akka runtime supplies it)
- The library module depends on **nothing from the Akka SDK**. The sample module depends on
  the Akka SDK (`akka-javasdk-parent` 3.5.19) and uses `ServiceSetup` + `Config` injection.
- `io.nats:jnats-server-runner` 2.0.0 (test scope) — embedded `nats-server` for integration tests
**Storage**: N/A — raw request/reply, no persistence (no JetStream, per spec Out of Scope)
**Testing**: JUnit 5, AssertJ; embedded NATS server via `jnats-server-runner`
**Target Platform**: JVM on Linux server; Akka runtime, deployed as one-to-many instances
**Project Type**: Multi-module — a published library module and a separate runnable sample
Akka service module that exercises it and hosts the integration tests
**Performance Goals**: No hard target; request/reply latency is handler time + NATS RTT.
Standard micro-service expectations — sub-second responses for trivial handlers.
**Constraints**: Raw-bytes payloads (no imposed serialization); NATS connection lifecycle
bound to service lifecycle; config/environment-driven NATS auth; single delivery per request
across the cluster via a NATS queue group; user-space only (no SDK changes).
**Scale/Scope**: Small library (~12-15 classes). Multi-instance Akka deployment is the
default operating mode.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

### I. Akka SDK First (NON-NEGOTIABLE) — PASS with documented deviations

- The feature is **not** an Akka SDK component type. There is no SDK primitive for NATS
  micro-service participation, and spec FR-018 explicitly scopes this as a user-space library
  built on the supported `ServiceSetup` lifecycle hook plus `ComponentClient`. The deviation
  is sanctioned by the spec and recorded in Complexity Tracking.
- Two external dependencies are added: `io.nats:jnats` (main) and `io.nats:jnats-server-runner`
  (test). Both are justified in Complexity Tracking — NATS protocol participation and the NATS
  micro framework cannot be reimplemented from the SDK or standard library without re-creating
  a look-alike the `nats` CLI would not recognize (spec: "NATS micro semantics must be honored,
  not approximated"). No other dependencies are introduced; annotation discovery uses plain
  reflection over explicitly registered instances (no classpath-scanning library).

### II. Design Principles — PASS

- **Domain independence**: Handler methods receive raw bytes / a library `NatsRequest` and
  return raw bytes; no Akka or NATS framework type leaks into the developer's domain logic.
- **API isolation**: The library's public surface (`NatsRequest`, `NatsHandlerException`,
  annotations) is distinct from any domain model; handlers reach domain components via
  `ComponentClient`.
- **Single responsibility**: Connection management, annotation reflection, micro-service
  registration, and request dispatch are separate, focused classes.
- **Descriptive naming**: `NatsMicroRuntime`, `HandlerDispatcher`, `NatsConnectionSettings` —
  no generic `Manager`/`Service`/`Event` names.

### III. Test Coverage — PASS

- Delivered with unit tests (annotation reflection, subject/token matching, dispatch) and
  integration tests against an embedded NATS server (request/reply, discovery, stats, error
  responses, wildcard routing, multi-instance single delivery) — 31 tests in total.

### IV. Simplicity — PASS

- Explicit instance registration instead of classpath scanning (no extra dependency, no
  hidden magic). No SDK extension point is built (YAGNI; recorded as a future-finding only).
  Streaming, JetStream, and fan-out are excluded per spec. Flat structure: a thin public API
  plus a small `internal` package.

**Result**: GATE PASSED. Deviations are limited, spec-sanctioned, and tracked below.

## Project Structure

### Documentation (this feature)

```text
specs/001-nats-micro-endpoint/
├── plan.md              # This file (/akka:plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── nats-micro-api.md
├── checklists/
│   └── requirements.md  # from /akka:specify
└── tasks.md             # Phase 2 output (/akka:tasks — NOT created here)
```

### Source Code (repository root)

> **As-built note**: this was delivered as a **multi-module** Maven build (root aggregator
> pom + a library module and a sample module) so the published library JAR contains no
> sample or `@Setup` code. The original plan assumed a single project; the tree below
> reflects what was built.

```text
pom.xml                            # root aggregator (packaging=pom)

nats-micro-endpoint/               # LIBRARY module — published artifact
├── pom.xml                        # plain jar; depends on io.nats:jnats + typesafe-config
└── src/
    ├── main/java/blog/kevinhoffman/akka/nats/
    │   ├── NatsMicroService.java       # @NatsMicroService class-level annotation
    │   ├── NatsSubject.java            # @NatsSubject method-level annotation
    │   ├── NatsRequest.java            # request context: concrete subject, payload, tokens
    │   ├── NatsHandlerException.java   # explicit rejection (developer-chosen int code)
    │   ├── NatsConnectionSettings.java # immutable record built from Config
    │   ├── NatsMicroRuntime.java       # public entry point: register / start / stop
    │   ├── package-info.java           # public API boundary
    │   └── internal/
    │       ├── ServiceDescriptor.java     # reflected metadata for one @NatsMicroService class
    │       ├── EndpointDescriptor.java    # reflected metadata for one @NatsSubject method
    │       ├── AnnotationReflector.java   # builds descriptors from a registered instance
    │       ├── SubjectMatcher.java        # matches concrete subject to pattern, extracts tokens
    │       ├── HandlerDispatcher.java     # io.nats ServiceMessageHandler -> developer method
    │       ├── NatsConnectionFactory.java # opens io.nats Connection from NatsConnectionSettings
    │       └── package-info.java          # marks the package non-public
    └── test/java/blog/kevinhoffman/akka/nats/
        ├── AnnotationReflectorTest.java   # unit
        ├── SubjectMatcherTest.java        # unit
        └── HandlerDispatcherTest.java     # unit

nats-micro-sample/                 # SAMPLE module — runnable Akka service, not published
├── pom.xml                        # extends akka-javasdk-parent; depends on the library module
└── src/
    ├── main/java/blog/kevinhoffman/sample/
    │   ├── Bootstrap.java          # @Setup ServiceSetup wiring NatsMicroRuntime
    │   ├── EchoNatsService.java    # sample @NatsMicroService exercised by integration tests
    │   └── HealthEndpoint.java     # minimal @HttpEndpoint so the Akka service can boot
    ├── main/resources/application.conf      # nats { url, auth } configuration block
    └── test/
        ├── java/blog/kevinhoffman/sample/
        │   ├── NatsMicroEndpointIntegrationTest.java   # request, discovery, stats, errors
        │   ├── WildcardRoutingIntegrationTest.java     # wildcard + token recovery
        │   └── ClusterSingleDeliveryIntegrationTest.java # N runtimes, M requests
        └── resources/application-test.conf
```

**Structure Decision**: Multi-module Maven build. The `nats-micro-endpoint` module is the
reusable library (public API in `blog.kevinhoffman.akka.nats`, non-public classes in the
`internal` sub-package) and is the only published artifact; it depends on `io.nats:jnats` and
typesafe-config and **nothing from the Akka SDK**. The `nats-micro-sample` module is a
runnable Akka service that depends on the library and is used to build, run, and
integration-test it. The split keeps the library JAR free of sample and `@Setup` code, which
would otherwise collide with a consumer's own service setup. See `contracts/nats-micro-api.md`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Feature is not an Akka SDK component type (runs in user space via `ServiceSetup`) | The SDK has no NATS endpoint primitive; spec FR-018 explicitly scopes this as a user-space library and asks that the gap be documented, not closed | Building a first-class SDK endpoint type would require SDK changes that are out of scope; spec records the candidate future SDK extension point as a finding |
| External dependency `io.nats:jnats` (main scope) | NATS wire protocol + the `io.nats.service` micro framework are required so stock `nats micro` commands recognize the service; spec forbids approximating micro semantics | Hand-rolling NATS protocol and the reserved `$SRV.*` discovery handlers would re-create a fragile look-alike and violate the spec's "honored, not approximated" constraint |
| External dependency `io.nats:jnats-server-runner` (test scope only) | Integration tests need a real `nats-server` to verify discovery, stats, and multi-instance delivery | Mocking NATS cannot validate that real `nats micro` semantics work; test-scope only, no production footprint |

## Phase 0 — Research

See [research.md](./research.md). All Technical Context items are resolved there; no
`NEEDS CLARIFICATION` markers remain. Key decisions:

- NATS client + micro framework: `io.nats:jnats` (`io.nats.service` package).
- Lifecycle binding: `ServiceSetup.onStartup()` opens the connection and registers services;
  `onShutdown()` drains and closes (runs after in-flight requests complete).
- Single delivery: a NATS queue group, defaulting to the service name, applied to every
  endpoint subscription.
- Library wiring: `NatsMicroRuntime` driven from the developer's own single `@Setup` class.
- Integration testing: embedded `nats-server` via `jnats-server-runner`.

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — entities: service/endpoint descriptors, `NatsRequest`,
  `NatsConnectionSettings`, error model, runtime states.
- [contracts/nats-micro-api.md](./contracts/nats-micro-api.md) — the library's public API
  contract (annotations, handler signatures, runtime lifecycle) and the external `nats micro`
  CLI contract the feature must satisfy.
- [quickstart.md](./quickstart.md) — end-to-end walkthrough: add the dependency, write an
  endpoint, wire the runtime, run, and call it with `nats micro`.

Post-design Constitution re-check: **PASS** — the design adds no dependencies beyond those in
the gate above, keeps domain logic free of framework types, and stays within the simple
flat structure. No new violations.

## Phase 2 — Next Step

Run `/akka:tasks` to generate the dependency-ordered `tasks.md`. This command stops here.
