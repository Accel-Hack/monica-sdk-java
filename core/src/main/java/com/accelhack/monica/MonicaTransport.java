package com.accelhack.monica;

@FunctionalInterface
public interface MonicaTransport {
  boolean send(MonicaEnvelope envelope) throws Exception;
}
