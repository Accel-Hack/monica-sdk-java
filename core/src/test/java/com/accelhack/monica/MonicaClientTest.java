package com.accelhack.monica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MonicaClientTest {
  @Test
  void capturesJavaExceptionWithPackagePathAndFlushes() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
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
        .presenceStore(alreadyReported())
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
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> true)
        .beforeSend((event, hint) -> { throw new IllegalStateException("hook failed"); })
        .build();
    assertNull(hookFailure.captureMessage("safe"));
    hookFailure.close();

    MonicaClient transportFailure = MonicaClient.builder()
        .presenceStore(alreadyReported())
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
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> true)
        .build();
    Throwable throwable = new IllegalArgumentException("same object");
    assertNotNull(client.captureException(throwable));
    assertNull(client.captureException(throwable));
    client.close();
  }

  @Test
  void stillSendsAThrowableThatWrapsACapturedOne() {
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> true)
        .build();
    // A connection pool logs its own failure, then the application logs the wrapped exception.
    Throwable root = new IllegalArgumentException("logged by the pool");
    Throwable wrapper = new RuntimeException("logged by the application",
        new IllegalStateException("middle", root));

    assertNotNull(client.captureException(root));
    assertNotNull(client.captureException(wrapper));
    client.close();
  }

  @Test
  void sendsAgainOnceTheDeduplicationWindowHasPassed() {
    AtomicLong now = new AtomicLong(1_000_000);
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> true)
        .clock(() -> Instant.ofEpochMilli(now.get()))
        .build();
    // HotSpot reuses one stackless NPE under OmitStackTraceInFastThrow; repeats must not vanish.
    Throwable reused = new NullPointerException();

    assertNotNull(client.captureException(reused));
    now.addAndGet(1_001);
    assertNotNull(client.captureException(new RuntimeException("wrapper", reused)));
    now.addAndGet(1_001);
    assertNotNull(client.captureException(reused));
    client.close();
  }

  @Test
  void skipsACauseCapturedAfterItsWrapper() {
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> true)
        .build();
    // The container logs only the root cause after the application and the resolver reported it.
    Throwable root = new IllegalArgumentException("root");
    Throwable wrapper = new RuntimeException("wrapper", new IllegalStateException("middle", root));

    assertNotNull(client.captureException(wrapper));
    assertNull(client.captureException(root));
    client.close();
  }

  @Test
  void survivesACyclicCauseChain() {
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> true)
        .build();
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalArgumentException("second", first);
    first.initCause(second);

    assertNotNull(client.captureException(first));
    assertNull(client.captureException(second));
    assertNotNull(client.captureException(new RuntimeException("unrelated", new Error())));
    client.close();
  }

  @Test
  void onlyDeduplicatesExceptionsThatPassedBeforeSend() {
    AtomicInteger attempts = new AtomicInteger();
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
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
        .presenceStore(alreadyReported())
        .dsn("https://mpk_public@example.test/project")
        .environment("test")
        .build());
  }

  @Test
  void rejectsAnEnvironmentOutsideTheProtocolLimit() {
    assertThrows(IllegalArgumentException.class, () -> MonicaClient.builder()
        .presenceStore(alreadyReported())
        .environment("x".repeat(MonicaOptions.MAX_ENVIRONMENT_LENGTH + 1))
        .transport(envelope -> true)
        .build());
  }

  @Test
  void requestScopeIsRemovedAfterClose() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = MonicaClient.builder()
        .presenceStore(alreadyReported())
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
        .presenceStore(alreadyReported())
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
        .presenceStore(alreadyReported())
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

  // --- presence heartbeat (client_report) -----------------------------------

  /**
   * A store that has just reported, so tests about other things see no heartbeat. Tests on a
   * fake clock set before the real time still get a start heartbeat; they do not count it.
   */
  static MonicaPresenceStore alreadyReported() {
    MonicaPresenceStore store = MonicaPresenceStore.inMemory();
    store.setLastReportedAt(System.currentTimeMillis());
    return store;
  }

  private static final long DAY = MonicaClient.PRESENCE_INTERVAL_MILLIS;

  /** Drives a client on a fake clock. The scheduled tick is a day out; tests call tick(). */
  private static final class Presence {
    final AtomicLong now = new AtomicLong(1_788_000_000_000L);
    final List<MonicaEnvelope> sent = Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<SendResult> answer = new AtomicReference<>(SendResult.of(true, 202));

    MonicaClient.Builder builder() {
      return MonicaClient.builder()
          .environment("test")
          .release("1.2.3")
          .flushInterval(Duration.ofDays(1))
          .clock(() -> Instant.ofEpochMilli(now.get()))
          .transport(new MonicaTransport() {
            @Override
            public boolean send(MonicaEnvelope envelope) {
              return deliver(envelope).isAccepted();
            }

            @Override
            public SendResult deliver(MonicaEnvelope envelope) {
              sent.add(envelope);
              return answer.get();
            }
          });
    }

    /** One entry per envelope: the heartbeat's trigger, or the first item's type. */
    List<String> sends() {
      List<String> kinds = new ArrayList<>();
      synchronized (sent) {
        for (MonicaEnvelope envelope : sent) {
          MonicaEvent first = envelope.getItems().get(0);
          kinds.add("client_report".equals(first.get("type"))
              ? String.valueOf(first.get("trigger")) : String.valueOf(first.get("type")));
        }
      }
      return kinds;
    }

    void advance(long millis) {
      now.addAndGet(millis);
    }
  }

  /** Stands in for device storage: keeps the rate too, as a distributable's store does. */
  private static final class DeviceStore implements MonicaPresenceStore {
    Long lastReportedAt;
    Long intervalMillis;
    Double sampleRate;

    @Override public Long getLastReportedAt() { return lastReportedAt; }
    @Override public void setLastReportedAt(long value) { lastReportedAt = value; }
    @Override public Long getIntervalMillis() { return intervalMillis; }
    @Override public void setIntervalMillis(long value) { intervalMillis = value; }
    @Override public Double getSampleRate() { return sampleRate; }
    @Override public void setSampleRate(double value) { sampleRate = value; }
  }

  @Test
  void sendsAStartClientReportAloneAtInit() {
    Presence presence = new Presence();
    try (MonicaClient client = presence.builder().build()) {
      assertTrue(client.flush(Duration.ofSeconds(1)));
      assertEquals(List.of("start"), presence.sends());
      MonicaEnvelope envelope = presence.sent.get(0);
      assertEquals(1, envelope.getItems().size(), "a client_report travels alone");
      assertEquals(Map.of("type", "client_report", "timestamp",
          Instant.ofEpochMilli(presence.now.get()).toString(), "platform", "java",
          "environment", "test", "trigger", "start", "release", "1.2.3"),
          envelope.getItems().get(0).values());
      assertEquals(MonicaOptions.DEFAULT_SDK_NAME, envelope.getSdk().get("name"));
      assertEquals(0, envelope.getDiscarded());
    }
  }

  @Test
  void sendsOneIntervalHeartbeatOnlyAfterAnIntervalOfSilence() {
    Presence presence = new Presence();
    try (MonicaClient client = presence.builder().build()) {
      client.flush(Duration.ofSeconds(1));
      presence.advance(DAY - 1);
      client.tick();
      assertEquals(List.of("start"), presence.sends(), "inside the interval nothing is sent");
      presence.advance(1);
      client.tick();
      client.tick();
      assertEquals(List.of("start", "interval"), presence.sends(), "one heartbeat, not two");
    }
  }

  @Test
  void anAcceptedErrorEnvelopePushesTheHeartbeatBack() {
    Presence presence = new Presence();
    try (MonicaClient client = presence.builder().build()) {
      client.flush(Duration.ofSeconds(1));
      presence.advance(DAY / 2);
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(1)));
      presence.advance(DAY / 2);
      client.tick();
      assertEquals(List.of("start", "error"), presence.sends());
      presence.advance(DAY / 2);
      client.tick();
      assertEquals(List.of("start", "error", "interval"), presence.sends());
    }
  }

  @Test
  void aFailedHeartbeatIsRetriedOnlyAnIntervalLater() {
    Presence presence = new Presence();
    presence.answer.set(SendResult.of(false, 503));
    try (MonicaClient client = presence.builder().build()) {
      client.flush(Duration.ofSeconds(1));
      presence.advance(60_000);
      client.tick();
      assertEquals(List.of("start"), presence.sends(), "a failure must not resend on every tick");
      presence.answer.set(SendResult.of(true, 202));
      presence.advance(DAY);
      client.tick();
      assertEquals(List.of("start", "interval"), presence.sends());
    }
  }

  @Test
  void theIntervalHeaderOfA202DecidesTheNextHeartbeat() {
    Presence presence = new Presence();
    presence.answer.set(SendResult.accepted(202, "120000", null));
    try (MonicaClient client = presence.builder().build()) {
      client.flush(Duration.ofSeconds(1));
      presence.answer.set(SendResult.of(true, 202));
      presence.advance(119_999);
      client.tick();
      assertEquals(List.of("start"), presence.sends());
      presence.advance(1);
      client.tick();
      assertEquals(List.of("start", "interval"), presence.sends(),
          "the header's interval replaces the contract default, and a 202 without it keeps it");
    }
  }

  @Test
  void brokenPresenceHeadersAreIgnoredOneByOne() {
    Presence presence = new Presence();
    DeviceStore store = new DeviceStore();
    presence.answer.set(SendResult.accepted(202, "120000", "0.5"));
    try (MonicaClient client = presence.builder().presenceStore(store).build()) {
      client.flush(Duration.ofSeconds(1));
      assertEquals(Long.valueOf(120_000), store.intervalMillis);
      assertEquals(Double.valueOf(0.5), store.sampleRate);
      String[][] broken = {
          {"59999", "0.009"}, {"1.2e5", "5e-1"}, {"120000.5", "1.5"}, {"-120000", "-0.5"},
          {"soon", "half"}, {"", ""}, {null, null}};
      for (String[] headers : broken) {
        presence.answer.set(SendResult.accepted(202, headers[0], headers[1]));
        client.captureMessage("error");
        client.flush(Duration.ofSeconds(1));
        assertEquals(Long.valueOf(120_000), store.intervalMillis, headers[0]);
        assertEquals(Double.valueOf(0.5), store.sampleRate, headers[1]);
      }
      presence.answer.set(SendResult.accepted(202, "86400000", "1"));
      client.captureMessage("error");
      client.flush(Duration.ofSeconds(1));
      assertEquals(Long.valueOf(DAY), store.intervalMillis);
      assertEquals(Double.valueOf(1), store.sampleRate);
    }
  }

  @Test
  void aServerClientReadsButNeverAppliesTheSampleRate() {
    Presence presence = new Presence();
    presence.answer.set(SendResult.accepted(202, null, "0.01"));
    try (MonicaClient client = presence.builder().random(() -> 0.99).build()) {
      client.flush(Duration.ofSeconds(1));
      presence.advance(DAY);
      client.tick();
      assertEquals(List.of("start", "interval"), presence.sends());
    }
  }

  @Test
  void aStoredReportSurvivesARestartOfADistributable() {
    Presence presence = new Presence();
    DeviceStore store = new DeviceStore();
    store.lastReportedAt = presence.now.get() - 1_000;
    try (MonicaClient client = presence.builder().presenceStore(store).build()) {
      client.flush(Duration.ofSeconds(1));
      client.checkPresence();
      client.flush(Duration.ofSeconds(1));
      assertEquals(List.of(), presence.sends(), "a restart inside the interval sends nothing");
      presence.advance(DAY);
      client.checkPresence();
      client.flush(Duration.ofSeconds(1));
      assertEquals(List.of("start"), presence.sends(), "returning to the foreground checks again");
    }
  }

  @Test
  void aDistributableSamplesItsHeartbeatAtTheStoredRate() {
    Presence presence = new Presence();
    DeviceStore store = new DeviceStore();
    store.sampleRate = 0.5;
    AtomicReference<Double> draw = new AtomicReference<>(0.7);
    try (MonicaClient client = presence.builder().presenceStore(store).random(draw::get).build()) {
      client.flush(Duration.ofSeconds(1));
      assertEquals(List.of(), presence.sends(), "a draw above the rate skips the heartbeat");
      draw.set(0.3);
      presence.advance(60_000);
      client.checkPresence();
      client.flush(Duration.ofSeconds(1));
      assertEquals(List.of(), presence.sends(), "a skipped heartbeat is not redrawn inside the interval");
      presence.advance(DAY);
      client.checkPresence();
      client.flush(Duration.ofSeconds(1));
      assertEquals(List.of("start"), presence.sends());
    }
  }

  @Test
  void queuedItemsHoldBackTheIntervalHeartbeat() throws Exception {
    AtomicLong now = new AtomicLong(1_788_000_000_000L);
    List<String> sends = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch senderBusy = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    MonicaClient client = MonicaClient.builder()
        .environment("test")
        .batchSize(2)
        .flushInterval(Duration.ofDays(1))
        .clock(() -> Instant.ofEpochMilli(now.get()))
        .transport(envelope -> {
          MonicaEvent first = envelope.getItems().get(0);
          boolean heartbeat = "client_report".equals(first.get("type"));
          sends.add(heartbeat ? String.valueOf(first.get("trigger")) : "error");
          // Hold the sender inside its first error batch so the queue cannot drain behind the test.
          if (!heartbeat && Thread.currentThread().getName().equals("monica-java-sender")
              && senderBusy.getCount() > 0) {
            senderBusy.countDown();
            release.await(5, TimeUnit.SECONDS);
          }
          // Errors fail, so no 202 refreshes the presence time: only the queue holds it back.
          return heartbeat;
        })
        .build();
    try {
      client.flush(Duration.ofSeconds(1));
      now.addAndGet(DAY);
      for (int index = 0; index < 6; index++) client.captureMessage("queued " + index);
      assertTrue(senderBusy.await(1, TimeUnit.SECONDS));
      client.tick();
      assertTrue(client.stats().getQueued() > 0, "the tick drains one batch only");
      assertFalse(sends.contains("interval"),
          "a queue with items must not send an interval heartbeat: " + sends);
    } finally {
      release.countDown();
      client.close();
    }
  }

  @Test
  void aNon2xxIsNeverAcceptedAndDropsThePresenceHeaders() {
    SendResult result = SendResult.accepted(503, "120000", "0.5");
    assertFalse(result.isAccepted());
    assertEquals(java.util.OptionalInt.of(503), result.getStatus());
    assertNull(result.getPresenceIntervalMs());
    assertNull(result.getPresenceSampleRate());
    assertTrue(SendResult.accepted(202, "120000", "0.5").isAccepted());
  }

  @Test
  void aStoredTimeInTheFutureCountsAsNothingStored() {
    Presence presence = new Presence();
    DeviceStore store = new DeviceStore();
    store.lastReportedAt = presence.now.get() + DAY;
    try (MonicaClient client = presence.builder().presenceStore(store).build()) {
      client.flush(Duration.ofSeconds(1));
      assertEquals(List.of("start"), presence.sends(), "a clock set back must not silence presence");
    }
  }
}
