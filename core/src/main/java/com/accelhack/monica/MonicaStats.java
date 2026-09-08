package com.accelhack.monica;

public final class MonicaStats {
  private final int queued;
  private final long discarded;

  public MonicaStats(int queued, long discarded) {
    this.queued = queued;
    this.discarded = discarded;
  }

  public int getQueued() {
    return queued;
  }

  public long getDiscarded() {
    return discarded;
  }
}
