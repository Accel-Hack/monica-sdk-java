package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.task.TaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(MonicaAutoConfiguration.class)
@ConditionalOnBean(MonicaClient.class)
@ConditionalOnClass({TaskSchedulerCustomizer.class, ThreadPoolTaskScheduler.class})
public class MonicaSchedulingAutoConfiguration {
  @Bean
  public TaskSchedulerCustomizer monicaTaskSchedulerCustomizer(MonicaClient client) {
    return scheduler -> scheduler.setErrorHandler(new MonicaScheduledTaskErrorHandler(client));
  }
}
