package com.accelhack.monica;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MonicaEnvelope {
  private final Map<String, String> sdk;
  private final String sentAt;
  private final long discarded;
  private final List<MonicaEvent> items;

  /** Public so a custom {@link MonicaTransport} can be exercised without a client. */
  public MonicaEnvelope(String sdkName, String sdkVersion, String sentAt, long discarded,
      List<MonicaEvent> items) {
    this.sdk = new LinkedHashMap<>();
    this.sdk.put("name", sdkName);
    this.sdk.put("version", sdkVersion);
    this.sentAt = sentAt;
    this.discarded = discarded;
    this.items = items;
  }

  public Map<String, String> getSdk() {
    return sdk;
  }

  @JsonProperty("sent_at")
  public String getSentAt() {
    return sentAt;
  }

  public long getDiscarded() {
    return discarded;
  }

  public List<MonicaEvent> getItems() {
    return items;
  }
}
