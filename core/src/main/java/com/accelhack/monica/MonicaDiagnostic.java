package com.accelhack.monica;

/**
 * Where the SDK writes a warning about an envelope ingest refused for good.
 *
 * <p>A rejection is not a MONICA event: it says the application's own payload is wrong, and
 * the only person who can fix it reads the application's log. The default sink is therefore
 * the platform's standard warning route ({@code System.Logger} at {@code WARNING} on
 * {@code com.accelhack.monica} for {@link JdkHttpTransport}), not a MONICA event.
 *
 * <p>Pass an implementation to {@code MonicaOptions.Builder#onDiagnostic} to route the text
 * into the application's own logging framework, or {@link #silent()} to drop it. The message
 * never contains the API key or the envelope, only the status, the error code and the
 * {@code path}/{@code message} pairs ingest returned.
 */
@FunctionalInterface
public interface MonicaDiagnostic {
  /** Called at most once per envelope, on the thread that sent it. Must not throw. */
  void warn(String message);

  /** Discards every diagnostic: the way to turn the warning off. */
  static MonicaDiagnostic silent() {
    return message -> { };
  }
}
