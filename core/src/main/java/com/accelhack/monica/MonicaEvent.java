package com.accelhack.monica;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MonicaEvent {
  private final Map<String, Object> values;

  public MonicaEvent() {
    this.values = new LinkedHashMap<>();
  }

  public MonicaEvent(Map<String, Object> values) {
    this.values = new LinkedHashMap<>(Objects.requireNonNull(values, "values"));
  }

  public MonicaEvent put(String key, Object value) {
    if (value == null) values.remove(key);
    else values.put(key, value);
    return this;
  }

  public MonicaEvent remove(String key) {
    values.remove(key);
    return this;
  }

  public Object get(String key) {
    return values.get(key);
  }

  @JsonValue
  public Map<String, Object> values() {
    return Collections.unmodifiableMap(values);
  }
}
