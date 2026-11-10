package com.accelhack.monica.spring.boot4;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** monica-logback is compiled against Logback 1.2; Spring Boot 4 runs it on Logback 1.5. */
class MonicaLogbackIntegrationTest {
  @Test
  void capturesAnErrorLoggedThroughTheRegisteredAppenderAndDetachesOnClose() {
    List<MonicaEvent> events = new CopyOnWriteArrayList<>();
    Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(Logger.ROOT_LOGGER_NAME);

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            MonicaAutoConfiguration.class, MonicaLogbackAutoConfiguration.class))
        .withBean(BeforeSend.class, () -> (event, hint) -> {
          events.add(event);
          return null;
        })
        .withPropertyValues(
            "monica.dsn=https://msk_test@localhost:9876/project",
            "monica.environment=test",
            "monica.logback.allowed-mdc-keys[0]=request_id")
        .run(context -> {
          assertThat(root.getAppender("MONICA")).isNotNull();
          MDC.put("request_id", "req-1");
          try {
            LoggerFactory.getLogger("com.example.app.Checkout")
                .error("charge failed", new IllegalStateException("gateway down"));
          } finally {
            MDC.remove("request_id");
          }

          assertThat(events).hasSize(1);
          MonicaEvent event = events.get(0);
          assertThat(event.get("level")).isEqualTo("error");
          assertThat(event.get("message")).isEqualTo("charge failed");
          assertThat(map(event.get("tags")))
              .containsEntry("logger", "com.example.app.Checkout")
              .containsEntry("log_level", "ERROR");
          Map<String, Object> logback = map(map(event.get("contexts")).get("logback"));
          assertThat(logback)
              .containsEntry("thread", Thread.currentThread().getName())
              .containsEntry("mdc", Map.of("request_id", "req-1"));
        });

    assertThat(root.getAppender("MONICA")).isNull();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
