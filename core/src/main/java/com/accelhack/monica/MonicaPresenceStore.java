package com.accelhack.monica;

/**
 * Where {@link MonicaClient} keeps what the presence heartbeat ({@code client_report}) decides
 * on. The default holds it in process memory, which is what a server needs. A distributable
 * (monica-android) replaces it with device storage, so a restart inside the interval does not
 * send again.
 *
 * <p>Every getter answers {@code null} for "nothing stored". The client reads and writes it
 * from its sender thread, and validates the values it wrote itself, so an implementation only
 * persists them.
 */
public interface MonicaPresenceStore {
  /** Epoch millis of the last {@code 202}, or of the last heartbeat attempted or sampled out. */
  Long getLastReportedAt();

  void setLastReportedAt(long epochMillis);

  /** The interval the last {@code X-Monica-Presence-Interval-Ms} header set. */
  Long getIntervalMillis();

  void setIntervalMillis(long intervalMillis);

  /**
   * The rate the last {@code X-Monica-Presence-Sample-Rate} header set. A store that answers
   * {@code null} opts out of sampling; the in-memory default does, because only distributables
   * sample.
   */
  Double getSampleRate();

  void setSampleRate(double sampleRate);

  /**
   * Whether the flush tick sends {@code interval} heartbeats. A distributable's (Android's)
   * store answers {@code false} and sends only the {@code start} at launch and on returning to
   * the foreground, because the spec gives mobile no background timer.
   */
  default boolean sendsIntervalHeartbeats() {
    return true;
  }

  /** The default: process memory, and no sampling. */
  static MonicaPresenceStore inMemory() {
    return new InMemoryPresenceStore();
  }
}

final class InMemoryPresenceStore implements MonicaPresenceStore {
  private volatile Long lastReportedAt;
  private volatile Long intervalMillis;

  @Override
  public Long getLastReportedAt() {
    return lastReportedAt;
  }

  @Override
  public void setLastReportedAt(long epochMillis) {
    lastReportedAt = epochMillis;
  }

  @Override
  public Long getIntervalMillis() {
    return intervalMillis;
  }

  @Override
  public void setIntervalMillis(long value) {
    intervalMillis = value;
  }

  @Override
  public Double getSampleRate() {
    return null;
  }

  @Override
  public void setSampleRate(double sampleRate) {
    // A server SDK reads the header but never samples.
  }
}
