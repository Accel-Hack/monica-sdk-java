package com.accelhack.monica;

@FunctionalInterface
public interface BeforeSend {
  MonicaEvent process(MonicaEvent event, CaptureHint hint) throws Exception;
}
