package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonicaProperties.class)
@ConditionalOnProperty(prefix = "monica", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MonicaAutoConfiguration {
  @Bean(destroyMethod = "")
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "monica", name = "dsn")
  public MonicaClient monicaClient(MonicaProperties properties,
      ObjectProvider<BeforeSend> beforeSendProvider) {
    MonicaClient.Builder builder = MonicaClient.builder()
        .dsn(properties.getDsn())
        .environment(properties.getEnvironment())
        .release(properties.getRelease())
        .serverName(properties.getServerName())
        .inAppPackages(properties.getInAppPackages())
        .flushTimeout(properties.getFlushTimeout())
        .flushInterval(properties.getFlushInterval())
        .maxQueueSize(properties.getMaxQueueSize())
        .batchSize(properties.getBatchSize())
        .sampleRate(properties.getSampleRate());
    BeforeSend beforeSend = beforeSendProvider.getIfAvailable();
    if (beforeSend != null) builder.beforeSend(beforeSend);
    return builder.build();
  }

  @Bean
  @ConditionalOnBean(MonicaClient.class)
  public MonicaLifecycle monicaLifecycle(MonicaClient client, MonicaProperties properties) {
    return new MonicaLifecycle(client, properties.getFlushTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(MonicaClient.class)
  public MonicaTestService monicaTestService(MonicaClient client, MonicaProperties properties) {
    return new MonicaTestService(client, properties.getFlushTimeout());
  }
}
