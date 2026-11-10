package com.accelhack.monica.spring.boot4;

import com.accelhack.monica.MonicaClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@AutoConfiguration(after = MonicaAutoConfiguration.class)
@ConditionalOnBean(MonicaClient.class)
@ConditionalOnClass({ThreadPoolTaskSchedulerCustomizer.class, ThreadPoolTaskScheduler.class})
public class MonicaSchedulingAutoConfiguration {
  @Bean
  public ThreadPoolTaskSchedulerCustomizer monicaTaskSchedulerCustomizer(MonicaClient client) {
    return scheduler -> scheduler.setErrorHandler(new MonicaScheduledTaskErrorHandler(client));
  }

  // With spring.threads.virtual.enabled=true, Boot builds a SimpleAsyncTaskScheduler instead.
  @Bean
  public SimpleAsyncTaskSchedulerCustomizer monicaSimpleAsyncTaskSchedulerCustomizer(
      MonicaClient client) {
    return scheduler -> scheduler.setErrorHandler(new MonicaScheduledTaskErrorHandler(client));
  }
}
