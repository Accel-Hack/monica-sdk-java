package com.accelhack.monica;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MonicaClient implements AutoCloseable {
  private static final Set<String> LEVELS = new HashSet<>(
      Arrays.asList("fatal", "error", "warning", "info", "debug"));
  private static final long DEDUPLICATION_WINDOW_MILLIS = 1_000;
  private static final int MAX_SEEN_THROWABLES = 256;
  // The ingest endpoint allows 1 MiB after gzip. A JSON preflight below one
  // million bytes also protects the decompressed limit and leaves framing room.
  static final int MAX_SAFE_ENVELOPE_JSON_BYTES = 1_000_000;
  private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();

  private final MonicaOptions options;
  private final Object lock = new Object();
  private final Deque<MonicaEvent> queue = new ArrayDeque<>();
  private final Deque<SeenThrowable> seenThrowables = new ArrayDeque<>();
  private final ScheduledExecutorService sender;
  private final Scope globalScope;
  private final ThreadLocal<Deque<Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);
  private long discarded;
  private volatile boolean closed;

  private MonicaClient(MonicaOptions options) {
    this.options = options;
    this.globalScope = new Scope(options.maxBreadcrumbs);
    this.sender = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    sender.scheduleWithFixedDelay(this::drainBestEffort, options.flushInterval.toMillis(),
        options.flushInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String captureException(Throwable throwable) {
    return captureException(throwable, CaptureContext.create());
  }

  public String captureException(Throwable throwable, CaptureContext context) {
    if (throwable == null || closed) return null;
    try {
      CaptureContext safeContext = context == null ? CaptureContext.create() : context;
      MonicaEvent event = baseEvent(safeContext.level());
      event.put("exception", ThrowableConverter.convert(throwable, options.inAppPackages,
          safeContext.handled()));
      String message = safeContext.message() == null ? throwable.getMessage() : safeContext.message();
      if (message != null) event.put("message", message);
      applyCaptureContext(event, safeContext);
      return prepareAndEnqueue(event, CaptureHint.forException(throwable), throwable);
    } catch (Throwable ignored) {
      return null;
    }
  }

  public String captureMessage(String message) {
    return captureMessage(message, "error");
  }

  public String captureMessage(String message, String level) {
    return captureMessage(message, CaptureContext.create().level(level));
  }

  public String captureMessage(String message, CaptureContext context) {
    if (message == null || closed) return null;
    try {
      CaptureContext safeContext = context == null ? CaptureContext.create() : context;
      MonicaEvent event = baseEvent(safeContext.level());
      event.put("message", message);
      applyCaptureContext(event, safeContext);
      return prepareAndEnqueue(event, CaptureHint.empty(), null);
    } catch (Throwable ignored) {
      return null;
    }
  }

  public Scope globalScope() {
    return globalScope;
  }

  public ScopeHandle pushScope() {
    Deque<Scope> local = scopes.get();
    Scope parent = local.peekLast();
    Scope scope = parent == null ? globalScope.copy() : parent.copy();
    local.addLast(scope);
    return new ScopeHandle(this, scope);
  }

  public boolean flush() {
    return flush(options.flushTimeout);
  }

  public boolean flush(Duration timeout) {
    if (sender.isShutdown()) return stats().getQueued() == 0;
    Duration safeTimeout = timeout == null ? options.flushTimeout : timeout;
    if (safeTimeout.isNegative()) return false;
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    try {
      sender.execute(() -> {
        boolean accepted = false;
        try {
          accepted = true;
          while (queued() > 0) accepted = drainOnce() && accepted;
        } finally {
          // An Error out of drainOnce must not leave the caller waiting out the whole
          // timeout; on a crashing thread that delays the process's death for nothing.
          result.complete(accepted);
        }
      });
      return result.get(safeTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      return false;
    }
  }

  public MonicaStats stats() {
    synchronized (lock) {
      return new MonicaStats(queue.size(), discarded);
    }
  }

  @Override
  public void close() {
    close(options.flushTimeout);
  }

  public boolean close(Duration timeout) {
    if (closed) return stats().getQueued() == 0;
    closed = true;
    boolean accepted = flush(timeout);
    sender.shutdownNow();
    scopes.remove();
    return accepted;
  }

  private MonicaEvent baseEvent(String requestedLevel) {
    String level = LEVELS.contains(requestedLevel) ? requestedLevel : "error";
    MonicaEvent event = new MonicaEvent()
        .put("type", "error")
        .put("event_id", UUID.randomUUID().toString())
        .put("timestamp", options.clock.get().toString())
        .put("level", level)
        .put("platform", "java")
        .put("environment", options.environment);
    if (options.release != null) event.put("release", options.release);
    if (options.serverName != null) event.put("server_name", options.serverName);
    return event;
  }

  private void applyCaptureContext(MonicaEvent event, CaptureContext context) {
    if (!context.tags().isEmpty()) event.put("tags", new LinkedHashMap<>(context.tags()));
    if (!context.contexts().isEmpty()) event.put("contexts", new LinkedHashMap<>(context.contexts()));
  }

  private String prepareAndEnqueue(MonicaEvent original, CaptureHint hint,
      Throwable deduplicationKey) throws Exception {
    if (options.random.get() >= options.sampleRate) return null;
    Scope active = scopes.get().peekLast();
    (active == null ? globalScope : active).applyTo(original);
    MonicaEvent event = options.beforeSend == null ? original : options.beforeSend.process(original, hint);
    if (event == null) return null;
    String eventId = String.valueOf(event.get("event_id"));
    boolean sendNow;
    synchronized (lock) {
      if (closed) return null;
      if (deduplicationKey != null && isDuplicateLocked(deduplicationKey)) return null;
      if (queue.size() >= options.maxQueueSize) {
        queue.removeFirst();
        discarded++;
      }
      queue.addLast(event);
      sendNow = "fatal".equals(event.get("level")) || queue.size() >= options.batchSize;
    }
    if (sendNow) sender.execute(this::drainBestEffort);
    return eventId;
  }

  private void drainBestEffort() {
    try {
      drainOnce();
    } catch (Throwable ignored) {
      // MONICA must never fail the host process or its scheduling thread.
    }
  }

  private boolean drainOnce() {
    List<MonicaEvent> batch = new ArrayList<>();
    long pendingDiscarded;
    synchronized (lock) {
      while (!queue.isEmpty() && batch.size() < options.batchSize) batch.add(queue.removeFirst());
      if (batch.isEmpty()) return true;
      pendingDiscarded = discarded;
      discarded = 0;
    }
    boolean oversizedDrop = false;
    while (!batch.isEmpty()) {
      String sentAt = options.clock.get().toString();
      int count = fittingItemCount(batch, sentAt, pendingDiscarded);
      if (count == 0) {
        batch.remove(0);
        pendingDiscarded++;
        oversizedDrop = true;
        continue;
      }
      List<MonicaEvent> items = new ArrayList<>(batch.subList(0, count));
      MonicaEnvelope envelope = new MonicaEnvelope(options.sdkName, options.sdkVersion,
          sentAt, pendingDiscarded, items);
      boolean accepted;
      try {
        accepted = options.transport.send(envelope);
      } catch (Throwable ignored) {
        accepted = false;
      }
      if (!accepted) {
        synchronized (lock) {
          discarded += pendingDiscarded + batch.size();
        }
        return false;
      }
      batch.subList(0, count).clear();
      pendingDiscarded = 0;
    }
    if (pendingDiscarded > 0) {
      synchronized (lock) {
        discarded += pendingDiscarded;
      }
    }
    return !oversizedDrop;
  }

  private int fittingItemCount(List<MonicaEvent> items, String sentAt, long pendingDiscarded) {
    if (!fitsEnvelope(items.subList(0, 1), sentAt, pendingDiscarded)) return 0;
    for (int count = items.size(); count > 1; count--) {
      if (fitsEnvelope(items.subList(0, count), sentAt, pendingDiscarded)) return count;
    }
    return 1;
  }

  private boolean fitsEnvelope(List<MonicaEvent> items, String sentAt, long pendingDiscarded) {
    MonicaEnvelope candidate = new MonicaEnvelope(options.sdkName, options.sdkVersion, sentAt,
        pendingDiscarded, items);
    try {
      return ENVELOPE_MAPPER.writeValueAsBytes(candidate).length
          <= MAX_SAFE_ENVELOPE_JSON_BYTES;
    } catch (Throwable ignored) {
      // Non-serializable application context is treated like an oversized
      // event so it cannot poison otherwise valid events in the same batch.
      return false;
    }
  }

  private int queued() {
    synchronized (lock) {
      return queue.size();
    }
  }

  private boolean isDuplicateLocked(Throwable throwable) {
    long now = System.currentTimeMillis();
    for (Iterator<SeenThrowable> iterator = seenThrowables.iterator(); iterator.hasNext();) {
      SeenThrowable seen = iterator.next();
      Throwable candidate = seen.throwable.get();
      if (candidate == null || now - seen.seenAt > DEDUPLICATION_WINDOW_MILLIS) {
        iterator.remove();
      } else if (candidate == throwable) {
        return true;
      }
    }
    seenThrowables.addLast(new SeenThrowable(throwable, now));
    while (seenThrowables.size() > MAX_SEEN_THROWABLES) seenThrowables.removeFirst();
    return false;
  }

  private static ThreadFactory daemonThreadFactory() {
    return task -> {
      Thread thread = new Thread(task, "monica-java-sender");
      thread.setDaemon(true);
      return thread;
    };
  }

  private void closeScope(Scope expected) {
    Deque<Scope> local = scopes.get();
    if (local.peekLast() == expected) local.removeLast();
    else local.remove(expected);
    if (local.isEmpty()) scopes.remove();
  }

  private static final class SeenThrowable {
    private final WeakReference<Throwable> throwable;
    private final long seenAt;

    private SeenThrowable(Throwable throwable, long seenAt) {
      this.throwable = new WeakReference<>(throwable);
      this.seenAt = seenAt;
    }
  }

  public static final class ScopeHandle implements AutoCloseable {
    private final MonicaClient client;
    private final Scope scope;
    private boolean closed;

    private ScopeHandle(MonicaClient client, Scope scope) {
      this.client = client;
      this.scope = scope;
    }

    public Scope scope() {
      return scope;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        client.closeScope(scope);
      }
    }
  }

  public static final class Builder {
    private final MonicaOptions.Builder options = MonicaOptions.builder();

    public Builder dsn(String value) { options.dsn(value); return this; }
    public Builder environment(String value) { options.environment(value); return this; }
    public Builder release(String value) { options.release(value); return this; }
    public Builder serverName(String value) { options.serverName(value); return this; }
    public Builder inAppPackage(String value) { options.inAppPackage(value); return this; }
    public Builder inAppPackages(Iterable<String> value) { options.inAppPackages(value); return this; }
    public Builder beforeSend(BeforeSend value) { options.beforeSend(value); return this; }
    public Builder maxQueueSize(int value) { options.maxQueueSize(value); return this; }
    public Builder maxBreadcrumbs(int value) { options.maxBreadcrumbs(value); return this; }
    public Builder sdk(String name, String version) { options.sdk(name, version); return this; }
    public Builder batchSize(int value) { options.batchSize(value); return this; }
    public Builder flushInterval(Duration value) { options.flushInterval(value); return this; }
    public Builder flushTimeout(Duration value) { options.flushTimeout(value); return this; }
    public Builder sampleRate(double value) { options.sampleRate(value); return this; }
    public Builder transport(MonicaTransport value) { options.transport(value); return this; }
    public Builder maxRetries(int value) { options.maxRetries(value); return this; }
    public Builder requestTimeout(Duration value) { options.requestTimeout(value); return this; }

    public MonicaClient build() {
      return new MonicaClient(options.build());
    }
  }
}
