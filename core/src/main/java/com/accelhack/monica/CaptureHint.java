package com.accelhack.monica;

import java.util.Optional;

public final class CaptureHint {
  private final Throwable originalException;

  private CaptureHint(Throwable originalException) {
    this.originalException = originalException;
  }

  public static CaptureHint forException(Throwable throwable) {
    return new CaptureHint(throwable);
  }

  public static CaptureHint empty() {
    return new CaptureHint(null);
  }

  public Optional<Throwable> getOriginalException() {
    return Optional.ofNullable(originalException);
  }
}
