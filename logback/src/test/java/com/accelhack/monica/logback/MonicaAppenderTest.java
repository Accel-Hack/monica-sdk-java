package com.accelhack.monica.logback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonicaAppenderTest {
  @Test
  void capturesThrowableWithoutFormattedArgumentsAndFiltersMdc() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
    MonicaAppender appender = new MonicaAppender();
    appender.setContext(new LoggerContext());
    appender.setClient(client);
    appender.setAllowedMdcKeys("request_id");
    appender.start();

    LoggingEvent event = event("com.example.app.BotService", Level.ERROR,
        "bot {} failed", new IllegalStateException("boom"));
    Map<String, String> mdc = new HashMap<>();
    mdc.put("request_id", "req-1");
    mdc.put("email", "person@example.test");
    event.setMDCPropertyMap(mdc);
    appender.doAppend(event);
    client.flush(Duration.ofSeconds(1));

    MonicaEvent captured = sent.get(0).getItems().get(0);
    assertEquals("bot {} failed", captured.get("message"));
    @SuppressWarnings("unchecked")
    Map<String, Object> contexts = (Map<String, Object>) captured.get("contexts");
    @SuppressWarnings("unchecked")
    Map<String, Object> logback = (Map<String, Object>) contexts.get("logback");
    @SuppressWarnings("unchecked")
    Map<String, String> capturedMdc = (Map<String, String>) logback.get("mdc");
    assertEquals("req-1", capturedMdc.get("request_id"));
    assertNull(capturedMdc.get("email"));
    client.close();
  }

  @Test
  void ignoresThrowableFreeErrorsByDefaultAndMonicaLoggerAlways() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
    MonicaAppender appender = new MonicaAppender();
    appender.setContext(new LoggerContext());
    appender.setClient(client);
    appender.start();
    appender.doAppend(event("com.example.app.BotService", Level.ERROR, "message only", null));
    appender.doAppend(event("com.accelhack.monica.transport", Level.ERROR, "recursive",
        new IllegalStateException("send failed")));
    client.flush(Duration.ofMillis(200));
    assertEquals(0, sent.size());
    client.close();
  }

  @Test
  void capturesAllowedContextForExplicitMessageEvents() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
    MonicaAppender appender = new MonicaAppender();
    appender.setContext(new LoggerContext());
    appender.setClient(client);
    appender.setCaptureMessages(true);
    appender.start();
    appender.doAppend(event("com.example.app.BotService", Level.ERROR, "message only", null));
    client.flush(Duration.ofSeconds(1));

    MonicaEvent captured = sent.get(0).getItems().get(0);
    @SuppressWarnings("unchecked")
    Map<String, String> tags = (Map<String, String>) captured.get("tags");
    assertEquals("com.example.app.BotService", tags.get("logger"));
    @SuppressWarnings("unchecked")
    Map<String, Object> contexts = (Map<String, Object>) captured.get("contexts");
    @SuppressWarnings("unchecked")
    Map<String, Object> logback = (Map<String, Object>) contexts.get("logback");
    assertEquals("test-thread", logback.get("thread"));
    client.close();
  }

  private LoggingEvent event(String loggerName, Level level, String message, Throwable throwable) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName(loggerName);
    event.setLevel(level);
    event.setMessage(message);
    event.setThreadName("test-thread");
    if (throwable != null) event.setThrowableProxy(new ThrowableProxy(throwable));
    return event;
  }
}
