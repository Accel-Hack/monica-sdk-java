package com.accelhack.monica.spring.boot2;

import ch.qos.logback.classic.Logger;
import com.accelhack.monica.logback.MonicaAppender;
import org.springframework.beans.factory.DisposableBean;

public final class MonicaLogbackRegistration implements DisposableBean {
  private final Logger logger;
  private final MonicaAppender appender;

  MonicaLogbackRegistration(Logger logger, MonicaAppender appender) {
    this.logger = logger;
    this.appender = appender;
  }

  static MonicaLogbackRegistration inactive() {
    return new MonicaLogbackRegistration(null, null);
  }

  boolean isActive() {
    return logger != null && appender != null;
  }

  @Override
  public void destroy() {
    if (logger != null && appender != null) {
      try {
        logger.detachAppender(appender);
      } catch (Throwable ignored) {
        // Optional monitoring cleanup must not fail application shutdown.
      }
      try {
        appender.stop();
      } catch (Throwable ignored) {
        // Optional monitoring cleanup must not fail application shutdown.
      }
    }
  }
}
