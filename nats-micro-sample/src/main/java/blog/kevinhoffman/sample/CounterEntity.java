package blog.kevinhoffman.sample;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * A minimal Akka Key Value Entity holding an integer counter.
 *
 * <p>It exists so the sample can demonstrate a NATS handler reaching a real Akka component
 * through an injected {@code ComponentClient} — see {@link CounterNatsService}.
 */
@Component(id = "counter")
public class CounterEntity extends KeyValueEntity<CounterEntity.Counter> {

  /** The entity state. */
  public record Counter(int value) {}

  @Override
  public Counter emptyState() {
    return new Counter(0);
  }

  /** Increments the counter and replies with the new value. */
  public Effect<Integer> increment() {
    Counter updated = new Counter(currentState().value() + 1);
    return effects().updateState(updated).thenReply(updated.value());
  }

  /** Replies with the current counter value. */
  public Effect<Integer> get() {
    return effects().reply(currentState().value());
  }
}
