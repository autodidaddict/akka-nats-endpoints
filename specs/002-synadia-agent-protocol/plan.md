# Implementation Plan: Synadia Agent Protocol Support

**Branch**: `002-synadia-agent-protocol` | **Date**: 2026-05-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-synadia-agent-protocol/spec.md`

## Summary

Extend the existing NATS endpoint library so a developer can expose an Akka service as a
**Synadia Agent** (Synadia Agent Protocol v0.3) over NATS. A developer annotates a plain
class with `@SynadiaAgent` (carrying the agent identity — canonical agent id, owner, instance
name — plus optional version, session label, heartbeat cadence, max payload, query timeout)
and marks exactly one method with `@PromptHandler`. The library reflects the annotations,
registers a NATS micro `Service` named `agents` with protocol-fixed `prompt` and `status`
endpoints behind the shared `agents` queue group, derives the protocol subjects from the
identity, publishes liveness beacons on `agents.hb.{agent}.{owner}.{name}`, and frames every
response as a protocol-conformant chunk stream (mandatory `ack`, typed `response` chunks,
zero-byte terminator). Discovery (`$SRV.PING/INFO.agents`) is provided by the NATS micro
framework, so standard Synadia/NATS tooling recognizes the agent.

The prompt handler works at a high level of abstraction: it receives a `PromptRequest` (the
extracted prompt text plus structured envelope access) and either returns a `String`
(single-shot — the library frames `ack` + one `response` chunk + terminator) or accepts an
`AgentResponse` writer to emit content progressively and to ask the caller mid-stream
questions. The library performs all low-level protocol mechanics; the developer supplies only
identity, response content, and high-level configuration.

The capability is delivered **inside the existing `nats-micro-endpoint` library module** in a
new `synadia` package, and is driven from the **same `NatsMicroRuntime`** the existing
library already exposes — `register()` accepts `@SynadiaAgent` instances alongside
`@NatsMicroService` instances, so an agent and ordinary NATS micro-endpoints share one NATS
connection and one service lifecycle (spec SC-007).

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**:
- `io.nats:jnats` 2.21.1 — NATS client, the `io.nats.service` micro framework, **and** its
  bundled JSON support (`io.nats.client.support.JsonParser` / `JsonValue` / write utilities),
  used for the protocol's request envelope, response chunks, and heartbeat payloads. **No new
  dependency** is introduced — JSON is handled with what `jnats` already ships.
- `com.typesafe:config` (`provided`) — reused via the existing `NatsConnectionSettings`.
- The library module continues to depend on **nothing from the Akka SDK**. The sample module
  depends on the Akka SDK (`akka-javasdk-parent` 3.5.19).
- `io.nats:jnats-server-runner` 2.0.0 (test scope) — embedded `nats-server` for integration tests.
**Storage**: N/A — request/reply over NATS, no persistence.
**Testing**: JUnit 5, AssertJ; embedded `nats-server` via `jnats-server-runner`.
**Target Platform**: JVM on Linux server; Akka runtime, deployed as one-to-many instances.
**Project Type**: Multi-module Maven — capability added to the existing published library
module; exercised by the existing sample module.
**Performance Goals**: No hard target. The mandatory `ack` chunk is emitted before any
latency-inducing work so callers do not time out; heartbeats default to a 30 s cadence.
**Constraints**: Synadia Agent Protocol v0.3 conformance; protocol-fixed subject scheme and
`agents` queue group; raw bytes carry JSON envelopes/chunks; default max payload 1 MB
(configurable); mid-stream query reply timeout default 60 s; session label optional; no
built-in auth (transport credentials + handler-level rejection); user-space only (no SDK
changes). Attachments out of scope for this release.
**Scale/Scope**: Small addition (~14 new classes plus a modest `NatsMicroRuntime` extension).
Multi-instance Akka deployment is the default operating mode; the `agents` queue group
load-balances prompts across instances.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

### I. Akka SDK First (NON-NEGOTIABLE) — PASS with documented deviations

- This is **not** an Akka SDK component type. As with feature 001, there is no SDK primitive
  for NATS micro-service / Synadia-agent participation; the capability runs in user space on
  the supported `ServiceSetup` lifecycle hook via `NatsMicroRuntime`. The deviation is
  identical in kind to the one already sanctioned for feature 001 and is recorded in
  Complexity Tracking.
- **No new external dependency is added.** `io.nats:jnats` (already a main dependency) and
  `io.nats:jnats-server-runner` (already a test dependency) cover the protocol, the micro
  framework, JSON encoding/decoding, and integration testing. Per the constitution's
  dependency rule, JSON handling was evaluated against the existing dependency tree and the
  bundled `io.nats.client.support` JSON utilities are sufficient — Jackson/Gson are **not**
  added.

### II. Design Principles — PASS

- **Domain independence**: The prompt handler receives a library `PromptRequest` and returns
  a `String` or writes to a library `AgentResponse`; no NATS framework type leaks into the
  developer's logic. The agent's reasoning logic stays free of protocol concerns.
- **API isolation**: The public surface (`@SynadiaAgent`, `@PromptHandler`, `PromptRequest`,
  `AgentResponse`, `SynadiaAgentException`) is distinct from any domain model.
- **Single responsibility**: Subject derivation, request decoding, response/stream framing,
  heartbeat publishing, and reflection are separate focused classes in a `synadia.internal`
  package.
- **Descriptive naming**: `SynadiaAgentRuntime` behavior folded into `NatsMicroRuntime`;
  `HeartbeatPublisher`, `ResponseStream`, `AgentReflector`, `RequestDecoder` — no generic
  `Manager`/`Service`/`Event` names.

### III. Test Coverage — PASS

- Delivered with unit tests (annotation reflection & fail-fast validation, subject
  derivation, request discrimination/envelope decode, chunk framing) and integration tests
  against an embedded NATS server (discovery & metadata, plain-text and structured prompts,
  streamed multi-chunk responses, mid-stream query round-trip incl. timeout, heartbeats &
  status endpoint, error/oversized handling, multi-instance single delivery). Test names
  describe behavior.

### IV. Simplicity — PASS

- The capability reuses the existing `NatsMicroRuntime`, connection factory, and
  `NatsConnectionSettings` rather than introducing a parallel runtime/connection — one
  connection, one lifecycle (YAGNI, and it directly satisfies SC-007).
- Explicit instance registration (no classpath scanning). No SDK extension point is built.
  Attachments, the reserved `attachments` verb, encryption, and strong identity are excluded
  per spec. Flat structure: a thin public API plus a `synadia.internal` package.

**Result**: GATE PASSED. Deviations are limited, spec-sanctioned, and tracked below.

## Project Structure

### Documentation (this feature)

```text
specs/002-synadia-agent-protocol/
├── plan.md              # This file (/akka:plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── synadia-agent-api.md
├── checklists/
│   └── requirements.md  # from /akka:specify
└── tasks.md             # Phase 2 output (/akka:tasks — NOT created here)
```

### Source Code (repository root)

The capability is added to the **existing** `nats-micro-endpoint` library module and
exercised by the **existing** `nats-micro-sample` module. New paths are marked `(new)`;
`(extend)` marks an existing file modified.

```text
nats-micro-endpoint/               # LIBRARY module — published artifact (unchanged coordinates)
└── src/
    ├── main/java/blog/kevinhoffman/akka/nats/
    │   ├── NatsMicroRuntime.java                 # (extend) register() also accepts @SynadiaAgent
    │   │                                         #   instances; schedules/stops heartbeats
    │   ├── NatsConnectionSettings.java           # (reused, unchanged)
    │   └── synadia/                              # (new) public API for Synadia agents
    │       ├── SynadiaAgent.java                 # (new) @SynadiaAgent type-level annotation
    │       ├── PromptHandler.java                # (new) @PromptHandler method-level annotation
    │       ├── PromptRequest.java                # (new) extracted prompt text + envelope access
    │       ├── AgentResponse.java                # (new) writer: emit chunks, ask() mid-stream queries
    │       ├── QueryReply.java                   # (new) result of a mid-stream query (reply | timeout)
    │       ├── SynadiaAgentException.java        # (new) explicit rejection (protocol status + message)
    │       ├── package-info.java                 # (new) public API boundary
    │       └── internal/                         # (new) non-public implementation
    │           ├── AgentDescriptor.java          # (new) reflected, validated agent metadata
    │           ├── AgentReflector.java           # (new) reflects @SynadiaAgent / @PromptHandler
    │           ├── SynadiaSubjects.java          # (new) derives prompt/status/hb subjects
    │           ├── ProtocolJson.java             # (new) envelope/chunk/heartbeat JSON via jnats support
    │           ├── RequestDecoder.java           # (new) discrimination rule + envelope decode
    │           ├── ResponseStream.java           # (new) AgentResponse impl: ack, chunks, terminator, query
    │           ├── PromptMessageHandler.java     # (new) ServiceMessageHandler for the prompt endpoint
    │           ├── StatusMessageHandler.java     # (new) ServiceMessageHandler for the status endpoint
    │           ├── HeartbeatPublisher.java       # (new) scheduled beacon + shared liveness payload
    │           └── package-info.java             # (new) marks the package non-public
    └── test/java/blog/kevinhoffman/akka/nats/synadia/
        ├── AgentReflectorTest.java               # (new) unit — validation & fail-fast
        ├── SynadiaSubjectsTest.java              # (new) unit — subject derivation
        ├── RequestDecoderTest.java               # (new) unit — discrimination rule, envelope decode
        └── ProtocolJsonTest.java                 # (new) unit — chunk/heartbeat encode/decode

nats-micro-sample/                 # SAMPLE module — runnable Akka service, not published
└── src/
    ├── main/java/blog/kevinhoffman/sample/
    │   └── EchoSynadiaAgent.java                 # (new) sample @SynadiaAgent exercised by tests
    │   # Bootstrap.java                          # (extend) register the agent with NatsMicroRuntime
    └── test/java/blog/kevinhoffman/sample/
        └── SynadiaAgentIntegrationTest.java      # (new) discovery, prompts, streaming, query, heartbeat, errors
```

**Structure Decision**: Capability added to the existing multi-module build — **no new
module, no new published artifact, no new dependency**. The Synadia agent public API lives in
`blog.kevinhoffman.akka.nats.synadia`; non-public classes in `synadia.internal`. It is driven
from the existing `NatsMicroRuntime`, which is extended to recognize `@SynadiaAgent`
instances so an agent and ordinary NATS micro-endpoints share one connection and one
lifecycle. See `contracts/synadia-agent-api.md`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Feature is not an Akka SDK component type (runs in user space via `ServiceSetup`/`NatsMicroRuntime`) | The SDK has no NATS/Synadia-agent endpoint primitive; spec scopes this as a user-space library, consistent with feature 001 | A first-class SDK endpoint type would require SDK changes that are out of scope; the spec records the gap as a finding |
| `NatsMicroRuntime` gains a second descriptor kind and a heartbeat scheduler | SC-007 requires an agent and ordinary NATS micro-endpoints to share one NATS connection and lifecycle | A separate `SynadiaAgentRuntime` would open a second connection or need a shared-connection abstraction — more moving parts than a bounded branch in one runtime |

*(No new external dependency is added, so — unlike feature 001 — there is no dependency row.)*

## Phase 0 — Research

See [research.md](./research.md). All Technical Context items are resolved there; no
`NEEDS CLARIFICATION` markers remain. Key decisions:

- Protocol JSON via the JSON support already bundled in `io.nats:jnats` — no new dependency.
- Streamed responses are published directly to the request's reply subject (`ack` chunk,
  `response` chunks, zero-byte terminator); the framework `respond` is reserved for the
  single-shot path and for nothing on the streaming path.
- Mid-stream queries use a fresh inbox subscription with a bounded `nextMessage` wait
  (default 60 s); a timeout is surfaced to the handler, not auto-failed.
- Heartbeats run on a single `ScheduledExecutorService` owned by `NatsMicroRuntime`, started
  and stopped with the runtime; the status endpoint replies with the same payload builder.
- One runtime, one connection: `NatsMicroRuntime.register()` accepts both annotation kinds.

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — entities: agent declaration & descriptor, agent
  identity, prompt request, response chunk stream, mid-stream query, liveness beacon,
  capability metadata, protocol error.
- [contracts/synadia-agent-api.md](./contracts/synadia-agent-api.md) — the library's public
  API contract (annotations, handler signatures, `AgentResponse`/`PromptRequest`, runtime
  registration) and the external Synadia Agent Protocol wire contract the feature satisfies.
- [quickstart.md](./quickstart.md) — end-to-end walkthrough: declare an agent, register it,
  run, and exercise it with the `nats` CLI / Synadia tooling.

Post-design Constitution re-check: **PASS** — the design adds no dependencies, keeps domain
logic free of framework types, reuses the existing runtime, and stays within the simple flat
structure. No new violations.

## Phase 2 — Next Step

Run `/akka:tasks` to generate the dependency-ordered `tasks.md`. This command stops here.
