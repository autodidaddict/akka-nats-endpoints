# Contract: Synadia Agent Protocol Support

**Feature**: 002-synadia-agent-protocol | **Date**: 2026-05-22

Two contracts: the **library API contract** (what a developer codes against) and the
**protocol wire contract** (what a Synadia/NATS caller observes). The feature is correct only
when both hold.

---

## Part A — Library API Contract

Public package: `blog.kevinhoffman.akka.nats.synadia`. Driven from the existing
`blog.kevinhoffman.akka.nats.NatsMicroRuntime`.

### A1. `@SynadiaAgent` (type-level annotation)

```java
@Retention(RUNTIME) @Target(TYPE)
public @interface SynadiaAgent {
  String agent();                       // required, non-blank, subject-token safe
  String owner();                       // required, non-blank, subject-token safe
  String name();                        // required, non-blank, subject-token safe
  String version()      default "0.1.0";
  String session()      default "";     // empty = session-unaware
  int    heartbeat()    default 30;     // seconds, > 0
  String maxPayload()   default "1MB";
  int    queryTimeout() default 60;     // seconds, > 0
}
```

**Contract**: A registered instance whose class lacks `@SynadiaAgent`, has a blank/non-token
identity element, or a non-positive `heartbeat`/`queryTimeout` MUST be rejected at
`register()`/`start()` with an `IllegalArgumentException` naming the class and the problem.

### A2. `@PromptHandler` (method-level annotation)

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface PromptHandler {}
```

**Contract**: Exactly one method per `@SynadiaAgent` class MUST carry `@PromptHandler`. Zero
or more than one MUST be rejected fail-fast. The method MUST match one of:

```java
String handle(String prompt);
String handle(PromptRequest request);
void   handle(PromptRequest request, AgentResponse response);
```

Any other signature MUST be rejected fail-fast with a message naming the method.

### A3. `PromptRequest` (record)

```java
public record PromptRequest(String prompt, boolean structured, String subject) { }
```

`prompt` is never blank. `structured` distinguishes a JSON envelope from promoted plain text.

### A4. `AgentResponse` (interface, streaming form only)

```java
public interface AgentResponse {
  void emit(String text);            // one ordered `response` chunk
  QueryReply query(String prompt);   // ask the caller; blocks up to queryTimeout
}

public record QueryReply(String answer, boolean timedOut) { }
```

**Contract**:
- The library emits the `ack` chunk before the handler is invoked and the zero-byte
  terminator after it returns — the developer never writes either.
- `emit(...)` calls appear to the caller as `response` chunks in invocation order.
- `query(...)` blocks until the caller replies once or `queryTimeout` elapses; on timeout it
  returns `new QueryReply("", true)` and the handler continues (it is NOT auto-failed).

### A5. `SynadiaAgentException` (explicit rejection)

```java
public class SynadiaAgentException extends RuntimeException {
  public SynadiaAgentException(int code, String message);
  public int code();
}
```

**Contract**: Throwing it from the handler yields a protocol error carrying `code` and the
message. Any other exception yields code `500`. In all cases the reply stream is terminated.

### A6. Runtime registration

```java
NatsMicroRuntime nats = NatsMicroRuntime.create(NatsConnectionSettings.fromConfig(config));
nats.register(new MyAgent());          // @SynadiaAgent instance
nats.register(new MyOtherEndpoint());  // @NatsMicroService instance — same runtime, same connection
// onStartup -> nats.start();   onShutdown -> nats.stop();
```

**Contract**: `register()` accepts both `@SynadiaAgent` and `@NatsMicroService` instances and
is valid only before `start()`. `start()` opens one connection, registers every service, and
begins heartbeats for agents. `stop()` stops heartbeats, drains, and closes. An agent and
ordinary micro-endpoints in one runtime share one NATS connection (spec SC-007).

---

## Part B — Protocol Wire Contract (Synadia Agent Protocol v0.3)

What a protocol-compliant caller / standard tooling observes. Subjects derive from the
identity triple.

### B1. Discovery & registration

| Observation | Requirement |
|-------------|-------------|
| `$SRV.PING.agents` / `$SRV.INFO.agents` | The agent responds (via the NATS micro framework). |
| Service name | `agents`. |
| Service metadata | `agent`, `owner`, `protocol_version = "0.3"`, and `session` when configured. |
| `prompt` endpoint | subject `agents.prompt.{agent}.{owner}.{name}`, queue group `agents`, metadata `max_payload` + `attachments_ok = false`. |
| `status` endpoint | subject `agents.status.{agent}.{owner}.{name}`, queue group `agents`. |

### B2. Prompt request → response stream

| Step | Requirement |
|------|-------------|
| Request | Plain UTF-8 text OR a JSON envelope `{"prompt": "...", ...}` (leading whitespace tolerated; `{` ⇒ JSON). |
| First reply chunk | `{"type":"status","data":"ack"}`, before any latency-inducing work. |
| Content | One or more `{"type":"response","data":<text|object>}` chunks, in order. |
| Mid-stream query | `{"type":"query","data":{"id","reply_subject","prompt"}}`; caller replies once to `reply_subject`. |
| Terminator | A headerless zero-byte message ends every stream (success or error). |

### B3. Heartbeats & status

| Observation | Requirement |
|-------------|-------------|
| `agents.hb.{agent}.{owner}.{name}` | A beacon every `heartbeat` seconds while running. |
| Beacon payload | `agent`, `owner`, `session` (only if configured), `instance_id`, `ts`, `interval_s`. |
| `status` endpoint reply | A payload equivalent to the beacon, returned on demand. |
| On shutdown | Beacons cease. |

### B4. Errors

| Condition | Result |
|-----------|--------|
| Malformed payload / missing `prompt` / oversize / attachments present | Error, code `400`. |
| Handler throws `SynadiaAgentException(code, msg)` | Error, that `code` + `msg`. |
| Handler throws anything else | Error, code `500`. |
| Error transport | `Nats-Service-Error-Code` + `Nats-Service-Error` headers; optional JSON body; stream terminated. |

---

## Contract Tests

| Test | Asserts |
|------|---------|
| `AgentReflectorTest` | A1/A2 fail-fast rules: missing `@SynadiaAgent`, incomplete identity, zero/duplicate `@PromptHandler`, bad signature, non-positive cadence/timeout. |
| `SynadiaSubjectsTest` | B1 subject derivation from the identity triple. |
| `RequestDecoderTest` | B2 discrimination rule; plain-text promotion; missing-prompt and attachments rejection (B4 `400`). |
| `ProtocolJsonTest` | B2/B3 encode/decode of `ack`/`response`/`query` chunks and beacon payload; `session` omitted when unset. |
| `SynadiaAgentIntegrationTest` | End-to-end over embedded NATS: B1 discovery & metadata, B2 plain & structured prompts, multi-chunk streaming, mid-stream query round-trip + timeout, B3 heartbeat & status, B4 error/oversized, multi-instance single delivery, and A6 agent + `@NatsMicroService` sharing one connection. |
