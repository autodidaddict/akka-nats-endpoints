# Phase 0 Research: Synadia Agent Protocol Support

**Feature**: 002-synadia-agent-protocol | **Date**: 2026-05-22

All Technical Context items are resolved here. No `NEEDS CLARIFICATION` markers remain.

---

## R1. Protocol JSON encoding/decoding — which library?

**Decision**: Use the JSON support already bundled in `io.nats:jnats`
(`io.nats.client.support.JsonParser`, `JsonValue`, and the JSON write utilities). Add **no**
new dependency.

**Rationale**: The protocol's request envelope (`{"prompt": ..., "attachments": [...]}`),
typed response chunks (`{"type": ..., "data": ...}`), and heartbeat payloads are small,
flat, well-known shapes. `jnats` already ships a complete JSON parser/writer it uses for its
own service-info and stats messages, so the functionality is available within the existing
dependency tree. The constitution requires evaluating existing dependencies before adding
new ones; this passes that test.

**Alternatives considered**:
- *Jackson / Gson* — rejected: a new external dependency for shapes the existing dependency
  already covers; violates the constitution's dependency rule and Simplicity.
- *Hand-rolled string parsing* — rejected: the discrimination rule and nested chunk `data`
  values (string or object) need a real parser; hand-rolling is more error-prone than reusing
  a tested one.

---

## R2. Request discrimination (plain text vs. JSON envelope)

**Decision**: Implement the protocol §5 rule in `RequestDecoder`: skip leading whitespace
bytes; if the next byte is `{`, parse the payload as a JSON envelope; otherwise treat the
whole payload as UTF-8 text and promote it to `{"prompt": <text>}`. A structured envelope
missing a non-empty `prompt` is a `400` bad request. An envelope carrying `attachments` is a
`400` bad request for this release (attachments declared not accepted).

**Rationale**: Directly mandated by the protocol. Centralizing it in one decoder keeps the
handler path simple and makes the rule unit-testable without NATS.

**Alternatives considered**: Always-JSON parsing — rejected: the protocol explicitly requires
accepting bare plain text.

---

## R3. Streaming responses over NATS micro

**Decision**: A response is delivered by publishing messages **directly to the request's
reply subject** (`ServiceMessage.getReplyTo()`) on the shared `Connection`:
1. first, the mandatory `{"type":"status","data":"ack"}` chunk, emitted before invoking
   developer logic that could induce latency;
2. then one or more `{"type":"response","data":<text|object>}` chunks — one per developer
   emission in order;
3. finally a **headerless zero-byte** message as the stream terminator.
The single-shot handler form (`String` return) is framed as `ack` + one `response` chunk +
terminator by the same `ResponseStream`. The micro framework's `respond(...)` is **not** used
on the streaming path (it would publish an extra message); the framework still counts the
request because it wraps the handler invocation.

**Rationale**: NATS micro `respond` is single-reply, but the protocol's stream is just
multiple ordinary publishes to the reply subject followed by an empty terminator — fully
expressible with `connection.publish(replyTo, ...)`. Emitting `ack` first satisfies the
protocol's requirement that callers' inactivity timers reset before slow work.

**Alternatives considered**:
- *One `respond` with the whole stream concatenated* — rejected: not protocol-conformant;
  callers expect discrete chunks and an early `ack`.
- *JetStream-backed delivery* — rejected: out of scope per the protocol's own v0.3 scope and
  the spec.

---

## R4. Mid-stream agent queries (`query` chunk) and the reply timeout

**Decision**: To ask the caller a question mid-stream, `ResponseStream`:
1. creates a fresh inbox via `connection.createInbox()` and subscribes to it;
2. publishes a `{"type":"query","data":{"id":<uuid>,"reply_subject":<inbox>,"prompt":<text>}}`
   chunk to the reply subject;
3. waits for one message on the inbox subscription using `Subscription.nextMessage(Duration)`
   with a **configurable timeout, default 60 s**;
4. on a reply, returns it to the handler as a `QueryReply` carrying the answer; on timeout,
   returns a `QueryReply` whose `timedOut()` is `true`, leaving the handler to decide how to
   proceed (per the spec clarification — the request is **not** auto-failed).
The inbox subscription is unsubscribed after the single reply or the timeout.

**Rationale**: Matches protocol §6 (caller replies exactly once to `reply_subject`). A
bounded wait prevents a stuck handler; surfacing the timeout to the handler matches the
clarified requirement (FR-025) and keeps the agent in control of its own flow.

**Alternatives considered**:
- *Block indefinitely* — rejected by the spec clarification (risk of a permanently stuck
  handler).
- *Auto-fail the request on timeout* — rejected by the spec clarification; the handler may
  legitimately want to continue with a default.

---

## R5. NATS micro-service registration and discovery for agents

**Decision**: Each `@SynadiaAgent` registers as a NATS micro `Service` with `name = "agents"`
and `version` from the annotation (default e.g. `0.1.0`). Service-level metadata carries
`agent`, `owner`, `protocol_version = "0.3"`, and — when configured — `session`. Two
`ServiceEndpoint`s are added, both in queue group `"agents"`:
- `prompt` → subject `agents.prompt.{agent}.{owner}.{name}` with endpoint metadata
  `max_payload` (default `"1MB"`, configurable) and `attachments_ok = "false"`;
- `status` → subject `agents.status.{agent}.{owner}.{name}`.
Discovery (`$SRV.PING.agents`, `$SRV.INFO.agents`, `$SRV.INFO.agents.{id}`) is served
automatically by the `io.nats.service` framework once the service name and metadata are set.

**Rationale**: The protocol is deliberately "a NATS micro service"; using the framework's
own registration means standard `nats micro` / Synadia tooling discovers the agent with no
extra code, mirroring how feature 001 already relies on the framework for discovery.

**Alternatives considered**: Hand-rolled `$SRV.*` responders — rejected: re-creates a fragile
look-alike the tooling may not recognize; the framework already does it correctly.

---

## R6. Heartbeats and the status endpoint

**Decision**: A single `ScheduledExecutorService` owned by `NatsMicroRuntime` (created only
when at least one agent is registered) publishes each agent's beacon to the protocol-fixed
`agents.hb.{agent}.{owner}.{name}` subject at the configured cadence (default 30 s). A
`HeartbeatPublisher` builds the payload (`agent`, `owner`, optional `session`, `instance_id`,
`ts`, `interval_s`); `instance_id` is a per-process UUID generated once at startup, `ts` is
the current UTC instant per beacon. The `status` endpoint handler replies synchronously with
the **same** payload builder so callers can bootstrap liveness on demand. The scheduler is
shut down and a final implicit "no more beacons" is achieved simply by stopping the timer
during `NatsMicroRuntime.stop()`.

**Rationale**: One scheduler keeps lifecycle simple and bounded. Sharing the payload builder
between the periodic beacon and the status reply guarantees they are equivalent (spec FR-015).
A per-process UUID is the natural `instance_id` (the protocol calls it "framework-assigned").

**Alternatives considered**:
- *A thread per agent* — rejected: needless concurrency; one scheduled executor handles all
  agents.
- *Deriving `instance_id` from identity* — rejected: identity is shared by load-balanced
  instances; `instance_id` must be unique per process.

---

## R7. Error signaling

**Decision**: Validation failures (malformed JSON, missing `prompt`, oversize payload,
attachments present) and handler outcomes map to protocol §9 errors written as the NATS
micro-service error headers `Nats-Service-Error-Code` (numeric) and `Nats-Service-Error`
(text), optionally with a small JSON body `{"error":...,"message":...}`. A developer signals
an intentional rejection by throwing `SynadiaAgentException(int status, String message)`
(e.g. `403`, `429`); any other exception maps to `500`. On the streaming path an error is
written as an error-headed message followed by the zero-byte terminator so the caller's
stream still terminates cleanly. The library performs **no** authentication/authorization of
its own (spec clarification): `401`/`403` come only from the developer's handler.

**Rationale**: Reuses the same NATS-native error mechanism feature 001's `NatsHandlerException`
already relies on, and matches the protocol's §9 taxonomy. Terminating the stream after an
error prevents caller hangs (spec SC-006).

**Alternatives considered**: A bespoke error envelope without NATS error headers — rejected:
the protocol requires callers to inspect `Nats-Service-Error-Code` before the body.

---

## R8. Runtime and connection sharing (one connection for agents + micro-endpoints)

**Decision**: Reuse the existing `NatsMicroRuntime`. `register(Object)` inspects the instance
for `@SynadiaAgent` (vs. `@NatsMicroService`) and routes to `AgentReflector` or the existing
`AnnotationReflector`. `start()` opens one `Connection` and builds a `Service` for every
descriptor of either kind; for agent descriptors it additionally schedules heartbeats.
`stop()` cancels heartbeats, then drains and closes as today.

**Rationale**: Directly satisfies SC-007 (an agent and ordinary NATS micro-endpoints run in
one service over one connection) with the least machinery — no second runtime, no
shared-connection abstraction. The change to `NatsMicroRuntime` is a bounded branch.

**Alternatives considered**: A separate `SynadiaAgentRuntime` — rejected: it would either
open a second NATS connection (violating SC-007) or require a shared-`Connection` abstraction
threaded between two runtimes, which is more complex than one runtime accepting two
annotation kinds.

---

## R9. Annotation shape — `@SynadiaAgent` + `@PromptHandler`

**Decision**: A type-level `@SynadiaAgent` carries `agent`, `owner`, `name` (the identity
triple, all mandatory and non-blank), plus optional `version`, `session`, `heartbeat`
(seconds), `maxPayload`, and `queryTimeout` (seconds). A method-level `@PromptHandler` marks
the single mandatory prompt method. Reflection fails fast at registration if `@SynadiaAgent`
is absent, the identity is incomplete, or `@PromptHandler` methods number anything other than
exactly one, or the marked method has an unsupported signature.

**Rationale**: The user explicitly asked for a `@SynadiaAgent` annotation requiring mandatory
methods rather than inheritance, and this mirrors the existing `@NatsMicroService` +
`@NatsSubject` pattern (a type annotation plus a method annotation) the project already uses.

**Alternatives considered**:
- *Inheritance / interface* — rejected by the user's explicit request and inconsistent with
  the project's established annotation pattern.
- *Convention-named method (no `@PromptHandler`)* — rejected: an explicit annotation makes
  the mandatory method discoverable and the fail-fast error precise, consistent with
  `@NatsSubject`.
