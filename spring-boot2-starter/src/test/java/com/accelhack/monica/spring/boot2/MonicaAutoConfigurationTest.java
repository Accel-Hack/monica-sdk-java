package com.accelhack.monica.spring.boot2;

import static org.assertj.core.api.Assertions.assertThat;

import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.task.TaskSchedulerCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class MonicaAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(MonicaAutoConfiguration.class));

  @Test
  void createsClientFromMonicaProperties() {
    contextRunner.withPropertyValues(
        "monica.dsn=https://msk_test@localhost:9876/project",
        "monica.environment=test",
        "monica.release=abc123",
        "monica.in-app-packages[0]=com.example.app",
        "monica.flush-timeout=150ms")
        .run(context -> {
          assertThat(context).hasSingleBean(MonicaClient.class);
          assertThat(context).hasSingleBean(MonicaTestService.class);
          assertThat(context.getBean(MonicaProperties.class).getInAppPackages())
              .containsExactly("com.example.app");
        });
  }

  @Test
  void staysDisabledWithoutDsnOrWhenExplicitlyDisabled() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(MonicaClient.class));
    contextRunner.withPropertyValues(
        "monica.enabled=false",
        "monica.dsn=https://msk_test@localhost:9876/project")
        .run(context -> assertThat(context).doesNotHaveBean(MonicaClient.class));
  }

  @Test
  void treatsBlankDsnAsUnset() {
    WebApplicationContextRunner fullStarter = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            MonicaAutoConfiguration.class,
            MonicaWebAutoConfiguration.class,
            MonicaSchedulingAutoConfiguration.class,
            MonicaLogbackAutoConfiguration.class,
            MonicaHealthAutoConfiguration.class,
            TaskSchedulingAutoConfiguration.class));
    for (String dsn : new String[] {"", "  "}) {
      fullStarter.withPropertyValues("monica.dsn=" + dsn).run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(MonicaClient.class);
        assertThat(context).doesNotHaveBean(MonicaLifecycle.class);
        assertThat(context).doesNotHaveBean(MonicaTestService.class);
        assertThat(context).doesNotHaveBean(MonicaLogbackRegistration.class);
        assertThat(context).doesNotHaveBean(MonicaExceptionResolver.class);
        assertThat(context).doesNotHaveBean(MonicaRequestScopeFilter.class);
        assertThat(context).doesNotHaveBean(TaskSchedulerCustomizer.class);
        assertThat(context).doesNotHaveBean("monicaHealthIndicator");
      });
    }
  }

  @Test
  void appliesAnApplicationBeforeSendBean() {
    AtomicInteger calls = new AtomicInteger();
    contextRunner
        .withBean(BeforeSend.class, () -> (event, hint) -> {
          calls.incrementAndGet();
          return null;
        })
        .withPropertyValues(
            "monica.dsn=https://msk_test@localhost:9876/project",
            "monica.environment=test")
        .run(context -> {
          assertThat(context.getBean(MonicaClient.class).captureMessage("scrub me")).isNull();
          assertThat(calls).hasValue(1);
        });
  }

  @Test
  void leavesTheHostRunningWhenSlf4jUsesAnotherBackend() throws Exception {
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> true)
        .build();
    try {
      MonicaLogbackRegistration registration = MonicaLogbackAutoConfiguration.register(
          new Object(), client, new MonicaProperties());

      assertThat(registration.isActive()).isFalse();
      registration.destroy();
    } finally {
      client.close();
    }
  }
}
