# Phase 0 Research: NATS Micro-Service Endpoint

**Feature**: 001-nats-micro-endpoint | **Date**: 2026-05-22

This document resolves every unknown in the plan's Technical Context. Each item records the
Decision, Rationale, and Alternatives considered.

---

## R1 — NATS client library and micro-service framework

**Decision**: Use `io.nats:jnats` (the official NATS Java client). Its `io.nats.service`
package provides the NATS micro-service framework: `Service`, `ServiceBuilder`,
`ServiceEndpoint`, `Endpoint`, `ServiceMessage`, and `ServiceMessageHandler`.

**Rationale**: The micro framework already implements the reserved `$SRV.PING`, `$SRV.INFO`,
`$SRV.STATS` discovery handlers and automatically tracks per-endpoint request and error
counters. Building on it means stock `nats micro list/info/ping/stats` recognize the service
with zero extra work — satisfying the spec constraint "NATS micro semantics must be honored,
not approximated." `jnats` is the canonical, actively maintained client and inherits no
transitive baggage that conflicts with the Akka SDK.

**Alternatives considered**:
- *Hand-roll NATS protocol + discovery*: rejected — re-creates a fragile look-alike, violates
  the spec, and is far more code than a dependency.
- *Other JVM NATS clients*: none provide a maintained micro framework; rejected.

---

## R2 — Binding the NATS connection to the Akka service lifecycle

**Decision**: Open the NATS connection and register the micro `Service`(s) inside
`ServiceSetup.onStartup()`. Drain in-flight requests and close the connection inside
`ServiceSetup.onShutdown()`.

**Rationale**: The Akka SDK docs (`setup-and-dependency-injection.html.md`) confirm
`ServiceSetup` exposes both `onStartup()` and `onShutdown()`. `onShutdown()` "is invoked when
the service instance is shutting down, after it has stopped handling requests" and "after any
in-flight requests have completed" — exactly the drain-then-close ordering FR-012 requires.
`onStartup`/`onShutdown` run on **every** instance independently, which matches the
multi-instance model. `NatsMicroRuntime.stop()` will call the jnats `Connection.drain(...)`
(graceful) before `close()`.

**Alternatives considered**:
- *JVM `Runtime.addShutdownHook`*: rejected — fires too late / not coordinated with Akka's
  request-draining; `onShutdown` is the supported, correctly ordered hook.
- *Akka CoordinatedShutdown directly*: rejected — lower-level than needed; `ServiceSetup`
  already wraps the lifecycle cleanly.

---

## R3 — Library wiring: only one `@Setup` class per service

**Decision**: The library does **not** ship a `@Setup ServiceSetup` class. It exposes a
`NatsMicroRuntime` object that the developer constructs and drives from *their own* single
`@Setup` class: `register(endpointInstance)` for each annotated class, `start()` from
`onStartup()`, `stop()` from `onShutdown()`.

**Rationale**: The SDK docs state plainly: "Only one such class may exist in the same
service." A library-provided `@Setup` would collide with the developer's. Driving a plain
runtime object from the developer's existing `ServiceSetup` is the only conflict-free wiring
and keeps control explicit (Constitution IV — Simplicity). The developer obtains
`ComponentClient` and `Config` via the SDK's constructor injection into their `ServiceSetup`
and hands them to `NatsMicroRuntime`.

**Alternatives considered**:
- *Classpath scanning for `@NatsMicroService` classes*: rejected — needs a scanning library
  (extra dependency, Constitution I) and hides wiring. Explicit `register(...)` is clearer.
- *A `DependencyProvider`-based hook*: rejected — `createDependencyProvider` is for injecting
  values into components, not for owning a connection lifecycle.

---

## R4 — Single delivery across cluster instances

**Decision**: Subscribe every endpoint under a NATS **queue group**. The queue-group name
defaults to the `@NatsMicroService` `name` and is overridable via an annotation attribute
(per the clarification). Each `ServiceEndpoint` is built with `.queueGroup(name)`.

**Rationale**: A NATS queue group is the native mechanism for load-balanced, exactly-one
delivery: when N instances subscribe to the same subject with the same queue group, the
server delivers each message to exactly one subscriber. This makes M requests result in M
handler invocations across the cluster, not M×N (FR-011, SC-004). The reserved `$SRV.PING`
discovery subjects are intentionally **not** queue-grouped by the micro framework, so
`nats micro ping` still gets one response per instance — which is the desired behavior.

**Alternatives considered**:
- *Leader election so one instance subscribes*: rejected — adds coordination complexity and
  a single point of failure; queue groups solve it natively.
- *Per-endpoint distinct queue groups*: rejected as the default — one group per service is
  simpler; per-endpoint override is unnecessary now (YAGNI).

---

## R5 — Handler signature and wildcard token recovery

**Decision**: A `@NatsSubject` method may declare one of two parameter shapes:
1. `byte[]` — the raw request payload; or
2. `NatsRequest` — a context object exposing `concreteSubject()`, `payload()` (bytes), and
   `tokens()` (the matched wildcard tokens) plus a `token(name)` lookup.
The return type is `byte[]`. Subject matching and token extraction are performed by an
internal `SubjectMatcher` that compares the concrete subject (`ServiceMessage.getSubject()`)
against the declared pattern.

**Rationale**: This honors the clarification ("optional request context object") — trivial
fixed-subject handlers stay byte-in/byte-out, while wildcard handlers opt into the richer
type. NATS wildcards are positional (`*` = one token, `>` = tail), so token extraction is a
simple token-by-token comparison; named tokens are derived from a `{name}`-style label syntax
in the declared subject (e.g. `orders.*.created` exposed as positional, or
`orders.{id}.created` exposing `token("id")`).

**Alternatives considered**:
- *Always pass `NatsRequest`*: rejected — forces ceremony on the common simple case.
- *Inject tokens as individual method parameters*: rejected — reflection-heavy binding,
  brittle ordering; a single context object is simpler and explicit.

---

## R6 — Error contract mapping to NATS-native errors

**Decision**: On an unhandled exception from a handler, respond with the NATS micro standard
error mechanism (`ServiceMessage.respondStandardError`) using a generic code `"500"` and the
exception message. A handler may instead throw `NatsHandlerException(code, message)` to
reject with a developer-chosen code and message. Either path increments the micro framework's
error counter automatically, so `nats micro stats` reflects it.

**Rationale**: Matches the clarification (generic code for crashes, explicit code for
rejections). Using the micro framework's standard-error response sets the `Nats-Service-Error`
and `Nats-Service-Error-Code` headers that `nats micro` tooling expects, and the framework
counts the error — satisfying FR-008/FR-013/FR-014 and SC-006 without manual counter code.

**Alternatives considered**:
- *Mapping exception types to codes*: rejected — implicit and hard to test; the explicit
  `NatsHandlerException` is clearer and developer-controlled.
- *Plain reply with an error payload*: rejected — invisible to `nats micro stats` and not a
  NATS-native error.

---

## R7 — NATS connection configuration and authentication

**Decision**: `NatsConnectionSettings` is an immutable record built from the injected
`com.typesafe.config.Config` under a `nats { ... }` block in `application.conf`: `url`,
optional `token`, optional `credentials-file` (NKey/JWT `.creds`), optional TLS settings.
Each field supports environment-variable override via HOCON `${?VAR}`.

**Rationale**: FR-015 requires config/environment-driven credentials, never hard-coded. The
SDK docs prescribe injecting `Config` (not `ConfigFactory.load()`). HOCON `${?ENV}` overrides
are the idiomatic way to keep secrets out of source. `jnats` `Options.Builder` accepts token,
`.creds` files, and TLS contexts directly.

**Alternatives considered**:
- *System properties / ad-hoc env reads*: rejected — bypasses the SDK config mechanism and
  the documented `application.conf` precedence.

---

## R8 — Integration testing with a real NATS server

**Decision**: Use `io.nats:jnats-server-runner` (test scope) to start an embedded
`nats-server` per integration-test class. Tests connect via `jnats`, exercise request/reply,
discovery, stats, wildcard routing, and multi-instance single delivery. The
ComponentClient-backed test additionally extends Akka `TestKitSupport` so the handler can
reach a real Akka component, with `application-test.conf` pointing `nats.url` at the embedded
server.

**Rationale**: Spec correctness criteria (discovery recognized by stock tooling, stats
counters, M-not-M×N delivery) can only be validated against a real NATS server. `jnats-server-
runner` is the NATS team's purpose-built test harness — lightweight, no Docker required.
Multi-instance single delivery (SC-004) is tested by constructing two `NatsMicroRuntime`
instances against one embedded server and asserting the total invocation count.

**Alternatives considered**:
- *Testcontainers NATS image*: viable fallback but requires Docker in CI; kept as a noted
  alternative, not the default.
- *Mocking the NATS connection*: rejected — cannot verify real micro semantics.

---

## Resolved Technical Context

No `NEEDS CLARIFICATION` markers remain. Java 21, `jnats` + `jnats-server-runner`, JUnit 5 /
AssertJ / `TestKitSupport`, single Maven project (library + sample service), queue-group
single delivery, and config-driven auth are all settled. Ready for Phase 1 design.
