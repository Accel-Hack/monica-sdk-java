package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import java.time.Duration;

public final class MonicaTestService {
  private final MonicaClient client;
  private final Duration timeout;

  MonicaTestService(MonicaClient client, Duration timeout) {
    this.client = client;
    this.timeout = timeout;
  }

  public boolean sendTestEvent() {
    return client.captureMessage("MONICA Spring Boot connectivity test", "info") != null
        && client.flush(timeout);
  }
}
