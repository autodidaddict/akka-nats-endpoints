# Feature Specification: Synadia Agent Protocol Support

**Feature Branch**: `002-synadia-agent-protocol`
**Created**: 2026-05-22
**Status**: Draft
**Input**: User description: "The Akka NATS endpoints libraries must facilitate developers creating classes that expose the Synadia Agent Protocol. This protocol is a simple API exposed via NATS Micro Service. Developers should be able to provide implementations for the appropriate functions exposed via the Synadia Agent Protocol. The library class that allows developers to expose an agent in this manner should automatically implement the lower level portions of the protocol, and allow the right level of abstraction for variables that belong on replies and heartbeats. It is likely preferable that there be a 'SynadiaAgent' annotation that requires mandatory methods rather than using inheritance as this is in line with existing patterns already used and in the Akka SDK itself."

## Clarifications

### Session 2026-05-22

- Q: When the handler asks a mid-stream question and the caller never replies, what should happen? → A: The library waits a configurable timeout (default 60 seconds); on timeout the handler receives a timeout outcome and decides how to proceed.
- Q: What maximum payload size should the prompt endpoint advertise and enforce? → A: Default 1 MB, overridable on the `@SynadiaAgent` annotation.
- Q: Should the `@SynadiaAgent` annotation require a session label? → A: Optional; when unset it is omitted from heartbeats and status replies, so session-unaware agents are valid.
- Q: How should authentication/authorization be handled? → A: No built-in auth — transport security relies on NATS connection credentials, and authorization decisions (401/403) are made by the developer's handler; pre-handler rejection covers only malformed/oversized requests.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Expose an agent that answers prompts (Priority: P1)

A developer has an Akka service that can answer questions or perform work in response to a text prompt. They want any standard Synadia-compatible caller to discover that agent over NATS and send it prompts, without writing any NATS wiring, message framing, or protocol bookkeeping themselves.

The developer writes a plain class, marks it with a `@SynadiaAgent` annotation that carries the agent's identity (canonical agent id, owner, instance name), and supplies one method that receives a prompt and produces a response. They register the class with the library at service startup. From that point the agent is discoverable and callable by the standard Synadia tooling and any other protocol-compliant caller.

**Why this priority**: This is the core promise of the feature — turning an ordinary class into a protocol-compliant agent. Without it nothing else has value. It is also the smallest slice that delivers a usable, demonstrable agent.

**Independent Test**: Annotate a class with a single prompt-handling method, register it, start the service, and confirm with standard Synadia/NATS discovery tooling that the agent appears, then send it a prompt and receive a correct, protocol-framed response.

**Acceptance Scenarios**:

1. **Given** a class annotated as a Synadia agent with a prompt-handling method, **When** the service starts, **Then** the agent registers and is discoverable through the protocol's standard discovery mechanism with its declared identity and capability metadata.
2. **Given** a registered agent, **When** a caller sends a plain-text prompt to the agent's prompt endpoint, **Then** the caller receives the developer-produced response delivered as a protocol-conformant reply stream that terminates correctly.
3. **Given** a registered agent, **When** a caller sends a structured request envelope containing a prompt, **Then** the prompt text is extracted and passed to the developer's handler and the response is returned the same way as for plain text.
4. **Given** multiple instances of the same agent running together, **When** callers send prompts concurrently, **Then** each prompt is handled by exactly one instance (load-balanced), never duplicated.

---

### User Story 2 - Agent liveness is visible to callers (Priority: P2)

An operator or calling system needs to know whether a given agent instance is alive and reachable. The developer should not have to implement liveness signaling; the library should publish it automatically using identity and cadence the developer configures at a high level.

**Why this priority**: Liveness is required by the protocol for any production use, but an agent that only answers prompts is already demonstrably useful, so this follows P1.

**Independent Test**: Start a registered agent, observe that liveness beacons are published at the configured cadence with correct identity fields, and separately query the agent's status endpoint and confirm it returns a matching liveness payload on demand.

**Acceptance Scenarios**:

1. **Given** a running agent, **When** the configured heartbeat interval elapses, **Then** the library publishes a liveness beacon carrying the agent identity, instance identifier, timestamp, interval, and the session label when one is configured.
2. **Given** a running agent, **When** a caller queries the agent's status endpoint, **Then** the library responds immediately with a liveness payload equivalent to the heartbeat, without the developer writing a status handler.
3. **Given** a developer who has not customized heartbeat cadence, **When** the agent runs, **Then** beacons are published at a sensible default interval.
4. **Given** an agent instance that stops, **When** callers track liveness, **Then** beacons for that instance cease so callers can detect it as offline.

---

### User Story 3 - Handler problems become protocol errors (Priority: P3)

When a request is malformed, too large, or the developer's handler rejects it (for example as unauthorized) or fails, the caller must receive a well-formed protocol error rather than a hang or an opaque failure. The developer should be able to signal an intentional rejection with a meaningful status, and unexpected failures should be translated automatically.

**Why this priority**: Correct error behavior is needed for a robust agent, but the happy path (P1) and liveness (P2) deliver demonstrable value first.

**Independent Test**: Send a malformed/oversized request and an intentionally-rejected request, and confirm each returns a protocol error with the appropriate status code and human-readable description; cause the handler to throw and confirm the caller receives a translated internal-error response.

**Acceptance Scenarios**:

1. **Given** a registered agent, **When** a request arrives that is malformed or exceeds the declared maximum payload size, **Then** the caller receives a protocol error indicating a bad request.
2. **Given** a developer handler that intentionally rejects a request with a chosen status and message, **When** that request is received, **Then** the caller receives a protocol error carrying that status and message.
3. **Given** a developer handler that fails unexpectedly, **When** that request is received, **Then** the caller receives a protocol internal-error response and the reply stream is terminated cleanly.
4. **Given** any error response, **When** the caller inspects it, **Then** it carries the protocol's error code and description so the caller can react before reading the body.

---

### Edge Cases

- What happens when a request payload is leading-whitespace padded before the JSON envelope, or is plain text that is not JSON at all? (The library must apply the protocol's discrimination rule and promote plain text to a prompt.)
- What happens when a structured request envelope omits the required prompt field?
- What happens when a caller sends attachments to an agent that has declared it does not accept them?
- How does the system behave if the developer's handler takes a long time before producing any output? (The protocol requires an early acknowledgement chunk so callers do not time out.)
- What happens if a developer annotates a class but omits the mandatory prompt-handling method, or declares more than one?
- What happens when two registered agents declare the same identity, or an identity that is incomplete?
- What happens when the handler issues a mid-stream query and the caller never replies? (The library applies a configurable timeout, default 60 seconds, and surfaces a timeout outcome to the handler.)
- How does the agent behave on a graceful shutdown — are in-flight prompts allowed to finish and do beacons stop?
- What happens when the response stream's terminating signal is lost — can callers still recover via their inactivity timeout?

## Requirements *(mandatory)*

### Functional Requirements

#### Declaring an agent

- **FR-001**: The library MUST let a developer expose an agent by applying a `@SynadiaAgent` annotation to a plain class, without requiring the class to extend a library base class or implement a library interface (annotation-based, consistent with the existing `@NatsMicroService` / `@NatsSubject` pattern in this project).
- **FR-002**: The `@SynadiaAgent` annotation MUST carry the agent's protocol identity: the canonical agent identifier, the owner, and the instance name.
- **FR-003**: The library MUST require the annotated class to provide exactly one mandatory prompt-handling method, and MUST report a clear, fail-fast error at registration/startup if that method is missing or declared more than once.
- **FR-004**: The library MUST allow a developer to register one or more annotated agent classes with the runtime, consistent with how the existing NATS micro-service library is registered and driven from the service's setup lifecycle.
- **FR-005**: The prompt-handling method MUST receive the caller's prompt text at a usable level of abstraction, so the developer does not parse the raw request envelope; the developer MUST also be able to access the structured request envelope's fields when a structured request is sent.
- **FR-006**: The developer's prompt-handling method MUST be able to return the response content as a high-level value, leaving the library to perform all protocol message framing.

#### Protocol mechanics handled automatically

- **FR-007**: The library MUST register each agent as a NATS micro service under the protocol's discovery name so that standard Synadia/NATS discovery tooling lists it.
- **FR-008**: The library MUST publish the protocol-required service and endpoint metadata, including protocol version, agent identity, owner, and the prompt endpoint's declared capability metadata: the maximum payload size — defaulting to 1 MB and overridable on the `@SynadiaAgent` annotation — and whether attachments are accepted.
- **FR-009**: The library MUST expose the agent's prompt endpoint and status endpoint on the protocol's subject scheme, derived from the declared identity, and place both behind the protocol's shared queue group so concurrent instances load-balance requests.
- **FR-010**: The library MUST accept both plain-text requests and structured request envelopes, applying the protocol's discrimination rule (skip leading whitespace; treat as structured only when the envelope marker is present, otherwise promote plain text to a prompt).
- **FR-011**: The library MUST frame every response as a protocol-conformant reply stream: an initial acknowledgement signal emitted before any latency-inducing work, the developer's response content as typed content chunks, and the protocol's stream terminator.
- **FR-012**: The library MUST respond to the protocol's discovery requests with full service and endpoint information without developer involvement.

#### Liveness

- **FR-013**: The library MUST publish liveness beacons for each running agent instance on the protocol's fixed heartbeat subject at a configurable cadence, defaulting to a sensible interval when the developer does not specify one.
- **FR-014**: Each liveness beacon MUST carry the protocol-required fields: agent identity, owner, instance identifier, timestamp, interval, and — when configured — a session label. The library MUST populate runtime-derived fields (instance identifier, timestamp) itself and let the developer supply only the high-level configurable values (cadence and, optionally, session label). The session label is optional; when the developer does not configure one, the library MUST omit it from beacons and status replies rather than emitting an empty value.
- **FR-015**: The library MUST automatically serve the agent's status endpoint, replying on demand with a liveness payload equivalent to the heartbeat, without the developer implementing a status handler.
- **FR-016**: The library MUST stop publishing beacons for an instance when that instance shuts down.

#### Errors and validation

- **FR-017**: The library MUST reject malformed or oversized requests (exceeding the declared maximum payload size) with a protocol-conformant error response carrying the appropriate status code and human-readable description, before invoking the developer's handler. The library performs no authentication or authorization of its own: transport security relies on NATS connection credentials, and authorization decisions (e.g. 401/403) are made by the developer's handler per FR-018.
- **FR-018**: The library MUST give the developer's handler a way to intentionally reject a request with a chosen protocol status code and message, and surface that as a protocol error response to the caller.
- **FR-019**: The library MUST translate an unexpected handler failure into a protocol internal-error response and terminate the reply stream cleanly.
- **FR-020**: Error responses MUST carry the protocol's error code and description in the manner protocol-compliant callers expect, so callers can react before reading any body.

#### Lifecycle and compatibility

- **FR-021**: The library MUST integrate with the service's startup and shutdown lifecycle so agents register on startup and stop cleanly on shutdown, consistent with the existing NATS micro-service library's lifecycle integration.
- **FR-022**: The library MUST declare the protocol version it implements and behave according to the protocol's compatibility rules, tolerating unknown fields it receives.
- **FR-023**: The library MUST read its NATS connection configuration from the same configuration mechanism the existing NATS micro-service library uses, so an agent and existing NATS micro-endpoints can run in one service against one connection.

#### Streaming and interactive responses

- **FR-024**: The library MUST support incremental (multi-part) streamed responses, where the developer's handler emits response content progressively (e.g. token by token) and the library frames each emission as a protocol content chunk in order, ahead of the stream terminator.
- **FR-025**: The library MUST allow the developer's handler to ask the caller a clarifying question mid-response and obtain the caller's answer before continuing, handling the protocol's mid-stream query exchange (correlation identifier, reply subject, and awaiting the single reply) on the developer's behalf at a usable level of abstraction. The library MUST wait for the caller's reply for a configurable timeout (default 60 seconds); if the timeout elapses, it MUST surface a timeout outcome to the handler so the handler can decide how to proceed (rather than failing the request automatically).
- **FR-026**: The first release MUST declare that the prompt endpoint does not accept attachments, and MUST reject any request that carries attachments with a protocol bad-request error. Inbound and outbound attachment payload support is deferred to a later release.

### Key Entities *(include if feature involves data)*

- **Synadia Agent declaration**: A developer's class marked as a protocol agent. Carries the agent identity (canonical agent id, owner, instance name) and the mandatory prompt-handling method; optionally carries heartbeat and capability configuration.
- **Agent identity**: The triple of canonical agent id, owner, and instance name that distinguishes one agent (and its load-balanced instances) and from which protocol subjects are derived.
- **Prompt request**: The inbound work item — either plain prompt text or a structured envelope containing a prompt (attachment payloads are out of scope for the first release).
- **Agent response**: The developer-produced answer content, delivered to the caller as a protocol reply stream (acknowledgement, content, terminator).
- **Liveness beacon**: The periodic and on-demand signal describing a running agent instance — identity, instance identifier, timestamp, interval, and an optional session label (present only when the agent is configured as session-aware).
- **Protocol error**: A failure outcome carrying a status code, a human-readable description, and optional machine-readable detail.
- **Capability metadata**: The agent's declared limits and options advertised at discovery time — maximum payload size and whether attachments are accepted.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can expose a working, discoverable agent by writing a single annotated class with one prompt-handling method and registering it — no NATS wiring, message framing, subject construction, or heartbeat code written by hand.
- **SC-002**: An agent created with the library is discovered and successfully prompted by standard Synadia/NATS tooling with no agent-specific client or custom protocol on the caller side.
- **SC-003**: 100% of the protocol's mandatory agent operations (registration with required metadata, prompt and status endpoints behind the shared queue group, early acknowledgement, correctly framed and terminated response streams, heartbeats, discovery responses, and protocol error signaling) are handled by the library without developer code.
- **SC-004**: Of the protocol fields that appear on replies and heartbeats, every field that can be derived at runtime is populated by the library automatically; the developer supplies only high-level values (identity, response content, heartbeat cadence, session label).
- **SC-005**: A misconfigured agent (missing or duplicate prompt-handling method, incomplete identity) is rejected at startup with a clear, actionable error rather than failing silently or at first request.
- **SC-006**: Every request outcome — success, intentional rejection, malformed/oversized input, and unexpected handler failure — produces a protocol-conformant reply or error that a compliant caller can interpret, with no caller-side hang.
- **SC-007**: An agent and the existing NATS micro-endpoints can run together in one Akka service over a single configured NATS connection.

## Assumptions

- The "users" of this feature are developers building Akka services; the protocol's "callers" are external Synadia-compatible clients and tooling, treated as an integration surface, not users to be designed for here.
- The feature targets Synadia Agent Protocol version 0.3 as published at the referenced specification, including its explicitly unstable 0.x compatibility expectations.
- The library is delivered as part of, or alongside, the existing reusable NATS micro-endpoint library and reuses its connection configuration and service-lifecycle integration; it does not introduce a separate connection or configuration mechanism.
- An agent runs inside an Akka service that already has at least one Akka component, consistent with the existing library's documented user-space limitation; this feature does not change that limitation.
- Attachment payloads on requests and responses are deferred to a later release; the first release advertises and enforces no-attachments. The protocol's reserved-but-unimplemented chunked attachment upload endpoint is likewise out of scope.
- Progressive (multi-chunk) streamed responses and agent-initiated mid-stream queries are both in scope for the first release.
- End-to-end encryption and strong agent identity are out of scope, consistent with the protocol's own v0.3 scope.
- The developer remains responsible for the agent's actual reasoning/answering logic; the library only provides protocol exposure.
- The library provides no authentication or authorization mechanism of its own; transport security is delegated to NATS connection credentials and authorization is the developer handler's responsibility.
