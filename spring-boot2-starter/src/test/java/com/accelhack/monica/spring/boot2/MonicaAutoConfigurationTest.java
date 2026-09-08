package com.accelhack.monica.spring.boot2;

import static org.assertj.core.api.Assertions.assertThat;

import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
