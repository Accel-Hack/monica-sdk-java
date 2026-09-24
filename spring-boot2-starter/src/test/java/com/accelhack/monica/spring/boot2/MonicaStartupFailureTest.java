package com.accelhack.monica.spring.boot2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class MonicaStartupFailureTest {
  private static final List<MonicaEvent> EVENTS = new CopyOnWriteArrayList<>();
  private static volatile MonicaClient injected;

  @BeforeEach
  void reset() {
    EVENTS.clear();
    injected = null;
  }

  @Test
  void reportsAFailedRefreshAsFatalAndClosesTheClient() {
    assertThrows(Exception.class, () -> application(FailingBeanApp.class).run());

    assertEquals(1, EVENTS.size());
    assertEquals("fatal", EVENTS.get(0).get("level"));
    assertEquals("spring_boot_startup", tags(EVENTS.get(0)).get("integration"));
    assertClosed(injected);
  }

  @Test
  void reportsARunnerFailureOnceAndClosesTheClient() {
    assertThrows(Exception.class, () -> application(FailingRunnerApp.class).run());

    // SpringApplication also logs "Application run failed" with the same Throwable; the
    // appender's capture of it is deduplicated.
    assertEquals(1, EVENTS.size());
    assertEquals("fatal", EVENTS.get(0).get("level"));
    assertClosed(injected);
  }

  @Test
  void closesTheClientWhenARunningContextCloses() {
    ConfigurableApplicationContext context = application(HealthyApp.class).run();
    MonicaClient client = context.getBean(MonicaClient.class);
    context.close();

    assertTrue(EVENTS.isEmpty());
    assertClosed(client);
  }

  private static SpringApplication application(Class<?> source) {
    SpringApplication application = new SpringApplication(source);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    application.setDefaultProperties(java.util.Map.of(
        "monica.dsn", "https://msk_test@localhost:9876/project",
        "monica.environment", "test",
        "monica.flush-timeout", "100ms"));
    return application;
  }

  // A closed client drops captures before beforeSend runs.
  private static void assertClosed(MonicaClient client) {
    assertNotNull(client);
    int before = EVENTS.size();
    client.captureMessage("after close");
    assertEquals(before, EVENTS.size());
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> tags(MonicaEvent event) {
    return (java.util.Map<String, Object>) event.get("tags");
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({MonicaAutoConfiguration.class, MonicaLogbackAutoConfiguration.class})
  static class HealthyApp {
    @Bean
    BeforeSend recordingBeforeSend() {
      return (event, hint) -> {
        EVENTS.add(event);
        return event; // dedup only remembers events that pass beforeSend; the send itself fails
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class FailingBeanApp extends HealthyApp {
    // Created before MONICA's own beans, so only the client exists when startup fails.
    @Bean
    Object clientUser(MonicaClient client) {
      injected = client;
      return new Object();
    }

    @Bean
    Object failing() {
      throw new IllegalStateException("bean creation failed");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class FailingRunnerApp extends HealthyApp {
    @Bean
    ApplicationRunner failingRunner(MonicaClient client) {
      injected = client;
      return args -> {
        throw new IllegalStateException("runner failed");
      };
    }
  }
}
