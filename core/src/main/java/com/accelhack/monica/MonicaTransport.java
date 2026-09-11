package com.accelhack.monica;

@FunctionalInterface
public interface MonicaTransport {
  /**
   * Sends one envelope and says whether ingest accepted it.
   *
   * <p>This is the whole interface for an implementor: it stays the single abstract method so a
   * lambda, and the transports already compiled against monica-core 0.1.1 (monica-android's
   * {@code HttpUrlConnectionTransport} among them), keep working unchanged.
   */
  boolean send(MonicaEnvelope envelope) throws Exception;

  /**
   * Sends one envelope and reports what ingest answered.
   *
   * <p>{@link MonicaClient} calls this instead of {@link #send(MonicaEnvelope)} so a rejection
   * can carry its HTTP status and the {@code error.json} body a {@code 422} returns. The default
   * implementation delegates to {@code send}, which loses the status but keeps every existing
   * transport source- and binary-compatible. A transport that can see the response should
   * override it; {@code send} then usually becomes {@code deliver(envelope).isAccepted()}.
   */
  default SendResult deliver(MonicaEnvelope envelope) throws Exception {
    return SendResult.of(send(envelope));
  }
}
