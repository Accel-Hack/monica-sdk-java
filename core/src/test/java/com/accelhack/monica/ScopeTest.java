package com.accelhack.monica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopeTest {
  private static MonicaEvent captureWith(MonicaClient.Builder builder,
      java.util.function.Consumer<Scope> arrange) {
    List<MonicaEnvelope> sent = new ArrayList<>();
    try (MonicaClient client = builder.transport(envelope -> { sent.add(envelope); return true; })
        .build()) {
      arrange.accept(client.globalScope());
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(1)));
    }
    return sent.get(0).getItems().get(0);
  }

  @Test
  void dropsTheOldestBreadcrumbOnceTheBoundIsReached() {
    MonicaEvent item = captureWith(
        MonicaClient.builder().environment("test").maxBreadcrumbs(2),
        scope -> {
          scope.addBreadcrumb("ui.click", "first");
          scope.addBreadcrumb("ui.click", "second");
          scope.addBreadcrumb("ui.click", "third");
        });

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breadcrumbs = (List<Map<String, Object>>) item.get("breadcrumbs");
    assertEquals(2, breadcrumbs.size());
    assertEquals("second", breadcrumbs.get(0).get("message"));
    assertEquals("third", breadcrumbs.get(1).get("message"));
  }

  @Test
  void carriesTheBoundIntoARequestScope() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    try (MonicaClient client = MonicaClient.builder()
        .environment("test")
        .maxBreadcrumbs(1)
        .transport(envelope -> { sent.add(envelope); return true; })
        .build()) {
      try (MonicaClient.ScopeHandle handle = client.pushScope()) {
        handle.scope().addBreadcrumb("ui.click", "first");
        handle.scope().addBreadcrumb("ui.click", "second");
        client.captureMessage("boom");
      }
      assertTrue(client.flush(Duration.ofSeconds(1)));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breadcrumbs =
        (List<Map<String, Object>>) sent.get(0).getItems().get(0).get("breadcrumbs");
    assertEquals(1, breadcrumbs.size());
    assertEquals("second", breadcrumbs.get(0).get("message"));
  }

  @Test
  void sendsNoUserUntilOneIsSet() {
    MonicaEvent absent = captureWith(MonicaClient.builder().environment("test"), scope -> { });
    assertNull(absent.get("user"));

    Map<String, Object> user = new LinkedHashMap<>();
    user.put("id", "u_123");
    MonicaEvent present = captureWith(MonicaClient.builder().environment("test"),
        scope -> scope.setUser(user));
    assertEquals(Collections.singletonMap("id", "u_123"), present.get("user"));
  }

  @Test
  void clearingTheUserRemovesItFromLaterEvents() {
    MonicaEvent item = captureWith(MonicaClient.builder().environment("test"), scope -> {
      scope.setUser(Collections.singletonMap("id", "u_123"));
      scope.setUser(null);
    });
    assertNull(item.get("user"));
  }

  @Test
  void reportsTheIntegrationThatProducedTheEnvelope() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    try (MonicaClient client = MonicaClient.builder()
        .environment("test")
        .sdk("com.accelhack.monica:monica-android", "9.9.9")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build()) {
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(1)));
    }
    assertEquals("com.accelhack.monica:monica-android", sent.get(0).getSdk().get("name"));
    assertEquals("9.9.9", sent.get(0).getSdk().get("version"));
  }

  @Test
  void defaultsToTheCoreIdentity() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    try (MonicaClient client = MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build()) {
      client.captureMessage("boom");
      assertTrue(client.flush(Duration.ofSeconds(1)));
    }
    assertEquals("com.accelhack.monica:monica-core", sent.get(0).getSdk().get("name"));
    assertEquals(MonicaOptions.DEFAULT_SDK_VERSION, sent.get(0).getSdk().get("version"));
  }

  @Test
  void rejectsAnEmptySdkNameOrVersion() {
    assertThrows(IllegalArgumentException.class, () -> MonicaClient.builder()
        .environment("test")
        .sdk("  ", "1.0.0")
        .transport(envelope -> true)
        .build());
    assertThrows(IllegalArgumentException.class, () -> MonicaClient.builder()
        .environment("test")
        .sdk("name", "  ")
        .transport(envelope -> true)
        .build());
  }

  @Test
  void rejectsANonPositiveBreadcrumbBound() {
    assertThrows(IllegalArgumentException.class, () -> MonicaClient.builder()
        .environment("test")
        .maxBreadcrumbs(0)
        .transport(envelope -> true)
        .build());
    assertThrows(IllegalArgumentException.class, () -> new Scope(-1));
  }

  @Test
  void aStandaloneScopeKeepsTheDefaultHundredBreadcrumbs() {
    Scope scope = new Scope();
    for (int index = 0; index < Scope.DEFAULT_MAX_BREADCRUMBS + 1; index++) {
      scope.addBreadcrumb("ui.click", "tap-" + index);
    }
    MonicaEvent event = new MonicaEvent();
    scope.applyTo(event);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breadcrumbs = (List<Map<String, Object>>) event.get("breadcrumbs");
    assertEquals(100, breadcrumbs.size());
    assertEquals("tap-1", breadcrumbs.get(0).get("message"));
    assertEquals("tap-" + Scope.DEFAULT_MAX_BREADCRUMBS, breadcrumbs.get(99).get("message"));
  }

  @Test
  void survivesBreadcrumbsWrittenWhileAnotherThreadAppliesIt() throws Exception {
    // A mobile main thread stamps lifecycle breadcrumbs while a crash is captured
    // elsewhere. Without synchronisation the trim in addBreadcrumb races the copy in
    // applyTo: ArrayIndexOutOfBounds on one side, ConcurrentModification on the other.
    Scope scope = new Scope(4);
    java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread writer = new Thread(() -> {
      try {
        for (int index = 0; index < 50_000; index++) scope.addBreadcrumb("ui", "b" + index);
      } catch (Throwable thrown) {
        failure.set(thrown);
      }
    });
    writer.start();
    for (int index = 0; index < 5_000; index++) {
      MonicaEvent event = new MonicaEvent();
      scope.applyTo(event);
      Object breadcrumbs = event.get("breadcrumbs");
      if (breadcrumbs != null) assertTrue(((List<?>) breadcrumbs).size() <= 4);
      scope.copy();
    }
    writer.join(20_000);
    assertNull(failure.get());
  }
}
