package com.accelhack.monica.spring.boot2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.logback.MonicaAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MonicaSchedulingIntegrationTest {
  @Test
  void capturesAndPreservesTheDefaultScheduledTaskErrorLogWithoutDuplication() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(loggerContext);
    logs.start();
    MonicaAppender monicaAppender = new MonicaAppender();
    monicaAppender.setContext(loggerContext);
    monicaAppender.setClient(client);
    monicaAppender.start();
    root.addAppender(logs);
    root.addAppender(monicaAppender);
    IllegalStateException failure = new IllegalStateException("scheduled failure");

    try {
      new MonicaScheduledTaskErrorHandler(client).handleError(failure);
      assertTrue(client.flush(Duration.ofSeconds(1)));

      assertEquals(1, sent.stream().mapToInt(envelope -> envelope.getItems().size()).sum());
      ILoggingEvent log = logs.list.stream()
          .filter(event -> event.getFormattedMessage().contains(
              "Unexpected error occurred in scheduled task"))
          .findFirst()
          .orElseThrow(AssertionError::new);
      assertEquals("scheduled failure", log.getThrowableProxy().getMessage());
    } finally {
      root.detachAppender(monicaAppender);
      root.detachAppender(logs);
      monicaAppender.stop();
      logs.stop();
      client.close();
    }
  }
}
