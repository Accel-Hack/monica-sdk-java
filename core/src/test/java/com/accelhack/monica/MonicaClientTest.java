package com.accelhack.monica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonicaClientTest {
  @Test
  void capturesJavaExceptionWithPackagePathAndFlushes() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .release("abc123")
        .inAppPackage("com.accelhack.monica")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();

    String eventId = client.captureException(new IllegalStateException("boom"));

    assertNotNull(eventId);
    assertTrue(client.flush(Duration.ofSeconds(1)));
    assertEquals(1, sent.size());
    MonicaEvent item = sent.get(0).getItems().get(0);
    assertEquals("java", item.get("platform"));
    assertEquals("abc123", item.get("release"));
    @SuppressWarnings("unchecked")
    Map<String, Object> exception = (Map<String, Object>) item.get("exception");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> values = (List<Map<String, Object>>) exception.get("values");
    assertEquals("java.lang.IllegalStateException", values.get(0).get("type"));
    client.close();
  }

  @Test
  void boundsQueueAndReportsDiscardedItems() throws Exception {
    List<MonicaEnvelope> sent = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch transportStarted = new CountDownLatch(1);
    CountDownLatch releaseTransport = new CountDownLatch(1);
    AtomicBoolean firstSend = new AtomicBoolean(true);
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .maxQueueSize(2)
        .batchSize(2)
        .transport(envelope -> {
          if (firstSend.compareAndSet(true, false)) {
            transportStarted.countDown();
            releaseTransport.await(2, TimeUnit.SECONDS);
          }
          sent.add(envelope);
          return true;
        })
        .build();

    try {
      client.captureMessage("one");
      client.captureMessage("two");
      assertTrue(transportStarted.await(1, TimeUnit.SECONDS));
      client.captureMessage("three");
      client.captureMessage("four");
      client.captureMessage("five");
      releaseTransport.countDown();

      assertTrue(client.flush(Duration.ofSeconds(1)));
      assertEquals(1, sent.stream().mapToLong(MonicaEnvelope::getDiscarded).sum());
    } finally {
      releaseTransport.countDown();
      client.close();
    }
  }

  @Test
  void beforeSendFailureAndTransportFailureNeverEscape() {
    MonicaClient hookFailure = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> true)
        .beforeSend((event, hint) -> { throw new IllegalStateException("hook failed"); })
        .build();
    assertNull(hookFailure.captureMessage("safe"));
    hookFailure.close();

    MonicaClient transportFailure = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { throw new IllegalStateException("MONICA unavailable"); })
        .build();
    assertNotNull(transportFailure.captureMessage("host remains healthy"));
    assertFalse(transportFailure.flush(Duration.ofSeconds(1)));
    assertEquals(1, transportFailure.stats().getDiscarded());
    transportFailure.close();
  }

  @Test
  void deduplicatesSameThrowableAcrossIntegrations() {
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> true)
        .build();
    Throwable throwable = new IllegalArgumentException("same object");
    assertNotNull(client.captureException(throwable));
    assertNull(client.captureException(throwable));
    client.close();
  }

  @Test
  void onlyDeduplicatesExceptionsThatPassedBeforeSend() {
    AtomicInteger attempts = new AtomicInteger();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> true)
        .beforeSend((event, hint) -> attempts.incrementAndGet() == 1 ? null : event)
        .build();
    Throwable throwable = new IllegalArgumentException("same object");

    assertNull(client.captureException(throwable));
    assertNotNull(client.captureException(throwable));
    client.close();
  }

  @Test
  void defaultHttpTransportRequiresASecretDsn() {
    assertThrows(IllegalArgumentException.class, () -> MonicaClient.builder()
        .dsn("https://mpk_public@example.test/project")
        .environment("test")
        .build());
  }

  @Test
  void rejectsAnEnvironmentOutsideTheProtocolLimit() {
    assertThrows(IllegalArgumentException.class, () -> MonicaClient.builder()
        .environment("x".repeat(MonicaOptions.MAX_ENVIRONMENT_LENGTH + 1))
        .transport(envelope -> true)
        .build());
  }

  @Test
  void requestScopeIsRemovedAfterClose() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
    try (MonicaClient.ScopeHandle scope = client.pushScope()) {
      scope.scope().setTag("request", "inside");
      client.captureMessage("scoped");
    }
    client.captureMessage("plain");
    client.flush(Duration.ofSeconds(1));
    List<MonicaEvent> items = new ArrayList<>();
    sent.forEach(envelope -> items.addAll(envelope.getItems()));
    @SuppressWarnings("unchecked")
    Map<String, String> tags = (Map<String, String>) items.get(0).get("tags");
    assertEquals("inside", tags.get("request"));
    assertNull(items.get(1).get("tags"));
    client.close();
  }

  @Test
  void splitsAByteHeavyBatchIntoSafeEnvelopes() throws Exception {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .batchSize(10)
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();

    client.captureMessage("a".repeat(600_000));
    client.captureMessage("b".repeat(600_000));

    assertTrue(client.flush(Duration.ofSeconds(2)));
    assertEquals(2, sent.size());
    ObjectMapper mapper = new ObjectMapper();
    for (MonicaEnvelope envelope : sent) {
      assertTrue(mapper.writeValueAsBytes(envelope).length
          <= MonicaClient.MAX_SAFE_ENVELOPE_JSON_BYTES);
    }
    client.close();
  }

  @Test
  void dropsOnlyAnIndividuallyOversizedEvent() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .batchSize(10)
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();

    client.captureMessage("oversized context",
        CaptureContext.create().context("payload", "x".repeat(1_100_000)));
    client.captureMessage("normal");

    assertFalse(client.flush(Duration.ofSeconds(2)));
    assertEquals(1, sent.size());
    assertEquals(1, sent.get(0).getDiscarded());
    assertEquals("normal", sent.get(0).getItems().get(0).get("message"));
    client.close();
  }
}
