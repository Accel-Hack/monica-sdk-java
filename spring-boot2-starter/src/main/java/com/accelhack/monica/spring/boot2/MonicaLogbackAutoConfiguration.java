package com.accelhack.monica.spring.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.logback.MonicaAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(MonicaAutoConfiguration.class)
@ConditionalOnBean(MonicaClient.class)
@ConditionalOnClass({LoggerContext.class, MonicaAppender.class})
@ConditionalOnProperty(prefix = "monica.logback", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class MonicaLogbackAutoConfiguration {
  @Bean
  public MonicaLogbackRegistration monicaLogbackRegistration(MonicaClient client,
      MonicaProperties properties) {
    return register(LoggerFactory.getILoggerFactory(), client, properties);
  }

  static MonicaLogbackRegistration register(Object loggerFactory, MonicaClient client,
      MonicaProperties properties) {
    if (!(loggerFactory instanceof LoggerContext)) {
      return MonicaLogbackRegistration.inactive();
    }
    LoggerContext context = (LoggerContext) loggerFactory;
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    MonicaAppender appender = new MonicaAppender();
    try {
      appender.setName("MONICA");
      appender.setContext(context);
      appender.setClient(client);
      appender.setCaptureMessages(properties.getLogback().isCaptureMessages());
      appender.setAllowedMdcKeys(String.join(",", properties.getLogback().getAllowedMdcKeys()));
      appender.start();
      root.addAppender(appender);
      return new MonicaLogbackRegistration(root, appender);
    } catch (Throwable ignored) {
      // An optional monitoring integration must not prevent application startup.
      try {
        root.detachAppender(appender);
      } catch (Throwable cleanupFailure) {
        // Cleanup is best effort for the same reason.
      }
      try {
        appender.stop();
      } catch (Throwable cleanupFailure) {
        // Cleanup is best effort for the same reason.
      }
      return MonicaLogbackRegistration.inactive();
    }
  }
}
