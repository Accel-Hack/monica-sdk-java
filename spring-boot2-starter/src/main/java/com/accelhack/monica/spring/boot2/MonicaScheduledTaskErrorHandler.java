package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaClient;
import org.springframework.scheduling.support.TaskUtils;
import org.springframework.util.ErrorHandler;

final class MonicaScheduledTaskErrorHandler implements ErrorHandler {
  private final MonicaClient client;
  private final ErrorHandler defaultErrorHandler = TaskUtils.getDefaultErrorHandler(true);

  MonicaScheduledTaskErrorHandler(MonicaClient client) {
    this.client = client;
  }

  @Override
  public void handleError(Throwable error) {
    try {
      client.captureException(error,
          CaptureContext.create().handled(false).tag("integration", "spring_scheduled"));
    } finally {
      // Preserve Spring's existing ERROR log and repeating-task suppression.
      // The core identity dedupe prevents the Logback appender from sending the
      // same Throwable for a second time.
      defaultErrorHandler.handleError(error);
    }
  }
}
