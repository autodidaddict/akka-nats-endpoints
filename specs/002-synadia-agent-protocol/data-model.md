# Phase 1 Data Model: Synadia Agent Protocol Support

**Feature**: 002-synadia-agent-protocol | **Date**: 2026-05-22

The "data" of this feature is the set of in-memory types that model an agent declaration, the
protocol messages, and the reflected runtime metadata. Nothing is persisted. Public API types
live in `blog.kevinhoffman.akka.nats.synadia`; reflected/internal types in
`blog.kevinhoffman.akka.nats.synadia.internal`.

---

## 1. Agent identity

The triple from which every protocol subject is derived.

| Field | Type | Rules |
|-------|------|-------|
| `agent` | String | Canonical agent id. Non-blank. Subject-token safe (no `.`, `*`, `>`, whitespace). |
| `owner` | String | Operator/account. Non-blank. Subject-token safe. |
| `name` | String | Instance name. Non-blank. Subject-token safe. |

Derived subjects (`SynadiaSubjects`):
- prompt: `agents.prompt.{agent}.{owner}.{name}`
- status: `agents.status.{agent}.{owner}.{name}`
- heartbeat: `agents.hb.{agent}.{owner}.{name}`

Validation: an incomplete or non-token-safe identity is a fail-fast error at registration
(spec SC-005).

---

## 2. `@SynadiaAgent` annotation (public, type-level)

Declares a plain class as a Synadia agent.

| Element | Type | Default | Meaning |
|---------|------|---------|---------|
| `agent` | String | — (required) | Canonical agent id. |
| `owner` | String | — (required) | Owner/account. |
| `name` | String | — (required) | Instance name. |
| `version` | String | `"0.1.0"` | Harness semver advertised via discovery. |
| `session` | String | `""` | Optional session label. Empty = session-unaware: omitted from heartbeats/status. |
| `heartbeat` | int | `30` | Heartbeat cadence in seconds. Must be > 0. |
| `maxPayload` | String | `"1MB"` | Advertised/enforced max request payload size. |
| `queryTimeout` | int | `60` | Seconds to await a mid-stream query reply. Must be > 0. |

## 3. `@PromptHandler` annotation (public, method-level)

Marks the single mandatory prompt-handling method. Exactly one per `@SynadiaAgent` class.

Permitted signatures (validated by reflection):

| Signature | Behavior |
|-----------|----------|
| `String handle(String prompt)` | Simplest single-shot form. |
| `String handle(PromptRequest request)` | Single-shot with structured request access. |
| `void handle(PromptRequest request, AgentResponse response)` | Streaming / interactive form. |

The return value of the `String` forms becomes a single `response` chunk. The `void` form
writes chunks through `AgentResponse`.

---

## 4. `PromptRequest` (public record)

The inbound work item handed to the handler.

| Field | Type | Notes |
|-------|------|-------|
| `prompt` | String | The extracted prompt text. Never blank (a blank/missing prompt is rejected upstream). |
| `structured` | boolean | `true` if the caller sent a JSON envelope, `false` if plain text was promoted. |
| `subject` | String | The concrete subject the request arrived on. |

(No attachment field — attachments are out of scope for this release; an attachment-bearing
request is rejected before a `PromptRequest` is built.)

---

## 5. `AgentResponse` (public interface) + `ResponseStream` (internal impl)

The writer passed to the streaming handler form; the library frames everything around it.

Operations:

| Method | Effect |
|--------|--------|
| `emit(String text)` | Publishes one `{"type":"response","data":text}` chunk in order. |
| `query(String prompt)` → `QueryReply` | Publishes a `query` chunk, awaits one caller reply within `queryTimeout`. |

Lifecycle handled by the library, not the developer:
- the `ack` chunk is emitted automatically before the handler runs;
- the zero-byte terminator is emitted automatically after the handler returns;
- an exception from the handler is converted to an error-headed message followed by the
  terminator.

State (internal): `acked` (boolean), `replySubject` (String), `connection`.

---

## 6. `QueryReply` (public record) — result of a mid-stream query

| Field | Type | Notes |
|-------|------|-------|
| `answer` | String | The caller's reply text. Empty when `timedOut` is `true`. |
| `timedOut` | boolean | `true` if no reply arrived within `queryTimeout`. |

Helper: `answer()` accessor; `timedOut()` accessor. The handler decides how to proceed on a
timeout (spec FR-025).

---

## 7. Response chunk (internal — wire shape)

Typed JSON chunk published to the reply subject. Modeled by `ProtocolJson`.

| `type` | `data` shape | When |
|--------|--------------|------|
| `status` | `"ack"` | Mandatory first chunk. |
| `response` | string (or object `{"text":...}`) | One per `emit(...)` / the single-shot return. |
| `query` | object: `id` (uuid), `reply_subject` (inbox), `prompt` (text) | One per `query(...)`. |
| — | zero-byte headerless message | Stream terminator (always last). |

---

## 8. `LivenessBeacon` (internal record) — heartbeat & status payload

Built by `HeartbeatPublisher`; published periodically on the heartbeat subject and returned
synchronously by the status endpoint.

| Field | Type | Notes |
|-------|------|-------|
| `agent` | String | From identity. |
| `owner` | String | From identity. |
| `session` | String (optional) | Omitted from JSON entirely when not configured. |
| `instanceId` | String | Per-process UUID, generated once at runtime startup. |
| `ts` | String | Current UTC instant, ISO-8601, per beacon. |
| `intervalS` | int | The configured heartbeat cadence in seconds. |

---

## 9. `CapabilityMetadata` (internal) — advertised at discovery

Endpoint metadata attached to the `prompt` `ServiceEndpoint`.

| Field | Type | Value this release |
|-------|------|--------------------|
| `maxPayload` | String | From `@SynadiaAgent.maxPayload` (default `"1MB"`). |
| `attachmentsOk` | boolean | Always `false` this release; advertised as `"false"`. |

Service-level metadata: `agent`, `owner`, `protocol_version = "0.3"`, optional `session`.

---

## 10. `ProtocolError` (internal) — failure outcome

| Field | Type | Notes |
|-------|------|-------|
| `code` | int | §9 taxonomy: 400 / 401 / 403 / 404 / 409 / 429 / 500. |
| `message` | String | Human-readable description → `Nats-Service-Error` header. |

Sources: pre-handler validation → `400`; `SynadiaAgentException` → developer-chosen code;
any other handler exception → `500`. Written as NATS micro error headers, optionally with a
small JSON body.

`SynadiaAgentException` (public): `RuntimeException` with an `int code` and a message — the
developer-facing way to raise a `ProtocolError`, analogous to the existing
`NatsHandlerException`.

---

## 11. `AgentDescriptor` (internal) — reflected, validated agent metadata

Produced by `AgentReflector` from a registered `@SynadiaAgent` instance; the agent-side
analogue of feature 001's `ServiceDescriptor`.

| Field | Type | Notes |
|-------|------|-------|
| `instance` | Object | The developer's registered agent instance. |
| `identity` | AgentIdentity | The validated `agent` / `owner` / `name` triple. |
| `version` | String | Advertised version. |
| `session` | String (optional) | Optional session label. |
| `heartbeatSeconds` | int | Validated cadence. |
| `maxPayload` | String | Advertised/enforced limit. |
| `queryTimeoutSeconds` | int | Validated mid-stream query timeout. |
| `promptMethod` | Method | The reflected `@PromptHandler` method (accessible). |
| `promptForm` | enum | `STRING_IN`, `REQUEST_IN`, `STREAMING` — which permitted signature. |

---

## 12. Runtime states (`NatsMicroRuntime`, reused)

Unchanged from feature 001: `NEW → RUNNING → STOPPED`. `register()` is valid only in `NEW`
and now accepts `@SynadiaAgent` instances too; `start()` opens the connection, builds a
`Service` per descriptor, and schedules heartbeats for agent descriptors; `stop()` cancels
the heartbeat scheduler, then drains and closes.

---

## Relationships

```text
@SynadiaAgent class ──reflected by AgentReflector──> AgentDescriptor
AgentDescriptor.identity ──SynadiaSubjects──> prompt / status / hb subjects
NatsMicroRuntime ──builds──> NATS micro Service { prompt endpoint, status endpoint }
                └──schedules──> HeartbeatPublisher ──publishes──> LivenessBeacon
prompt request ──RequestDecoder──> PromptRequest ──@PromptHandler──> String | AgentResponse
AgentResponse (ResponseStream) ──publishes──> ack chunk, response chunks, [query chunk], terminator
failure ──> ProtocolError ──> NATS error headers
```
