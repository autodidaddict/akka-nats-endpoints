# Phase 1 Data Model: NATS Micro-Service Endpoint

**Feature**: 001-nats-micro-endpoint | **Date**: 2026-05-22

The feature carries no persisted data (no JetStream, no entities). The "data model" here is
the set of in-memory types — annotations, value records, and runtime descriptors — that make
up the library. Public types live in `blog.kevinhoffman.akka.nats`; descriptors live in
`blog.kevinhoffman.akka.nats.internal`.

---

## 1. `@NatsMicroService` (annotation, public)

Class-level marker declaring that a class is a NATS micro-service.

| Attribute | Type | Required | Default | Notes |
|-----------|------|----------|---------|-------|
| `name` | `String` | yes | — | Service name advertised via `nats micro info`. |
| `version` | `String` | yes | — | Semantic version string advertised via discovery. |
| `queueGroup` | `String` | no | `""` → falls back to `name` | Overrides the single-delivery queue group. |
| `description` | `String` | no | `""` | Optional service description for `nats micro info`. |

**Validation**: `name` and `version` must be non-blank; `name` must be a valid NATS service
name (alphanumeric, `-`, `_`). Validated at registration time → `IllegalArgumentException`.

**Relationships**: A class annotated `@NatsMicroService` owns one or more `@NatsSubject`
methods. Exactly one NATS micro-service per class (FR-002a).

---

## 2. `@NatsSubject` (annotation, public)

Method-level marker binding a handler method to a NATS subject.

| Attribute | Type | Required | Default | Notes |
|-----------|------|----------|---------|-------|
| `value` | `String` | yes | — | Subject pattern; may contain `*` / `>` wildcards or `{name}` tokens. |
| `name` | `String` | no | method name | Endpoint name within the micro-service. |
| `description` | `String` | no | `""` | Optional per-endpoint description for `nats micro info`. |

**Validation**: `value` must be a syntactically valid NATS subject; `>` may only appear as
the final token. Annotated methods must return `byte[]` and accept exactly one parameter of
type `byte[]` **or** `NatsRequest`. Violations are reported at registration time with the
offending method name.

---

## 3. `NatsRequest` (record, public)

Optional handler parameter giving access to request context (FR-010).

| Field | Type | Notes |
|-------|------|-------|
| `concreteSubject` | `String` | The actual subject the request arrived on. |
| `payload` | `byte[]` | Raw request payload (may be zero-length). |
| `tokens` | `List<String>` | Wildcard tokens of the concrete subject, in order. |
| `namedTokens` | `Map<String,String>` | Tokens bound to `{name}` labels in the declared subject. |

**Behavior methods**: `String token(String name)` (named-token lookup, throws if absent),
`Optional<String> tokenOpt(String name)`, `byte[] payload()`.

**Lifecycle**: Constructed by `HandlerDispatcher` per request; immutable; never persisted.

---

## 4. `NatsHandlerException` (exception, public)

Thrown by a handler to explicitly reject a request with a chosen NATS error (FR-014).

| Field | Type | Notes |
|-------|------|-------|
| `code` | `int` | Developer-chosen NATS service error code (matches the NATS micro `Nats-Service-Error-Code` field). |
| `message` | `String` | Human-readable error message. |

Unhandled (non-`NatsHandlerException`) exceptions propagate to the NATS micro framework,
which answers with the generic error code `500` and the exception's message (FR-013).

---

## 5. `NatsConnectionSettings` (record, public)

Immutable connection configuration read from `Config` (FR-015).

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `url` | `String` | yes | NATS server URL(s), e.g. `nats://localhost:4222`. |
| `token` | `Optional<String>` | no | Token authentication. |
| `credentialsFile` | `Optional<String>` | no | Path to an NKey/JWT `.creds` file. |
| `tlsEnabled` | `boolean` | no (default false) | Enables TLS context. |
| `connectionName` | `String` | no | Defaults to the service name; aids server-side debugging. |
| `drainTimeout` | `Duration` | no (default 5s) | Max time to drain in-flight requests on shutdown. |

**Source**: built by a static `fromConfig(Config)` reading the `nats { ... }` block.
**Validation**: `url` non-blank; `token` and `credentialsFile` are mutually optional (both
absent ⇒ anonymous connection).

---

## 6. `ServiceDescriptor` (internal)

Reflected, validated metadata for one registered `@NatsMicroService` instance.

| Field | Type | Notes |
|-------|------|-------|
| `instance` | `Object` | The developer's registered endpoint instance. |
| `name` | `String` | From `@NatsMicroService.name`. |
| `version` | `String` | From `@NatsMicroService.version`. |
| `queueGroup` | `String` | Resolved: override or `name`. |
| `description` | `String` | Optional. |
| `endpoints` | `List<EndpointDescriptor>` | One per `@NatsSubject` method. |

**Relationships**: 1 `ServiceDescriptor` → N `EndpointDescriptor`. Built by
`AnnotationReflector` from a registered instance.

---

## 7. `EndpointDescriptor` (internal)

Reflected metadata for one `@NatsSubject` handler method.

| Field | Type | Notes |
|-------|------|-------|
| `endpointName` | `String` | From `@NatsSubject.name` or the method name. |
| `declaredSubject` | `String` | Subject as written in `@NatsSubject` (may contain `{name}` tokens). |
| `natsSubject` | `String` | Actual NATS subscription subject (`{name}` labels replaced by `*`). |
| `description` | `String` | Optional. |
| `method` | `java.lang.reflect.Method` | The handler method. |
| `acceptsRequestContext` | `boolean` | True if the parameter type is `NatsRequest`. |

Named token values are not stored on the descriptor; `SubjectMatcher` derives both positional
and `{name}` tokens from `declaredSubject` against the concrete subject at dispatch time.

---

## 8. Runtime State — `NatsMicroRuntime` lifecycle

`NatsMicroRuntime` is a stateful object with an explicit lifecycle:

```text
NEW  --register(instance)-->  NEW            (descriptors accumulated; repeatable)
NEW  --start()-->             RUNNING        (connection opened, micro services registered)
RUNNING --stop()-->           STOPPED        (connection drained then closed)
```

**Transition rules**:
- `register(...)` is only valid in `NEW`; calling it after `start()` throws
  `IllegalStateException`.
- `start()` is idempotent-safe to call once; a second call throws `IllegalStateException`.
- `stop()` is safe to call from `NEW`, `RUNNING`, or `STOPPED` (no-op if not `RUNNING`), so a
  failed startup can still be cleaned up.
- On `start()` failure (e.g. NATS unreachable), the runtime transitions to `STOPPED`, closes
  any partial connection, and rethrows so the service surfaces a clear startup failure
  (spec edge case: "NATS server unreachable at startup").

---

## 9. Error Model summary

| Situation | Caller receives | Stats effect |
|-----------|-----------------|--------------|
| Handler returns bytes | Reply with exactly those bytes | request counter +1 |
| Handler returns empty `byte[]` | Reply with zero-length payload | request counter +1 |
| Handler throws `NatsHandlerException(code,msg)` | NATS error: `code`, `msg` | request +1, error +1 |
| Handler throws any other exception | NATS error: code `500`, exception message | request +1, error +1 |
| Subject has no matching handler | No response (NATS no-responder) | n/a (not this service) |
