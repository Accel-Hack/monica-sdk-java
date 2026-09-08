package com.accelhack.monica;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CaptureContext {
  private String level = "error";
  private String message;
  private boolean handled = true;
  private final Map<String, String> tags = new LinkedHashMap<>();
  private final Map<String, Object> contexts = new LinkedHashMap<>();

  public static CaptureContext create() {
    return new CaptureContext();
  }

  public CaptureContext level(String level) {
    this.level = level;
    return this;
  }

  public CaptureContext message(String message) {
    this.message = message;
    return this;
  }

  public CaptureContext handled(boolean handled) {
    this.handled = handled;
    return this;
  }

  public CaptureContext tag(String key, String value) {
    if (key != null && value != null) tags.put(key, value);
    return this;
  }

  public CaptureContext context(String key, Object value) {
    if (key != null && value != null) contexts.put(key, value);
    return this;
  }

  String level() {
    return level;
  }

  String message() {
    return message;
  }

  boolean handled() {
    return handled;
  }

  Map<String, String> tags() {
    return tags;
  }

  Map<String, Object> contexts() {
    return contexts;
  }
}
