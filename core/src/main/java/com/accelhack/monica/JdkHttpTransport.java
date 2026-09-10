package com.accelhack.monica;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
  /**
   * How much of a response body is read before it is given up on. A rejection carries a handful
   * of {@code issues}; anything past this is not the {@code error.json} this transport knows how
   * to read, and reading it would let an unhealthy endpoint decide how much memory the host
   * process spends on a request MONICA has already lost.
   */
  static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
  /** The name the default diagnostic sink logs under, so an application can filter on it. */
  static final String LOGGER_NAME = "com.accelhack.monica";

  private final HttpClient client;
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final String key;
  private final int maxRetries;
  private final Duration requestTimeout;
  private final MonicaDiagnostic diagnostic;
  /**
   * {@code transport.json} answers {@code drop_and_stop} for {@code 401}: the key is wrong or
   * revoked, so every later envelope would be rejected the same way. Written once, read on every
   * send from whichever thread drains the queue.
   */
  private volatile boolean stopped;

  public JdkHttpTransport(String dsn, int maxRetries, Duration requestTimeout) {
    this(dsn, maxRetries, requestTimeout, null);
  }

  /** @param diagnostic where a rejection is reported; {@code null} uses {@code System.Logger}. */
  public JdkHttpTransport(String dsn, int maxRetries, Duration requestTimeout,
      MonicaDiagnostic diagnostic) {
    ParsedDsn parsed = parseDsn(dsn);
    this.client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    this.mapper = new ObjectMapper();
    this.endpoint = parsed.endpoint;
    this.key = parsed.key;
    this.maxRetries = maxRetries;
    this.requestTimeout = requestTimeout;
    this.diagnostic = diagnostic != null ? diagnostic : SystemLoggerDiagnostic.INSTANCE;
  }

  @Override
  public boolean send(MonicaEnvelope envelope) throws Exception {
    return deliver(envelope).isAccepted();
  }

  @Override
  public SendResult deliver(MonicaEnvelope envelope) throws Exception {
    if (stopped) return SendResult.rejected(401, null, null, null, true);
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
        HttpResponse<InputStream> response = client.send(request,
            HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        // Read before branching: the stream has to be closed either way, and a rejection's
        // reason is only in the body.
        byte[] payload = readCapped(response.body());
        if (status >= 200 && status < 300) return SendResult.of(true, status);
        if (status != 429 && status < 500) return rejected(status, payload);
        if (attempt == maxRetries) return SendResult.of(false, status);
        sleep(retryDelay(response, attempt));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return SendResult.of(false);
      } catch (Exception failure) {
        if (attempt == maxRetries) return SendResult.of(false);
        sleep(backoff(attempt));
      }
    }
    return SendResult.of(false);
  }

  /** True once a {@code 401} has closed this transport; it no longer posts anything. */
  public boolean isStopped() {
    return stopped;
  }

  /**
   * A 4xx other than 429 is dropped, never retried. What changes here is only that the reason
   * is read and reported: the envelope is lost exactly as before.
   */
  private SendResult rejected(int status, byte[] payload) {
    ErrorBody error = ErrorBody.parse(mapper, payload);
    boolean stop = status == 401;
    if (stop) stopped = true;
    if (status == 422) {
      warn(rejectionMessage(status, error.code, false) + issueSummary(error.issues));
    } else if (stop) {
      // Going quiet for the rest of the process's life is worth one line: nothing else would
      // tell the application that MONICA has stopped accepting its events.
      warn(rejectionMessage(status, error.code, true) + "; no further envelopes will be sent");
    }
    return SendResult.rejected(status, error.code, error.message, error.issues, stop);
  }

  /**
   * The wording is fixed across every MONICA SDK, so an operator who has read one of them can
   * read them all. A 401 always names a code, {@code unknown} when the body did not carry one.
   */
  private static String rejectionMessage(int status, String code, boolean codeAlwaysShown) {
    StringBuilder text = new StringBuilder("monica: ingest rejected the envelope with ")
        .append(status);
    if (code != null) text.append(" (").append(code).append(')');
    else if (codeAlwaysShown) text.append(" (unknown)");
    return text.toString();
  }

  private static String issueSummary(List<SendResult.Issue> issues) {
    StringBuilder text = new StringBuilder(": ").append(issues.size()).append(" issue(s)");
    for (SendResult.Issue issue : issues) {
      text.append("; ").append(issue.getPath()).append(": ").append(issue.getMessage());
    }
    return text.toString();
  }

  private void warn(String message) {
    try {
      diagnostic.warn(message);
    } catch (Throwable ignored) {
      // A broken logger must not turn a dropped envelope into a failure of the host process.
    }
  }

  /**
   * Reads at most {@link #MAX_ERROR_BODY_BYTES} and answers empty for anything larger, so a
   * truncated body is never mistaken for a well-formed one. The stream is always closed.
   */
  private static byte[] readCapped(InputStream stream) {
    if (stream == null) return new byte[0];
    try (InputStream in = stream) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] chunk = new byte[8_192];
      int read;
      while (bytes.size() <= MAX_ERROR_BODY_BYTES && (read = in.read(chunk)) >= 0) {
        bytes.write(chunk, 0, read);
      }
      return bytes.size() > MAX_ERROR_BODY_BYTES ? new byte[0] : bytes.toByteArray();
    } catch (Exception ignored) {
      return new byte[0];
    }
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

  /**
   * {@code error.json}, as much of it as a response actually carried. Anything that is not the
   * documented shape becomes {@link #EMPTY}: the envelope is dropped either way, so a malformed
   * rejection body must never be the thing that throws.
   */
  private static final class ErrorBody {
    static final ErrorBody EMPTY = new ErrorBody(null, null, Collections.emptyList());

    final String code;
    final String message;
    final List<SendResult.Issue> issues;

    private ErrorBody(String code, String message, List<SendResult.Issue> issues) {
      this.code = code;
      this.message = message;
      this.issues = issues;
    }

    static ErrorBody parse(ObjectMapper mapper, byte[] payload) {
      if (payload == null || payload.length == 0) return EMPTY;
      try {
        JsonNode error = mapper.readTree(payload).path("error");
        if (!error.isObject()) return EMPTY;
        List<SendResult.Issue> issues = new ArrayList<>();
        for (JsonNode issue : error.path("issues")) {
          JsonNode path = issue.path("path");
          JsonNode message = issue.path("message");
          // error.json requires both, as strings. A half-filled issue tells nobody which
          // field to fix, so it is dropped rather than reported as "null".
          if (path.isTextual() && message.isTextual()) {
            issues.add(new SendResult.Issue(path.asText(), message.asText()));
          }
        }
        return new ErrorBody(text(error.path("code")), text(error.path("message")), issues);
      } catch (Throwable ignored) {
        return EMPTY;
      }
    }

    private static String text(JsonNode node) {
      return node.isTextual() ? node.asText() : null;
    }
  }

  /**
   * The default sink: {@code System.Logger}, so an application gets the warning through
   * whatever logging framework its JDK is wired to without this SDK depending on one.
   *
   * <p>{@code System.getLogger} is JDK 9 and does not exist on Android. It lives in this class
   * for that reason: monica-android supplies its own transport, so nothing on Android ever loads
   * it, exactly as with {@code java.net.http} above (see the animal-sniffer note in core/pom.xml).
   */
  private static final class SystemLoggerDiagnostic implements MonicaDiagnostic {
    static final MonicaDiagnostic INSTANCE = new SystemLoggerDiagnostic();

    @Override
    public void warn(String message) {
      System.getLogger(LOGGER_NAME).log(System.Logger.Level.WARNING, message);
    }
  }
}
