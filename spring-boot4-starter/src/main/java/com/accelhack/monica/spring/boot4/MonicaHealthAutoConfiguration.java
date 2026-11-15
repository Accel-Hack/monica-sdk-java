package com.accelhack.monica.spring.boot4;

import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaStats;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = MonicaAutoConfiguration.class)
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
