package com.accelhack.monica;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tags, contexts, breadcrumbs and the user that every event captured through it carries.
 *
 * <p>Every method is {@code synchronized}. Server integrations mostly touch a per-request
 * scope from one thread, but a mobile application writes breadcrumbs from its main thread
 * while a crash is captured on another; a plain {@code ArrayList} being trimmed by one
 * and copied by the other throws in whichever thread loses. Scope operations happen at
 * event frequency, so the lock is never contended in a way that matters. Application
 * code ({@code beforeSend}) is never called while the lock is held.
 */
public final class Scope {
  static final int DEFAULT_MAX_BREADCRUMBS = 100;

  private final Map<String, String> tags = new LinkedHashMap<>();
  private final Map<String, Object> contexts = new LinkedHashMap<>();
  private final List<Map<String, Object>> breadcrumbs = new ArrayList<>();
  private final int maxBreadcrumbs;
  private Map<String, Object> user;

  public Scope() {
    this(DEFAULT_MAX_BREADCRUMBS);
  }

  Scope(int maxBreadcrumbs) {
    if (maxBreadcrumbs <= 0) throw new IllegalArgumentException("maxBreadcrumbs must be positive");
    this.maxBreadcrumbs = maxBreadcrumbs;
  }

  public synchronized Scope setTag(String key, String value) {
    if (key != null && value != null) tags.put(key, value);
    return this;
  }

  /**
   * Identifies the person the events belong to. Nothing about them is sent until an
   * application calls this, and {@code beforeSend} still gets the last word.
   */
  public synchronized Scope setUser(Map<String, Object> user) {
    this.user = user == null || user.isEmpty() ? null : new LinkedHashMap<>(user);
    return this;
  }

  public synchronized Scope setContext(String key, Object value) {
    if (key != null && value != null) contexts.put(key, value);
    return this;
  }

  public synchronized Scope addBreadcrumb(String category, String message) {
    Map<String, Object> breadcrumb = new LinkedHashMap<>();
    breadcrumb.put("timestamp", Instant.now().toString());
    breadcrumb.put("category", category);
    breadcrumb.put("message", message);
    breadcrumbs.add(breadcrumb);
    // A long-lived process must not grow its scope forever; drop the oldest.
    while (breadcrumbs.size() > maxBreadcrumbs) breadcrumbs.remove(0);
    return this;
  }

  synchronized void applyTo(MonicaEvent event) {
    Map<String, String> mergedTags = new LinkedHashMap<>(tags);
    Object eventTags = event.get("tags");
    if (eventTags instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, String> typed = (Map<String, String>) eventTags;
      mergedTags.putAll(typed);
    }
    if (!mergedTags.isEmpty()) event.put("tags", mergedTags);

    Map<String, Object> mergedContexts = new LinkedHashMap<>(contexts);
    Object eventContexts = event.get("contexts");
    if (eventContexts instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) eventContexts;
      mergedContexts.putAll(typed);
    }
    if (!mergedContexts.isEmpty()) event.put("contexts", mergedContexts);
    if (!breadcrumbs.isEmpty() && event.get("breadcrumbs") == null) {
      event.put("breadcrumbs", new ArrayList<>(breadcrumbs));
    }
    if (user != null && event.get("user") == null) {
      event.put("user", new LinkedHashMap<>(user));
    }
  }

  synchronized Scope copy() {
    // The copy is not shared with anyone yet, so only this scope needs the lock.
    Scope copy = new Scope(maxBreadcrumbs);
    copy.tags.putAll(tags);
    copy.contexts.putAll(contexts);
    copy.breadcrumbs.addAll(breadcrumbs);
    copy.user = user == null ? null : new LinkedHashMap<>(user);
    return copy;
  }
}
