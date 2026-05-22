# Quickstart: Expose an Akka service as a Synadia Agent

**Feature**: 002-synadia-agent-protocol

This walks through declaring a Synadia agent, registering it, running it, and exercising it
with the stock `nats` CLI. It assumes the `nats-micro-endpoint` dependency is already on the
classpath (see the project README).

---

## 1. Declare an agent

Annotate a plain class with `@SynadiaAgent` and mark exactly one method `@PromptHandler`.

```java
import blog.kevinhoffman.akka.nats.synadia.*;

@SynadiaAgent(agent = "echo", owner = "acme", name = "echo-1", version = "0.1.0")
public class EchoSynadiaAgent {

  // Simplest form — return a single response.
  @PromptHandler
  public String handle(String prompt) {
    return "echo: " + prompt;
  }
}
```

### Streaming + interactive form

To stream content progressively or ask the caller a question mid-response, take an
`AgentResponse`:

```java
@SynadiaAgent(agent = "writer", owner = "acme", name = "writer-1")
public class WriterAgent {

  @PromptHandler
  public void handle(PromptRequest request, AgentResponse response) {
    for (String word : request.prompt().split("\\s+")) {
      response.emit(word + " ");                    // one `response` chunk each
    }
    QueryReply more = response.query("Want a summary? (yes/no)");
    if (!more.timedOut() && more.answer().equalsIgnoreCase("yes")) {
      response.emit("\nSummary: " + request.prompt().length() + " chars.");
    }
    // `ack` and the stream terminator are added by the library.
  }
}
```

Reject a request explicitly with `SynadiaAgentException`:

```java
if (request.prompt().length() > 4000) {
  throw new SynadiaAgentException(400, "prompt too long");
}
```

---

## 2. Register the agent

Drive it from your service's single `@Setup` class — the **same** `NatsMicroRuntime` used for
ordinary NATS micro-endpoints, so everything shares one connection:

```java
@Setup
public class Bootstrap implements ServiceSetup {
  private final NatsMicroRuntime nats;

  public Bootstrap(Config config) {
    this.nats = NatsMicroRuntime.create(NatsConnectionSettings.fromConfig(config));
    this.nats.register(new EchoSynadiaAgent());   // a Synadia agent
    // this.nats.register(new SomeNatsService()); // ...and @NatsMicroService endpoints, if any
  }

  @Override public void onStartup()  { nats.start(); }
  @Override public void onShutdown() { nats.stop(); }
}
```

Configuration is the existing `nats { ... }` block — no new keys.

---

## 3. Run it

```shell
nats-server &
mvn -pl nats-micro-sample -am compile exec:java
```

---

## 4. Exercise it with the `nats` CLI

```shell
# Discovery — the agent registers as a micro service named "agents"
nats micro ls
nats micro info agents

# Prompt the agent (plain text). The reply is the protocol chunk stream:
#   {"type":"status","data":"ack"}  →  {"type":"response","data":"echo: hello"}  →  <empty>
nats request 'agents.prompt.echo.acme.echo-1' 'hello'

# Prompt with a JSON envelope
nats request 'agents.prompt.echo.acme.echo-1' '{"prompt":"hello"}'

# Liveness — observe heartbeats (every 30s by default)
nats sub 'agents.hb.echo.acme.echo-1'

# Liveness on demand — the status endpoint replies with a heartbeat-shaped payload
nats request 'agents.status.echo.acme.echo-1' ''
```

---

## 5. Verify

```shell
mvn verify   # builds the library + sample, runs unit and integration tests
```

`SynadiaAgentIntegrationTest` confirms, against an embedded `nats-server`: discovery and
metadata, plain-text and structured prompts, multi-chunk streamed responses, the mid-stream
query round-trip (including the 60 s timeout path), heartbeats and the status endpoint, error
and oversized-payload handling, multi-instance single delivery via the `agents` queue group,
and an agent coexisting with a `@NatsMicroService` on one connection.

---

## What the library does for you

| You write | The library handles |
|-----------|--------------------|
| `@SynadiaAgent` identity + one `@PromptHandler` method | Micro-service registration, protocol metadata, subject derivation, `agents` queue group |
| Return a `String` / `emit(...)` content | The `ack` chunk, `response` chunk framing, the zero-byte terminator |
| `response.query(...)` | The `query` chunk, a reply inbox, the bounded wait, timeout signaling |
| `heartbeat` / `session` config (optional) | Periodic beacons and the status endpoint, with runtime-derived `instance_id` + `ts` |
| `throw new SynadiaAgentException(code, msg)` | Protocol error headers and clean stream termination |
| Nothing | Discovery responses (`$SRV.PING/INFO.agents`) |

## Known limitations (this release)

- **Attachments are not supported** — the `prompt` endpoint advertises `attachments_ok=false`
  and rejects attachment-bearing requests with `400`.
- As with the rest of this library, the agent runs in **user space** on the `ServiceSetup`
  hook; it is not a first-class SDK endpoint type, and the Akka service still needs at least
  one real Akka component to boot.
