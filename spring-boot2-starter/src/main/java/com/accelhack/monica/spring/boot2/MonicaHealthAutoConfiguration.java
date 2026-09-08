package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaStats;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(MonicaAutoConfiguration.class)
@ConditionalOnBean(MonicaClient.class)
@ConditionalOnClass(HealthIndicator.class)
public class MonicaHealthAutoConfiguration {
  @Bean(name = "monicaHealthIndicator")
  @ConditionalOnMissingBean(name = "monicaHealthIndicator")
  public HealthIndicator monicaHealthIndicator(MonicaClient client) {
    return () -> {
      MonicaStats stats = client.stats();
      return Health.up()
          .withDetail("bestEffort", true)
          .withDetail("queued", stats.getQueued())
          .withDetail("discarded", stats.getDiscarded())
          .build();
    };
  }
}
