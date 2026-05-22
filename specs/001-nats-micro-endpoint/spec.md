# Feature Specification: NATS Micro-Service Endpoint for Akka Components

**Feature Branch**: `001-nats-micro-endpoint`
**Created**: 2026-05-22
**Status**: Draft
**Input**: User description: "Let a developer expose an Akka service as a NATS micro-service by writing an ordinary class whose methods handle requests arriving on NATS subjects. Delivered as a library dependency added to a developer's service project."

## Clarifications

### Session 2026-05-22

- Q: What developer-facing shape should declare the service identity and bind methods to subjects? → A: A class-level annotation supplies the service name & version; a separate per-method annotation binds each method to a subject (mirrors `@HttpEndpoint` + `@Get`).
- Q: How should a wildcard handler recover the concrete subject and matched tokens? → A: A handler may receive an optional request context object exposing the concrete subject, raw payload bytes, and matched wildcard tokens; fixed-subject handlers may instead take just the raw bytes.
- Q: What is the error contract for failed requests? → A: An unhandled exception yields a generic error code (e.g. "500"); a handler may explicitly reject a request with a developer-chosen error code and message.
- Q: Can a single class expose more than one NATS micro-service? → A: One service per class — each declared class exposes exactly one NATS micro-service; multiple services require multiple classes.
- Q: How is the single-delivery queue-group name derived? → A: It defaults to the service name and is overridable by the developer in the declaration.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Declare a class as a NATS micro-service and serve requests (Priority: P1)

A developer adds the library to their Akka service project, takes a plain class, declares
that it is a NATS micro-service (giving it a service name and version), and declares that
one or more of its methods handle requests on named subjects. Each handler receives the raw
request payload and returns the raw response payload. Once the service starts, any NATS
client can call a handler and receive exactly the bytes the handler produced.

**Why this priority**: This is the foundational capability. Without the ability to declare a
service and have a handler answer a request, nothing else in the feature has value. It is the
minimum viable product — a developer can put one Akka component on the NATS fabric.

**Independent Test**: Add the library to a sample service, annotate a class with one subject
handler that echoes its input, start the service, and run
`nats micro request <service> <subject> "<payload>"`; the response equals the bytes the
handler returned.

**Acceptance Scenarios**:

1. **Given** a class declared as a NATS micro-service with a name and version and one method
   bound to a subject, **When** the service starts, **Then** the handler is subscribed to
   that subject on the configured NATS server.
2. **Given** a running service with a subject handler, **When** a client sends a request to
   that subject with a raw payload, **Then** the handler receives exactly those bytes and the
   client receives exactly the bytes the handler returned.
3. **Given** a handler method, **When** it is invoked, **Then** the developer controls
   serialization entirely — no JSON or other encoding is imposed by the framework.
4. **Given** a class with multiple methods, each bound to a different subject, **When** the
   service starts, **Then** each method is subscribed to its own subject independently.

---

### User Story 2 - Discover and introspect the service with standard `nats` tooling (Priority: P2)

An operator or developer uses the stock `nats micro` command-line tools to discover the
Akka-backed service, inspect its identity and declared endpoints, ping its instances, and
read its request statistics — the same way they would for any other NATS micro-service.

**Why this priority**: Discovery and introspection make the service a full citizen of the
NATS micro ecosystem rather than an opaque subscriber. It is essential for operability but
the service can still answer requests (Story 1) without it.

**Independent Test**: Against a running declared service, run `nats micro list`,
`nats micro info <service>`, `nats micro ping`, and `nats micro stats` and confirm each
reports the service, its metadata, its instances, and its request counters.

**Acceptance Scenarios**:

1. **Given** a running declared service, **When** an operator runs `nats micro list`,
   **Then** the service appears with its declared name.
2. **Given** a running declared service, **When** an operator runs
   `nats micro info <service>`, **Then** the output reports the service's version and the
   declared subjects of its endpoints.
3. **Given** one or more running instances, **When** an operator runs `nats micro ping`,
   **Then** a response is returned from each running instance.
4. **Given** a running service, **When** requests are made and an operator runs
   `nats micro stats`, **Then** request counts (and error counts) reflect the requests made
   and increase as more requests are sent.

---

### User Story 3 - Bind a handler to wildcard subjects and recover the concrete subject (Priority: P2)

A developer binds a single handler to a subject containing NATS wildcards so that one method
can serve a family of related subjects, and inside the handler recovers which concrete
subject (and which matched tokens) a given request actually arrived on.

**Why this priority**: Wildcard binding with subject recovery is expected ergonomics for any
non-trivial service, analogous to path-parameter binding on an HTTP endpoint. It is not
required for a single fixed-subject MVP, so it ranks below Story 1.

**Independent Test**: Bind a handler to a wildcard subject, send a request to a concrete
subject covered by the wildcard, and confirm the handler is invoked and can report back the
concrete subject and matched tokens.

**Acceptance Scenarios**:

1. **Given** a handler bound to a wildcard subject, **When** a request arrives on a concrete
   subject covered by the wildcard, **Then** that handler is invoked.
2. **Given** a wildcard handler is invoked, **When** it inspects the request, **Then** it can
   determine the concrete subject the request arrived on and the matched wildcard tokens.

---

### User Story 4 - Run a cluster with exactly-once delivery per request (Priority: P2)

A developer deploys the service as several identical instances (Akka's normal multi-instance
deployment) and each incoming request is handled exactly once across the cluster, load
balanced across instances — not once per instance.

**Why this priority**: Akka services run as one-to-many distributed instances by default, so
correct cluster behavior is a correctness requirement, not an optimization. It ranks at P2
because a single-instance MVP (Story 1) demonstrates the capability before clustering is
verified.

**Independent Test**: Run N identical instances of the declared service, send M requests, and
confirm exactly M handler invocations occurred in total across all instances (not M×N).

**Acceptance Scenarios**:

1. **Given** N identical instances of the same declared service are running, **When** M
   requests are sent to a handled subject, **Then** the total number of handler invocations
   across the cluster is M.
2. **Given** N identical instances are running, **When** requests are sent over time,
   **Then** the requests are distributed across the instances rather than concentrated on one.

---

### User Story 5 - Receive NATS-native error responses on handler failure (Priority: P3)

When a handler throws or explicitly rejects a request, the caller receives a NATS-native
error response carrying an error code and message, rather than a hang or a malformed reply,
and the failure is visible in standard NATS statistics.

**Why this priority**: Error handling improves observability and robustness, but the happy
path (Stories 1-4) delivers usable value first. It ranks lowest of the must-have stories.

**Independent Test**: Invoke a handler that throws and one that explicitly rejects; confirm
the caller receives a NATS error response with a non-empty error code and that the error
counter in `nats micro stats` increments.

**Acceptance Scenarios**:

1. **Given** a handler that throws an unhandled exception, **When** a request reaches it,
   **Then** the caller receives a NATS error response with a non-empty error code and message.
2. **Given** a handler that explicitly rejects a request, **When** a request reaches it,
   **Then** the caller receives a NATS error response with an error code reflecting that
   rejection.
3. **Given** a request fails, **When** an operator runs `nats micro stats`, **Then** the
   error counter for the service has incremented.

---

### Edge Cases

- **Service shutdown**: When the service is brought down cleanly, in-flight requests are
  allowed to complete, the NATS connection is drained and closed, and the service no longer
  appears in `nats micro list`.
- **Service startup with no NATS configuration changes**: Bringing the service up registers
  it again on the NATS fabric with no manual NATS server-side configuration.
- **NATS server unreachable at startup**: The service must surface a clear failure rather
  than starting silently broken; behavior on later reconnection follows the NATS client
  library's standard reconnect semantics.
- **Request to an unhandled subject**: A subject with no matching handler produces no
  response from this service (standard NATS no-responder behavior applies).
- **Overlapping subject bindings**: Two handlers whose subjects overlap (e.g. a wildcard and
  a concrete subject) must produce predictable, documented routing rather than ambiguous
  double handling.
- **Empty payload**: A handler must be able to receive and return an empty (zero-byte)
  payload without error.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The library MUST let a developer declare an ordinary class as a NATS
  micro-service by adding it to a service project as a dependency and applying a class-level
  annotation that supplies at minimum a service name and a service version.
- **FR-002**: The library MUST let a developer mark individual methods of that class as
  handlers via a per-method annotation, each binding the method to a named NATS subject.
- **FR-002a**: Each declared class MUST expose exactly one NATS micro-service; exposing
  multiple services requires multiple declared classes.
- **FR-003**: A handler MUST be able to receive the raw request payload as bytes and MUST
  return the raw response payload as bytes; the library MUST NOT impose JSON or any other
  serialization. A handler MAY alternatively receive a request context object (see FR-010).
- **FR-004**: The library MUST subscribe each declared handler to its subject on the
  configured NATS server when the service starts.
- **FR-005**: The library MUST reply to each request with exactly the bytes the corresponding
  handler returned.
- **FR-006**: The library MUST register the service using the standard NATS micro-service
  mechanisms so that stock `nats micro` commands (`list`, `info`, `ping`, `stats`) recognize
  and report the service.
- **FR-007**: The service MUST advertise, via `nats micro info`, at minimum its name, its
  version, and the declared subjects of its endpoints.
- **FR-008**: The library MUST maintain per-service request statistics (including request
  counts and error counts) and expose them through the standard `nats micro stats` mechanism.
- **FR-009**: The library MUST allow a handler to be bound to a subject containing NATS
  wildcards, and MUST route any concrete subject covered by that wildcard to the handler.
- **FR-010**: The library MUST provide an optional request context object that a handler may
  declare as its parameter, exposing the concrete subject the request arrived on, the raw
  request payload bytes, and the matched wildcard tokens. Handlers that do not need this MAY
  declare a plain raw-bytes parameter instead.
- **FR-011**: When the service runs as multiple identical instances, the library MUST ensure
  each request is delivered to and handled by exactly one instance across the cluster
  (load-balanced single delivery) using a queue group whose name defaults to the service
  name and is overridable by the developer in the service declaration.
- **FR-012**: The library MUST open the NATS connection during service startup and MUST drain
  in-flight requests and close the connection during service shutdown.
- **FR-013**: When a handler throws an unhandled exception, the library MUST return a
  NATS-native error response to the caller carrying a generic error code (e.g. "500") and a
  message.
- **FR-014**: The library MUST provide a way for a handler to explicitly reject a request with
  a developer-chosen error code and message, such that the caller receives a NATS-native
  error response carrying that code and message.
- **FR-015**: The library MUST obtain NATS server connection settings and authentication
  credentials (e.g. token, NKey/JWT, TLS) from service configuration or environment, never
  hard-coded.
- **FR-016**: The library MUST integrate with the service lifecycle so that no separate
  bridge process or manual NATS-side configuration is required for the service to register
  and deregister.
- **FR-017**: The library MUST allow a class to declare multiple handler methods, each bound
  to its own subject, within a single declared service.
- **FR-018**: The feature MUST be usable entirely in user space (as a library dependency)
  without requiring changes to the underlying service SDK; any limitation arising from this
  (e.g. not being a runtime-recognized first-class endpoint type) MUST be documented for the
  developer.

### Out of Scope

- Any higher-level message protocol or envelope on top of NATS micro (structured
  prompt/response, agent protocol, streaming chunk framing, multi-message conversations).
  This feature is raw-payload request/reply only.
- Streaming a single logical response as multiple NATS messages; handlers return exactly one
  response payload per request.
- Publishing to arbitrary subjects unrelated to replying to an incoming request (fire-and-
  forget publication, fan-out).
- JetStream-backed durability, persistence, or replay; this feature targets core NATS
  request/reply plus micro discovery.
- Becoming a runtime-recognized, first-class endpoint type on par with built-in HTTP/gRPC/MCP
  endpoints, with compile-time validation, component-registry visibility, and built-in
  test-client support. This feature delivers the capability in user space and documents the
  gap rather than closing it.

### Key Entities *(include if feature involves data)*

- **NATS Micro-Service Declaration**: The developer's class marked as a NATS micro-service.
  Key attributes: service name, service version, the set of endpoint declarations it owns.
- **Subject Handler (Endpoint)**: A method of the declared class bound to a NATS subject.
  Key attributes: bound subject (which may contain wildcards), the handler logic, optional
  description metadata advertised via discovery.
- **Request**: An inbound NATS message addressed to a handled subject. Key attributes: raw
  payload bytes, the concrete subject it arrived on, matched wildcard tokens.
- **Response**: The outbound reply to a request — either raw payload bytes produced by the
  handler, or a NATS-native error carrying an error code and message.
- **Service Statistics**: Per-service counters tracking total requests and errors, exposed
  through standard NATS micro stats.

## Assumptions

The declaration shape, service-per-class rule, queue-group naming, error contract, and
wildcard recovery contract were resolved in the Clarifications section above. The remaining
reasonable defaults below were chosen where the input left a decision open.

- **Discovery metadata**: The minimum advertised metadata is name + version + endpoint
  subjects; per-endpoint descriptions are supported as optional additions. (Lower-impact
  open question; the default holds unless revised during planning.)
- The target NATS deployment is core NATS request/reply with the micro framework; no
  JetStream is assumed.
- The library is consumed by adding it as an ordinary build dependency to an existing Akka
  service project; this and all related features are designed for that distribution model.

## Dependencies

- A reachable NATS server (core NATS, with the micro-service framework) configured for the
  Akka service.
- The Akka service-lifecycle setup hook for opening and closing the NATS connection in step
  with the service.
- A third-party NATS client library, consumed as an ordinary build dependency.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can expose an existing class as a NATS micro-service by adding the
  library dependency and marking the class plus at least one method — with no separate bridge
  process and no manual NATS server-side configuration.
- **SC-002**: A `nats micro request` to a handled subject returns exactly the bytes the
  handler produced, byte-for-byte, for 100% of well-formed requests.
- **SC-003**: All four standard discovery commands (`nats micro list`, `info`, `ping`,
  `stats`) recognize and correctly report the Akka-backed service without any Akka-specific
  client or custom protocol.
- **SC-004**: With N identical instances running and M requests sent, the total number of
  handler invocations across the cluster equals M (single delivery), verified for at least
  one multi-instance configuration.
- **SC-005**: A request routed through a wildcard handler is handled, and the handler
  correctly reports the concrete subject for 100% of such requests.
- **SC-006**: A handler failure (thrown or explicitly rejected) results in a NATS error
  response with a non-empty error code in 100% of failure cases, and the `nats micro stats`
  error counter increments accordingly.
- **SC-007**: On clean shutdown, the service drains in-flight requests, closes its NATS
  connection, and no longer appears in `nats micro list`; on restart it re-registers
  automatically.
