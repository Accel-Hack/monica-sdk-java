package com.accelhack.monica.spring.boot4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import com.accelhack.monica.MonicaPresenceStore;
import com.accelhack.monica.logback.MonicaAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class MonicaSchedulingIntegrationTest {
  @Test
  void capturesAFailingScheduledMethod() {
    assertScheduledFailureCaptured(scheduling(), ThreadPoolTaskScheduler.class);
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void capturesAFailingScheduledMethodOnVirtualThreads() {
    assertScheduledFailureCaptured(
        scheduling().withPropertyValues("spring.threads.virtual.enabled=true"),
        SimpleAsyncTaskScheduler.class);
  }

  private static void assertScheduledFailureCaptured(ApplicationContextRunner runner,
      Class<?> schedulerType) {
    List<MonicaEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch captured = new CountDownLatch(1);
    runner
        .withBean(BeforeSend.class, () -> (event, hint) -> {
          events.add(event);
          captured.countDown();
          return null;
        })
        .run(context -> {
          assertInstanceOf(schedulerType, context.getBean("taskScheduler"));
          assertTrue(captured.await(5, TimeUnit.SECONDS));
          MonicaEvent event = events.get(0);
          assertEquals("scheduled failure", event.get("message"));
          @SuppressWarnings("unchecked")
          Map<String, Object> tags = (Map<String, Object>) event.get("tags");
          assertEquals("spring_scheduled", tags.get("integration"));
        });
  }

  private static ApplicationContextRunner scheduling() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            MonicaAutoConfiguration.class,
            MonicaSchedulingAutoConfiguration.class,
            TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(FailingJob.class)
        .withPropertyValues(
            "monica.dsn=https://msk_test@localhost:9876/project",
            "monica.environment=test");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class FailingJob {
    @Scheduled(fixedDelay = 60_000)
    void fail() {
      throw new IllegalStateException("scheduled failure");
    }
  }

  @Test
  void capturesAndPreservesTheDefaultScheduledTaskErrorLogWithoutDuplication() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
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

  /** Keeps the init heartbeat out of what these tests count. */
  private static MonicaPresenceStore alreadyReported() {
    MonicaPresenceStore store = MonicaPresenceStore.inMemory();
    store.setLastReportedAt(System.currentTimeMillis());
    return store;
  }
}
