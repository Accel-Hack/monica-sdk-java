package com.accelhack.monica.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MonicaAppender extends AppenderBase<ILoggingEvent> {
  private static final String MONICA_LOGGER_PREFIX = "com.accelhack.monica";

  private MonicaClient client;
  private Level threshold = Level.ERROR;
  private boolean captureMessages;
  private Set<String> allowedMdcKeys = Collections.emptySet();

  public void setClient(MonicaClient client) {
    this.client = client;
  }

  public void setThreshold(String threshold) {
    this.threshold = Level.toLevel(threshold, Level.ERROR);
  }

  public void setCaptureMessages(boolean captureMessages) {
    this.captureMessages = captureMessages;
  }

  public void setAllowedMdcKeys(String commaSeparated) {
    if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
      allowedMdcKeys = Collections.emptySet();
      return;
    }
    allowedMdcKeys = Arrays.stream(commaSeparated.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  protected void append(ILoggingEvent event) {
    try {
      if (client == null || event == null || !event.getLevel().isGreaterOrEqual(threshold)) return;
      if (event.getLoggerName() != null && event.getLoggerName().startsWith(MONICA_LOGGER_PREFIX)) return;
      Throwable throwable = throwable(event.getThrowableProxy());
      CaptureContext context = CaptureContext.create()
          .level(toMonicaLevel(event.getLevel()))
          .message(event.getMessage())
          .handled(true)
          .tag("logger", value(event.getLoggerName()))
          .tag("log_level", event.getLevel().levelStr)
          .context("logback", logbackContext(event));
      if (throwable != null) client.captureException(throwable, context);
      else if (captureMessages) client.captureMessage(event.getMessage(), context);
    } catch (Throwable ignored) {
      // Logging must not recurse or fail the host application.
    }
  }

  private Map<String, Object> logbackContext(ILoggingEvent event) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("thread", event.getThreadName());
    Map<String, String> allowed = new LinkedHashMap<>();
    Map<String, String> mdc = event.getMDCPropertyMap();
    for (String key : allowedMdcKeys) {
      if (mdc != null && mdc.containsKey(key)) allowed.put(key, mdc.get(key));
    }
    if (!allowed.isEmpty()) context.put("mdc", allowed);
    return context;
  }

  private static Throwable throwable(IThrowableProxy proxy) {
    return proxy instanceof ThrowableProxy ? ((ThrowableProxy) proxy).getThrowable() : null;
  }

  private static String toMonicaLevel(Level level) {
    if (level.isGreaterOrEqual(Level.ERROR)) return "error";
    if (level.isGreaterOrEqual(Level.WARN)) return "warning";
    if (level.isGreaterOrEqual(Level.INFO)) return "info";
    return "debug";
  }

  private static String value(String input) {
    return input == null ? "unknown" : input;
  }
}
