# Tasks: Synadia Agent Protocol Support

**Input**: Design documents from `/specs/002-synadia-agent-protocol/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/synadia-agent-api.md

**Tests**: Test tasks ARE included — the project constitution (Principle III, Test Coverage)
requires every behavioral change to ship with tests.

**Organization**: Tasks are grouped by user story. US1 is the MVP; US2 and US3 extend the
agent's NATS micro `Service` built in US1.

## Path Conventions

Capability added to the existing `nats-micro-endpoint` library module and exercised by the
existing `nats-micro-sample` module. Repository-root-relative paths:

- Library main: `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/`
- Library tests: `nats-micro-endpoint/src/test/java/blog/kevinhoffman/akka/nats/`
- Sample main: `nats-micro-sample/src/main/java/blog/kevinhoffman/sample/`
- Sample tests: `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Package scaffolding. No new Maven dependency — `io.nats:jnats` (already present)
provides the NATS client, the micro framework, and the JSON utilities (research R1).

- [X] T001 [P] Create the public `synadia` package with `package-info.java` (API-boundary javadoc) at `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/package-info.java`
- [X] T002 [P] Create the non-public `synadia.internal` package with `package-info.java` (marks the package an internal implementation detail) at `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/package-info.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The public annotations/types and the reflection + runtime registration that
every user story depends on — an agent must be declarable and registrable before prompts,
liveness, or error handling can be exercised.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Create the `@SynadiaAgent` type-level annotation (`agent`, `owner`, `name`, `version`, `session`, `heartbeat`, `maxPayload`, `queryTimeout` per data-model §2 / contract A1) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/SynadiaAgent.java`
- [X] T004 [P] Create the `@PromptHandler` method-level annotation (contract A2) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/PromptHandler.java`
- [X] T005 [P] Create the `PromptRequest` record (`prompt`, `structured`, `subject`; non-blank `prompt` invariant per data-model §4) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/PromptRequest.java`
- [X] T006 [P] Create the `SynadiaAgentException` runtime exception (`int code` + message, contract A5, analogous to `NatsHandlerException`) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/SynadiaAgentException.java`
- [X] T007 [P] Create `SynadiaSubjects` deriving the `prompt` / `status` / `hb` subjects from the identity triple, with subject-token-safety validation (data-model §1) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/SynadiaSubjects.java`
- [X] T008 [P] Create `ProtocolJson` to encode/decode the request envelope, typed chunks (`status`/`response`/`query`), and the heartbeat payload using `io.nats.client.support` JSON utilities; omit `session` from output when unset (research R1, data-model §7/§8) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/ProtocolJson.java`
- [X] T009 [P] Create the `AgentDescriptor` record (reflected, validated agent metadata incl. `promptForm` enum per data-model §11) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/AgentDescriptor.java`
- [X] T010 Create `AgentReflector` that reflects a `@SynadiaAgent` instance into an `AgentDescriptor`, fail-fast on missing annotation, incomplete/non-token identity, zero-or-multiple `@PromptHandler`, unsupported handler signature, non-positive `heartbeat`/`queryTimeout` (contract A1/A2) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/AgentReflector.java` (depends on T003–T009)
- [X] T011 Extend `NatsMicroRuntime.register(Object)` to detect `@SynadiaAgent` (vs `@NatsMicroService`), route to `AgentReflector`, and store agent descriptors in a separate list (research R8) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/NatsMicroRuntime.java` (depends on T010)
- [X] T012 [P] Unit test `SynadiaSubjectsTest` — subject derivation and token-safety rejection in `nats-micro-endpoint/src/test/java/blog/kevinhoffman/akka/nats/synadia/SynadiaSubjectsTest.java` (depends on T007)
- [X] T013 [P] Unit test `ProtocolJsonTest` — round-trip of `status`/`response`/`query` chunks and beacon payload; `session` omitted when unset in `nats-micro-endpoint/src/test/java/blog/kevinhoffman/akka/nats/synadia/ProtocolJsonTest.java` (depends on T008)
- [X] T014 [P] Unit test `AgentReflectorTest` — fail-fast for every invalid case in contract A1/A2 plus acceptance of each valid handler signature in `nats-micro-endpoint/src/test/java/blog/kevinhoffman/akka/nats/synadia/AgentReflectorTest.java` (depends on T010)

**Checkpoint**: An agent can be declared and registered (validated, fail-fast). User stories can now begin.

---

## Phase 3: User Story 1 - Expose an agent that answers prompts (Priority: P1) 🎯 MVP

**Goal**: A `@SynadiaAgent` class registers as a discoverable NATS micro service named
`agents`, exposes its `prompt` endpoint behind the `agents` queue group, and answers plain
or structured prompts with a protocol-conformant chunk stream — including progressive
multi-chunk streaming and mid-stream queries.

**Independent Test**: Register the sample agent, start the service, and confirm with
`nats micro info agents` that it is discoverable with correct metadata; send a plain-text
and a JSON-envelope prompt and confirm an `ack` chunk, `response` chunk(s), and a zero-byte
terminator come back; confirm a streaming handler emits multiple `response` chunks and a
mid-stream `query` round-trips.

### Tests for User Story 1 ⚠️ (write first; they fail until implementation lands)

- [X] T015 [P] [US1] Unit test `RequestDecoderTest` — discrimination rule (leading whitespace, `{`→JSON), plain-text promotion, structured-envelope decode, missing-`prompt` and attachments rejection (contract B2/B4) in `nats-micro-endpoint/src/test/java/blog/kevinhoffman/akka/nats/synadia/RequestDecoderTest.java`
- [X] T016 [US1] Integration test `SynadiaPromptIntegrationTest` against an embedded `nats-server` — discovery & service/endpoint metadata; plain-text prompt; JSON-envelope prompt; multi-chunk streamed response; mid-stream query round-trip and the query-timeout path; multi-instance single delivery via the `agents` queue group; an agent coexisting with a `@NatsMicroService` on one connection (contract B1/B2, spec SC-007) in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/SynadiaPromptIntegrationTest.java`

### Implementation for User Story 1

- [X] T017 [P] [US1] Create `RequestDecoder` applying the §5 discrimination rule and decoding into a `PromptRequest`; reject a missing/blank `prompt` and any `attachments` as a `400` `ProtocolError` (research R2, contract B2) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/RequestDecoder.java`
- [X] T018 [P] [US1] Create the `QueryReply` record (`answer`, `timedOut`, data-model §6 / contract A4) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/QueryReply.java`
- [X] T019 [P] [US1] Create the `AgentResponse` public interface (`emit(String)`, `query(String)→QueryReply`, contract A4) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/AgentResponse.java`
- [X] T020 [US1] Create `ResponseStream` implementing `AgentResponse`: publish the mandatory `ack` chunk, ordered `response` chunks, and the zero-byte terminator to the request reply subject; implement `query(...)` via a fresh inbox subscription with a bounded `nextMessage` wait (default 60 s) returning a `QueryReply` (research R3/R4, data-model §5) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/ResponseStream.java` (depends on T018, T019)
- [X] T021 [US1] Create `PromptMessageHandler` (`ServiceMessageHandler` for the `prompt` endpoint): decode via `RequestDecoder`, emit `ack`, invoke the `@PromptHandler` method per its reflected `promptForm` (String-in / PromptRequest-in / streaming), frame the result through `ResponseStream`, terminate the stream (research R3) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/PromptMessageHandler.java` (depends on T017, T020)
- [X] T022 [US1] Extend `NatsMicroRuntime` to build, for each agent descriptor, a NATS micro `Service` named `agents` (service metadata `agent`/`owner`/`protocol_version`/optional `session`) with a `prompt` `ServiceEndpoint` on the derived subject, queue group `agents`, and capability metadata (`max_payload`, `attachments_ok=false`); start it in `start()` (research R5, contract B1) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/NatsMicroRuntime.java` (depends on T021)
- [X] T023 [P] [US1] Create the sample `EchoSynadiaAgent` (`@SynadiaAgent` with a single-shot `@PromptHandler` and a streaming/query example) in `nats-micro-sample/src/main/java/blog/kevinhoffman/sample/EchoSynadiaAgent.java`
- [X] T024 [US1] Extend the sample `Bootstrap` to `register(new EchoSynadiaAgent())` on the existing `NatsMicroRuntime` in `nats-micro-sample/src/main/java/blog/kevinhoffman/sample/Bootstrap.java` (depends on T022, T023)

**Checkpoint**: User Story 1 is fully functional — an agent is discoverable and answers plain, structured, streamed, and interactive prompts. This is the MVP.

---

## Phase 4: User Story 2 - Agent liveness is visible to callers (Priority: P2)

**Goal**: Each running agent publishes liveness beacons on `agents.hb.{agent}.{owner}.{name}`
at the configured cadence and serves a `status` endpoint that returns an equivalent payload
on demand — with no developer code.

**Independent Test**: Start the sample agent; subscribe to its heartbeat subject and confirm
beacons arrive at the configured interval carrying `agent`, `owner`, `instance_id`, `ts`,
`interval_s` (and `session` only when configured); request the `status` endpoint and confirm
an equivalent payload; stop the agent and confirm beacons cease.

### Tests for User Story 2 ⚠️

- [X] T025 [US2] Integration test `SynadiaLivenessIntegrationTest` against an embedded `nats-server` — beacons published at the configured cadence with correct fields; `session` present only when configured; the `status` endpoint reply equivalent to a beacon; beacons cease after `stop()` (contract B3) in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/SynadiaLivenessIntegrationTest.java`

### Implementation for User Story 2

- [X] T026 [P] [US2] Create `HeartbeatPublisher` that builds the `LivenessBeacon` payload (per-process UUID `instance_id`, current UTC `ts`, configured `interval_s`, optional `session`) and publishes it to the heartbeat subject; expose the payload builder for reuse (research R6, data-model §8) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/HeartbeatPublisher.java`
- [X] T027 [US2] Create `StatusMessageHandler` (`ServiceMessageHandler` for the `status` endpoint) that replies synchronously with the `HeartbeatPublisher` payload (research R6, contract B3) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/StatusMessageHandler.java` (depends on T026)
- [X] T028 [US2] Extend `NatsMicroRuntime` to add the `status` `ServiceEndpoint` (queue group `agents`) to each agent `Service`, and to own a single `ScheduledExecutorService` that schedules every agent's `HeartbeatPublisher` at `start()` and is cancelled/shut down at `stop()` (research R6) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/NatsMicroRuntime.java` (depends on T027)

**Checkpoint**: User Stories 1 AND 2 both work — the agent answers prompts and is observably alive.

---

## Phase 5: User Story 3 - Handler problems become protocol errors (Priority: P3)

**Goal**: Malformed and oversized requests, intentional handler rejections, and unexpected
handler failures all produce protocol-conformant errors (NATS error headers + clean stream
termination) instead of hangs or opaque failures.

**Independent Test**: Send a malformed payload and an oversized payload and confirm a `400`;
have the handler throw `SynadiaAgentException(403, ...)` and confirm a `403` with that
message; have the handler throw a plain exception and confirm a `500`; confirm every error
carries `Nats-Service-Error-Code`/`Nats-Service-Error` headers and that the reply stream is
terminated.

### Tests for User Story 3 ⚠️

- [X] T029 [US3] Integration test `SynadiaErrorIntegrationTest` against an embedded `nats-server` — malformed payload → `400`; oversized payload → `400`; `SynadiaAgentException` → developer-chosen code + message; other handler exception → `500`; error headers present and stream terminated (contract B4, spec SC-006) in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/SynadiaErrorIntegrationTest.java`

### Implementation for User Story 3

- [X] T030 [US3] Add max-payload enforcement to `PromptMessageHandler` — parse the agent's `maxPayload` (e.g. `"1MB"`) to a byte count and reject an oversized request with a `400` `ProtocolError` before decoding (contract B4) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/PromptMessageHandler.java` (depends on T021)
- [X] T031 [US3] Add error translation to `PromptMessageHandler`/`ResponseStream` — map a `ProtocolError`, a `SynadiaAgentException` (developer code), and any other exception (`500`) to a NATS error-headed reply (`Nats-Service-Error-Code` + `Nats-Service-Error`, optional JSON body) followed by the zero-byte terminator (research R7, contract B4) in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/synadia/internal/ResponseStream.java` (depends on T020, T021, T030)

**Checkpoint**: All three user stories work — prompts answered, liveness visible, failures surfaced as protocol errors.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and end-to-end validation across all stories.

- [X] T032 [P] Update `README.md` with a Synadia agent section — dependency note, `@SynadiaAgent`/`@PromptHandler` example, and `nats` CLI examples (`nats micro info agents`, `nats request agents.prompt.…`, `nats sub agents.hb.…`)
- [X] T033 Run the `quickstart.md` walkthrough end to end (start `nats-server`, run the sample, exercise the agent with the `nats` CLI) and correct any drift in `specs/002-synadia-agent-protocol/quickstart.md`
- [X] T034 Run `mvn verify` from the repository root and confirm the full unit + integration suite (existing 001 tests plus all new Synadia tests) passes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational. Delivers the MVP.
- **User Story 2 (Phase 4)**: Depends on Foundational; extends the agent `Service` built in
  US1 (T022) by adding the `status` endpoint and the heartbeat scheduler. Practically
  sequenced after US1.
- **User Story 3 (Phase 5)**: Depends on Foundational; extends `PromptMessageHandler` (T021)
  and `ResponseStream` (T020) from US1. Practically sequenced after US1.
- **Polish (Phase 6)**: Depends on all desired user stories being complete.

### User Story Dependencies

- **US1 (P1)**: Independent of US2/US3 — the MVP.
- **US2 (P2)**: Independently *testable* (liveness can be verified without sending prompts),
  but its implementation adds endpoints/scheduling to the `Service` and runtime created in
  US1; build US1 first.
- **US3 (P3)**: Independently *testable* (error paths verified on their own), but its
  implementation modifies US1's handler and stream; build US1 first.

### Within Each User Story

- Test tasks are listed first and should fail before implementation lands.
- Records/annotations before the classes that consume them.
- Internal classes before the runtime wiring that registers them.

### Parallel Opportunities

- T001, T002 (Setup) run in parallel.
- T003–T009 (Foundational types — all new, independent files) run in parallel; then T010,
  then T011. Unit tests T012–T014 run in parallel once their targets exist.
- US1: T015 and T017 are parallel; T018 and T019 are parallel; T023 is parallel with the
  internal-class work. T020→T021→T022→T024 are sequential.
- US2: T026 can be written in parallel with US1 internal classes; T027 then T028 sequential.

---

## Parallel Example: Phase 2 Foundational

```bash
# Launch the independent foundational types together:
Task: "Create @SynadiaAgent annotation in synadia/SynadiaAgent.java"          # T003
Task: "Create @PromptHandler annotation in synadia/PromptHandler.java"        # T004
Task: "Create PromptRequest record in synadia/PromptRequest.java"             # T005
Task: "Create SynadiaAgentException in synadia/SynadiaAgentException.java"    # T006
Task: "Create SynadiaSubjects in synadia/internal/SynadiaSubjects.java"       # T007
Task: "Create ProtocolJson in synadia/internal/ProtocolJson.java"             # T008
Task: "Create AgentDescriptor in synadia/internal/AgentDescriptor.java"       # T009
# Then T010 (AgentReflector), then T011 (NatsMicroRuntime.register routing).
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories).
3. Complete Phase 3: User Story 1.
4. **STOP and VALIDATE**: register the sample agent, run the service, and exercise it with
   `nats micro info agents` + `nats request agents.prompt.…`.
5. The MVP is a discoverable agent that answers plain, structured, streamed, and interactive
   prompts.

### Incremental Delivery

1. Setup + Foundational → an agent can be declared and validated.
2. US1 → discoverable, prompt-answering agent (MVP).
3. US2 → liveness beacons + status endpoint.
4. US3 → handler problems become protocol errors.
5. Polish → docs + full `mvn verify`.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task.
- `[Story]` labels (US1/US2/US3) map tasks to spec.md user stories; Setup/Foundational/Polish
  carry no story label.
- No new Maven dependency is introduced — `io.nats:jnats` supplies the NATS client, micro
  framework, and JSON utilities.
- The plan referenced a single `SynadiaAgentIntegrationTest`; tasks split it into one
  integration test file per story (`SynadiaPromptIntegrationTest`,
  `SynadiaLivenessIntegrationTest`, `SynadiaErrorIntegrationTest`) so each story stays
  independently testable.
- Integration tests require a `nats-server` binary on the `PATH` (embedded via
  `jnats-server-runner`), consistent with the existing 001 tests.
- Commit after each task or logical group.
