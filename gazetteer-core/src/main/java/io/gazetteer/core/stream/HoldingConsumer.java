package io.gazetteer.core.stream;

import java.util.function.Consumer;

/**
 * A {@code Consumer} that holds the latest value it accepted.
 *
 * @param <T>
 */
public class HoldingConsumer<T> implements Consumer<T> {

  private T value;

  /**
   * {@inheritDoc}
   */
  @Override
  public void accept(T value) {
    this.value = value;
  }

  /**
   * Returns the holded value.
   *
   * @return the holded value.
   */
  public T value() {
    return value;
  }
}
