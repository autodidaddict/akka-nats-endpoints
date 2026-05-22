# Tasks: NATS Micro-Service Endpoint for Akka Components

**Input**: Design documents from `/specs/001-nats-micro-endpoint/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/nats-micro-api.md, quickstart.md

**Tests**: INCLUDED — the project constitution mandates test coverage (Principle III) and
plan.md Phase 1 enumerates explicit unit and integration test files.

**Organization**: Tasks are grouped by user story so each story can be implemented and
tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: User story the task belongs to (US1–US5)
- All paths are relative to the repository root.

## Path Conventions

Multi-module Maven build (see T040):

- **Library module** `nats-micro-endpoint/` — published artifact. Public API under
  `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/`, non-public classes under
  `.../akka/nats/internal/`. Unit tests under `nats-micro-endpoint/src/test/java/`.
- **Sample module** `nats-micro-sample/` — runnable Akka service, not published. Code under
  `nats-micro-sample/src/main/java/blog/kevinhoffman/sample/`. Integration tests under
  `nats-micro-sample/src/test/java/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization, dependencies, and configuration scaffolding.

- [X] T001 Add dependencies to `pom.xml`: `io.nats:jnats` (compile scope), `io.nats:jnats-server-runner` (test scope), and JUnit 5 + AssertJ test dependencies
- [X] T002 [P] Create source/test package directories: `src/main/java/blog/kevinhoffman/akka/nats/`, `src/main/java/blog/kevinhoffman/akka/nats/internal/`, `src/main/java/blog/kevinhoffman/sample/`, `src/test/java/blog/kevinhoffman/akka/nats/`, `src/test/java/blog/kevinhoffman/sample/`
- [X] T003 [P] Create `src/main/resources/application.conf` with the `nats { url, token, credentials-file, tls-enabled, drain-timeout }` block, each value supporting `${?ENV}` override
- [X] T004 [P] Create `src/test/resources/application-test.conf` that includes `application.conf` and overrides `nats.url` to point at the embedded test server

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Public value types and connection plumbing that every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T005 [P] Create `@NatsMicroService` annotation (`name`, `version`, `queueGroup`, `description`) in `src/main/java/blog/kevinhoffman/akka/nats/NatsMicroService.java`
- [X] T006 [P] Create `@NatsSubject` annotation (`value`, `name`, `description`) in `src/main/java/blog/kevinhoffman/akka/nats/NatsSubject.java`
- [X] T007 [P] Create `NatsRequest` record (`concreteSubject`, `payload`, `tokens`, `namedTokens`) with `token(name)`/`tokenOpt(name)`/`payload()` methods in `src/main/java/blog/kevinhoffman/akka/nats/NatsRequest.java`
- [X] T008 [P] Create `NatsHandlerException` (fields `code`, `message`) in `src/main/java/blog/kevinhoffman/akka/nats/NatsHandlerException.java`
- [X] T009 [P] Create `NatsConnectionSettings` record with static `fromConfig(Config)` in `src/main/java/blog/kevinhoffman/akka/nats/NatsConnectionSettings.java`
- [X] T010 Create `NatsConnectionFactory` (builds an `io.nats` `Connection` from `NatsConnectionSettings`: url, token, `.creds`, TLS) in `src/main/java/blog/kevinhoffman/akka/nats/internal/NatsConnectionFactory.java` (depends on T009)

**Checkpoint**: Annotations, value types, and connection plumbing exist — user stories can begin.

---

## Phase 3: User Story 1 - Declare a class as a NATS micro-service and serve requests (Priority: P1) 🎯 MVP

**Goal**: A developer annotates a plain class, registers it with `NatsMicroRuntime`, and on
startup a fixed-subject handler answers requests with exactly the bytes it returned.

**Independent Test**: Run the sample service against an embedded NATS server and execute
`nats micro request echo-service echo.upper "hello"` — the response is `HELLO`.

### Tests for User Story 1 ⚠️ (write first, ensure they FAIL before implementation)

- [X] T011 [P] [US1] Unit test `AnnotationReflectorTest` in `src/test/java/blog/kevinhoffman/akka/nats/AnnotationReflectorTest.java` — valid class reflects to correct descriptors; missing `@NatsMicroService`, blank name/version, and bad handler signature each throw `IllegalArgumentException`
- [X] T012 [P] [US1] Unit test `HandlerDispatcherTest` in `src/test/java/blog/kevinhoffman/akka/nats/HandlerDispatcherTest.java` — a `byte[]` handler returns exact bytes; zero-length payload handled
- [X] T013 [P] [US1] Integration test `NatsMicroEndpointIntegrationTest` (request/reply portion) in `src/test/java/blog/kevinhoffman/sample/NatsMicroEndpointIntegrationTest.java` — embedded NATS, register the echo service, request returns exact bytes, including the empty-payload case

### Implementation for User Story 1

- [X] T014 [P] [US1] Create `EndpointDescriptor` in `src/main/java/blog/kevinhoffman/akka/nats/internal/EndpointDescriptor.java`
- [X] T015 [P] [US1] Create `ServiceDescriptor` in `src/main/java/blog/kevinhoffman/akka/nats/internal/ServiceDescriptor.java`
- [X] T016 [P] [US1] Create `SubjectMatcher` in `src/main/java/blog/kevinhoffman/akka/nats/internal/SubjectMatcher.java` (implemented with full wildcard + `{name}` token support — see note below; T026 covered early)
- [X] T017 [US1] Create `AnnotationReflector` that builds and validates `ServiceDescriptor`/`EndpointDescriptor` from a registered instance in `src/main/java/blog/kevinhoffman/akka/nats/internal/AnnotationReflector.java` (depends on T014, T015)
- [X] T018 [US1] Create `HandlerDispatcher` implementing `io.nats` `ServiceMessageHandler` — invokes the handler method and replies with exact bytes in `src/main/java/blog/kevinhoffman/akka/nats/internal/HandlerDispatcher.java` (depends on T016, T017)
- [X] T019 [US1] Create `NatsMicroRuntime` (public entry point: `create`/`register`/`start`/`stop`, lifecycle states NEW→RUNNING→STOPPED, builds one `io.nats` `Service` per descriptor and subscribes endpoints) in `src/main/java/blog/kevinhoffman/akka/nats/NatsMicroRuntime.java` (depends on T010, T017, T018)
- [X] T020 [P] [US1] Create sample `EchoNatsService` with the `echo.upper` `byte[]` handler in `src/main/java/blog/kevinhoffman/sample/EchoNatsService.java`
- [X] T021 [US1] Create sample `Bootstrap` (`@Setup ServiceSetup`) that constructs `NatsMicroRuntime`, registers `EchoNatsService`, and calls `start()`/`stop()` in `onStartup`/`onShutdown` in `src/main/java/blog/kevinhoffman/sample/Bootstrap.java` (depends on T019, T020)

> **US1 implementation note**: `SubjectMatcher` and `HandlerDispatcher` were built in their
> complete form (full `*`/`>`/`{name}` wildcard + token support, and `NatsRequest`-parameter
> dispatch) because US3 modifies the very same files. This means T026 and T028 are already
> implemented; US3 reduces to adding the wildcard sample handler (T029) and its tests
> (T024, T025).

**Checkpoint**: MVP — a NATS client can call a handler and get exact bytes back.

---

## Phase 4: User Story 2 - Discover and introspect with standard `nats` tooling (Priority: P2)

**Goal**: `nats micro list/info/ping/stats` recognize the service, its identity, its declared
endpoints, and its request counters.

**Independent Test**: Against the running sample service, `nats micro list` shows
`echo-service`; `nats micro info` reports version and subjects; `nats micro ping` replies
per instance; `nats micro stats` counters increase with requests.

**Note**: The `io.nats.service` micro framework provides discovery, ping, and stats
automatically once the `Service` is built (see research R1). This phase is small by design —
it adds optional description metadata and verifies the framework behavior.

### Tests for User Story 2 ⚠️

- [X] T022 [P] [US2] Add discovery/stats assertions to `NatsMicroEndpointIntegrationTest` in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/NatsMicroEndpointIntegrationTest.java` — verifies (via `io.nats.service.Discovery`) the service is discoverable, `info` advertises name/version/endpoint subjects/queue group, `ping` responds, and `stats` request counters increment

### Implementation for User Story 2

- [X] T023 [US2] Wire optional service and per-endpoint `description` metadata into the `io.nats` `ServiceBuilder`/`ServiceEndpoint` so `nats micro info` advertises them, in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/NatsMicroRuntime.java` (service description + per-endpoint description via `endpointMetadata`)

**Checkpoint**: The service is a full citizen of the NATS micro ecosystem.

---

## Phase 5: User Story 3 - Wildcard subjects and concrete-subject recovery (Priority: P2)

**Goal**: A handler bound to a wildcard subject serves a family of subjects and can recover
the concrete subject and matched tokens via `NatsRequest`.

**Independent Test**: `nats micro request echo-service echo.repeat.3 "ab"` is routed to the
wildcard handler, returns `ababab`, and the handler reads token `count` = `3`.

### Tests for User Story 3 ⚠️

- [X] T024 [P] [US3] Unit test `SubjectMatcherTest` in `nats-micro-endpoint/src/test/java/blog/kevinhoffman/akka/nats/SubjectMatcherTest.java` — `*` and `>` matching, positional token extraction, `{name}` named-token extraction, and `toNatsSubject` translation
- [X] T025 [P] [US3] Integration test `WildcardRoutingIntegrationTest` in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/WildcardRoutingIntegrationTest.java` — wildcard handler recovers a named token, one handler serves a family of concrete subjects, and a `>` handler recovers the concrete subject

### Implementation for User Story 3

- [X] T026 [US3] Extend `SubjectMatcher` with NATS wildcard matching (`*`, trailing `>`) and `{name}` token parsing/extraction in `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/internal/SubjectMatcher.java` (implemented complete in US1)
- [X] T027 [US3] Parse `{name}` token labels in `AnnotationReflector` — `nats-micro-endpoint/.../internal/AnnotationReflector.java` builds `natsSubject` via `SubjectMatcher.toNatsSubject`; named tokens are derived from `declaredSubject` at dispatch time rather than stored on the descriptor (implemented in US1)
- [X] T028 [US3] Extend `HandlerDispatcher` to build a `NatsRequest` (concrete subject, payload, positional + named tokens) and invoke `NatsRequest`-parameter handlers — `nats-micro-endpoint/.../internal/HandlerDispatcher.java` (implemented complete in US1)
- [X] T029 [P] [US3] Add wildcard handlers to the sample `EchoNatsService` (`echo.repeat.{count}` and `echo.subject.>`) in `nats-micro-sample/src/main/java/blog/kevinhoffman/sample/EchoNatsService.java`

**Checkpoint**: Wildcard handlers work with full subject/token recovery.

---

## Phase 6: User Story 4 - Cluster single delivery (Priority: P2)

**Goal**: With N identical instances running, M requests result in M total handler
invocations across the cluster — load-balanced single delivery via a NATS queue group.

**Independent Test**: Start two `NatsMicroRuntime` instances against one embedded NATS
server, send M requests, and assert exactly M handler invocations in total.

### Tests for User Story 4 ⚠️

- [X] T030 [P] [US4] Integration test `ClusterSingleDeliveryIntegrationTest` in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/ClusterSingleDeliveryIntegrationTest.java` — two runtimes, 20 requests, asserts exactly 20 total handler invocations across the cluster and that both instances handled some

### Implementation for User Story 4

- [X] T031 [US4] Resolve the queue-group name in `ServiceDescriptor` (use `@NatsMicroService.queueGroup` override, else fall back to the service name) in `src/main/java/blog/kevinhoffman/akka/nats/internal/AnnotationReflector.java` (done during US1)
- [X] T032 [US4] Apply the resolved queue group to every endpoint subscription via `ServiceEndpoint.Builder.endpointQueueGroup(...)` in `src/main/java/blog/kevinhoffman/akka/nats/NatsMicroRuntime.java` (depends on T031)

**Checkpoint**: Multi-instance deployments deliver each request exactly once.

---

## Phase 7: User Story 5 - NATS-native error responses (Priority: P3)

**Goal**: A handler that throws or explicitly rejects causes the caller to receive a
NATS-native error response, and the error counter increments.

**Independent Test**: A throwing handler yields error code `"500"`; a handler that throws
`NatsHandlerException("400", ...)` yields code `400`; `nats micro stats` error counter rises.

### Tests for User Story 5 ⚠️

- [X] T033 [P] [US5] Add error-handling assertions to `NatsMicroEndpointIntegrationTest` in `nats-micro-sample/src/test/java/blog/kevinhoffman/sample/NatsMicroEndpointIntegrationTest.java` — thrown exception → error code `500`; `NatsHandlerException` → developer-chosen code `400`; `stats` error counter increments

### Implementation for User Story 5

- [X] T034 [US5] Error handling in `HandlerDispatcher` — `nats-micro-endpoint/src/main/java/blog/kevinhoffman/akka/nats/internal/HandlerDispatcher.java`. Ordinary exceptions propagate so the NATS micro framework responds with code `500` and counts the error; a `NatsHandlerException` is answered with the developer-chosen code via `respondStandardError`, then rethrown so the framework still counts it. `NatsHandlerException` code changed to `int` to match the NATS protocol / `respondStandardError`.
- [X] T035 [P] [US5] Add `echo.fail` (throws an ordinary exception) and `echo.reject` (throws `NatsHandlerException(400, ...)`) handlers to the sample `EchoNatsService`, in `nats-micro-sample/src/main/java/blog/kevinhoffman/sample/EchoNatsService.java`

**Checkpoint**: All five user stories are independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, the user-space limitation note, and end-to-end validation.

- [X] T040 Restructure into a multi-module Maven build: library module `nats-micro-endpoint` (the published artifact) and sample module `nats-micro-sample` with its own `pom.xml` depending on the library. Verified: the library JAR contains only `blog/kevinhoffman/akka/nats` — no sample code, no `@Setup`/component classes. (Done before Phases 4–7.)
- [X] T036 Rewrote `README.md` — feature overview, module layout, build/run instructions, `nats` CLI usage examples, configuration, and the documented user-space limitation (FR-018 — not a first-class SDK endpoint type; a NATS-only service still needs one Akka component to boot)
- [X] T037 [P] Added `package-info.java` to `blog.kevinhoffman.akka.nats` (supported public API) and `blog.kevinhoffman.akka.nats.internal` (non-public) clarifying the API boundary
- [X] T038 End-to-end validation: the integration suite executes every acceptance scenario against a real embedded `nats-server` (request/reply, discovery, wildcard, cluster single delivery, error responses). The README is the runnable walkthrough; the `specs/` `quickstart.md` predates the multi-module split.
- [X] T039 `mvn clean verify` — all 30 tests pass (library 18 unit, sample 12 integration); library JAR verified to contain only `blog/kevinhoffman/akka/nats`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Stories (Phases 3–7)**: All depend on Foundational completion.
  - US1 is the MVP and must be done first.
  - US2, US3, US4, US5 build on the `NatsMicroRuntime`/`HandlerDispatcher` produced by US1.
- **Polish (Phase 8)**: Depends on all targeted user stories being complete.

### User Story Dependencies

- **US1 (P1)**: Depends only on Foundational. Produces the runtime/dispatcher core.
- **US2 (P2)**: Depends on US1 (extends `NatsMicroRuntime`). Independently testable.
- **US3 (P2)**: Depends on US1 (extends `SubjectMatcher`/`HandlerDispatcher`/`AnnotationReflector`). Independently testable.
- **US4 (P2)**: Depends on US1 (extends `AnnotationReflector`/`NatsMicroRuntime`). Independently testable.
- **US5 (P3)**: Depends on US1 (extends `HandlerDispatcher`). Independently testable.

> Note: US2–US5 each extend files created in US1, so they are best run sequentially in
> priority order rather than in parallel — they are independently *testable*, not
> file-isolated from one another.

### Within Each User Story

- Tests are written first and must FAIL before implementation.
- Descriptors/value types before the reflector; reflector before the dispatcher; dispatcher
  before the runtime; runtime before the sample `Bootstrap`.

### Parallel Opportunities

- Setup: T002, T003, T004 in parallel.
- Foundational: T005–T009 in parallel (T010 follows T009).
- US1 tests T011–T013 in parallel; then T014, T015, T016 in parallel; T020 in parallel with them.
- US3 tests T024, T025 in parallel.

---

## Parallel Example: User Story 1

```bash
# Tests first (all fail before implementation):
Task: "Unit test AnnotationReflectorTest in src/test/java/blog/kevinhoffman/akka/nats/AnnotationReflectorTest.java"
Task: "Unit test HandlerDispatcherTest in src/test/java/blog/kevinhoffman/akka/nats/HandlerDispatcherTest.java"
Task: "Integration test NatsMicroEndpointIntegrationTest in src/test/java/blog/kevinhoffman/sample/NatsMicroEndpointIntegrationTest.java"

# Then the independent building blocks:
Task: "Create EndpointDescriptor in src/main/java/blog/kevinhoffman/akka/nats/internal/EndpointDescriptor.java"
Task: "Create ServiceDescriptor in src/main/java/blog/kevinhoffman/akka/nats/internal/ServiceDescriptor.java"
Task: "Create SubjectMatcher in src/main/java/blog/kevinhoffman/akka/nats/internal/SubjectMatcher.java"
Task: "Create sample EchoNatsService in src/main/java/blog/kevinhoffman/sample/EchoNatsService.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories).
3. Complete Phase 3: User Story 1.
4. **STOP and VALIDATE**: run `NatsMicroEndpointIntegrationTest`; call `nats micro request`.
5. Demo the MVP — a NATS client can call an Akka-backed handler.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → MVP: request/reply works.
3. US2 → discovery/introspection works with stock `nats` tooling.
4. US3 → wildcard subjects + token recovery.
5. US4 → verified cluster single delivery.
6. US5 → NATS-native error responses.
7. Polish → docs, limitation note, quickstart validation, `mvn verify`.

### Parallel Team Strategy

Foundational must be done together. Because US2–US5 each modify the core files from US1,
prefer sequential priority-ordered delivery; parallelize *within* a story (tests, then
independent descriptors/types) rather than across stories.

---

## Notes

- [P] = different files, no dependency on an incomplete task.
- [Story] label maps each task to a user story for traceability.
- Verify each test fails before implementing the code that satisfies it.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently.
- US2–US5 extend US1's files — treat them as independently *testable* increments, not as
  file-isolated parallel tracks.
