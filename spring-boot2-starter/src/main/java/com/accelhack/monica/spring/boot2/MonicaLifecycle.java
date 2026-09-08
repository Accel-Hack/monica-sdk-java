package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import java.time.Duration;
import org.springframework.beans.factory.DisposableBean;

public final class MonicaLifecycle implements DisposableBean {
  private final MonicaClient client;
  private final Duration timeout;

  MonicaLifecycle(MonicaClient client, Duration timeout) {
    this.client = client;
    this.timeout = timeout;
  }

  @Override
  public void destroy() {
    client.close(timeout);
  }
}
