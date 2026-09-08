package com.accelhack.monica;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPOutputStream;

public final class JdkHttpTransport implements MonicaTransport {
  /**
   * The retry policy the public contract publishes in {@code transport.json}. Kept as named
   * constants so the contract test can compare them with the vendored bundle instead of
   * re-reading them out of the prose.
   */
  static final String INGEST_PATH = "/v1/envelope";
  static final String SECRET_KEY_PREFIX = "msk_";
  static final long RETRY_AFTER_MAX_SECONDS = 60;
  static final long BACKOFF_BASE_MILLIS = 1_000L;
  static final int BACKOFF_FACTOR = 2;
  static final long BACKOFF_MAX_MILLIS = 30_000L;
  static final double BACKOFF_JITTER_MIN = 0.5;
  static final double BACKOFF_JITTER_MAX = 1.0;

  private final HttpClient client;
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final String key;
  private final int maxRetries;
  private final Duration requestTimeout;

  public JdkHttpTransport(String dsn, int maxRetries, Duration requestTimeout) {
    ParsedDsn parsed = parseDsn(dsn);
    this.client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    this.mapper = new ObjectMapper();
    this.endpoint = parsed.endpoint;
    this.key = parsed.key;
    this.maxRetries = maxRetries;
    this.requestTimeout = requestTimeout;
  }

  @Override
  public boolean send(MonicaEnvelope envelope) throws Exception {
    byte[] body = gzip(mapper.writeValueAsBytes(envelope));
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status >= 200 && status < 300) return true;
        if (status != 429 && status < 500) return false;
        if (attempt == maxRetries) return false;
        sleep(retryDelay(response, attempt));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception failure) {
        if (attempt == maxRetries) return false;
        sleep(backoff(attempt));
      }
    }
    return false;
  }

  private static Duration retryDelay(HttpResponse<?> response, int attempt) {
    if (response.statusCode() == 429) {
      String value = response.headers().firstValue("Retry-After").orElse("");
      try {
        long seconds = Long.parseLong(value);
        return Duration.ofSeconds(Math.min(Math.max(seconds, 0), RETRY_AFTER_MAX_SECONDS));
      } catch (NumberFormatException ignored) {
        // Fall through to jittered backoff.
      }
    }
    return backoff(attempt);
  }

  static Duration backoff(int attempt) {
    long ceiling = BACKOFF_BASE_MILLIS;
    for (int step = 0; step < attempt && ceiling < BACKOFF_MAX_MILLIS; step++) {
      ceiling *= BACKOFF_FACTOR;
    }
    ceiling = Math.min(ceiling, BACKOFF_MAX_MILLIS);
    long floor = (long) (ceiling * BACKOFF_JITTER_MIN);
    long cap = (long) (ceiling * BACKOFF_JITTER_MAX);
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(floor, cap + 1));
  }

  private static void sleep(Duration delay) throws InterruptedException {
    Thread.sleep(delay.toMillis());
  }

  private static byte[] gzip(byte[] value) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(value);
    }
    return bytes.toByteArray();
  }

  private static ParsedDsn parseDsn(String value) {
    try {
      URI uri = URI.create(value);
      String userInfo = uri.getRawUserInfo();
      String key = userInfo == null ? "" : java.net.URLDecoder.decode(
          userInfo.split(":", 2)[0], StandardCharsets.UTF_8.name());
      if (key.isEmpty()) throw new IllegalArgumentException("dsn must contain an API key");
      if (!key.startsWith(SECRET_KEY_PREFIX)) {
        throw new IllegalArgumentException("Java dsn must contain a secret msk_ key");
      }
      boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
      if (!"https".equals(uri.getScheme()) && !local) {
        throw new IllegalArgumentException("dsn must use https except for localhost");
      }
      URI endpoint = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
          INGEST_PATH, null, null);
      return new ParsedDsn(endpoint, key);
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("dsn must be a valid URL", failure);
    }
  }

  private static final class ParsedDsn {
    private final URI endpoint;
    private final String key;

    private ParsedDsn(URI endpoint, String key) {
      this.endpoint = endpoint;
      this.key = key;
    }
  }
}
