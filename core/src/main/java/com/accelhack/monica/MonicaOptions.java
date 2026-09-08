package com.accelhack.monica;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

public final class MonicaOptions {
  static final String DEFAULT_SDK_NAME = "com.accelhack.monica:monica-core";
  static final String DEFAULT_SDK_VERSION = "0.1.1";
  /** The bound the event schema puts on {@code environment}; integrations validate against it. */
  public static final int MAX_ENVIRONMENT_LENGTH = 128;
  /** The item limit ingest puts on one envelope; a larger batch is split before it goes out. */
  static final int MAX_ITEMS_PER_ENVELOPE = 100;
  static final int DEFAULT_MAX_QUEUE_SIZE = 100;
  static final int DEFAULT_BATCH_SIZE = 30;
  static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(5);
  static final Duration DEFAULT_FLUSH_TIMEOUT = Duration.ofSeconds(2);

  final String dsn;
  final String sdkName;
  final String sdkVersion;
  final String environment;
  final String release;
  final String serverName;
  final List<String> inAppPackages;
  final BeforeSend beforeSend;
  final int maxQueueSize;
  final int maxBreadcrumbs;
  final int batchSize;
  final Duration flushInterval;
  final Duration flushTimeout;
  final double sampleRate;
  final MonicaTransport transport;
  final Supplier<Instant> clock;
  final Supplier<Double> random;

  private MonicaOptions(Builder builder) {
    dsn = builder.dsn;
    sdkName = requireText(builder.sdkName, "sdkName");
    sdkVersion = requireText(builder.sdkVersion, "sdkVersion");
    environment = requireBoundedText(builder.environment, "environment", MAX_ENVIRONMENT_LENGTH);
    release = builder.release;
    serverName = builder.serverName;
    inAppPackages = Collections.unmodifiableList(new ArrayList<>(builder.inAppPackages));
    beforeSend = builder.beforeSend;
    maxQueueSize = positive(builder.maxQueueSize, "maxQueueSize");
    maxBreadcrumbs = positive(builder.maxBreadcrumbs, "maxBreadcrumbs");
    batchSize = Math.min(positive(builder.batchSize, "batchSize"),
        Math.min(maxQueueSize, MAX_ITEMS_PER_ENVELOPE));
    flushInterval = positive(builder.flushInterval, "flushInterval");
    flushTimeout = positive(builder.flushTimeout, "flushTimeout");
    if (!Double.isFinite(builder.sampleRate) || builder.sampleRate < 0 || builder.sampleRate > 1) {
      throw new IllegalArgumentException("sampleRate must be between 0 and 1");
    }
    sampleRate = builder.sampleRate;
    transport = builder.transport != null ? builder.transport : new JdkHttpTransport(
        requireText(builder.dsn, "dsn"), builder.maxRetries, builder.requestTimeout);
    clock = Objects.requireNonNull(builder.clock, "clock");
    random = Objects.requireNonNull(builder.random, "random");
  }

  public static Builder builder() {
    return new Builder();
  }

  private static int positive(int value, String name) {
    if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static Duration positive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    return value;
  }

  private static String requireBoundedText(String value, String name, int maxLength) {
    String required = requireText(value, name);
    if (required.length() > maxLength) {
      throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
    }
    return required;
  }

  public static final class Builder {
    private String dsn;
    private String sdkName = DEFAULT_SDK_NAME;
    private String sdkVersion = DEFAULT_SDK_VERSION;
    private String environment;
    private String release;
    private String serverName;
    private final List<String> inAppPackages = new ArrayList<>();
    private BeforeSend beforeSend;
    private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
    private int maxBreadcrumbs = Scope.DEFAULT_MAX_BREADCRUMBS;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private Duration flushInterval = DEFAULT_FLUSH_INTERVAL;
    private Duration flushTimeout = DEFAULT_FLUSH_TIMEOUT;
    private double sampleRate = 1;
    private MonicaTransport transport;
    private Supplier<Instant> clock = Instant::now;
    private Supplier<Double> random = new Random()::nextDouble;
    private int maxRetries = 5;
    private Duration requestTimeout = Duration.ofSeconds(2);

    public Builder dsn(String dsn) {
      this.dsn = dsn;
      return this;
    }

    /** Identifies the integration that produced the envelope, not just the core library. */
    public Builder sdk(String name, String version) {
      this.sdkName = name;
      this.sdkVersion = version;
      return this;
    }

    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    public Builder release(String release) {
      this.release = release;
      return this;
    }

    public Builder serverName(String serverName) {
      this.serverName = serverName;
      return this;
    }

    public Builder inAppPackage(String packagePrefix) {
      if (packagePrefix != null && !packagePrefix.trim().isEmpty()) {
        inAppPackages.add(packagePrefix.trim());
      }
      return this;
    }

    public Builder inAppPackages(Iterable<String> packagePrefixes) {
      if (packagePrefixes != null) packagePrefixes.forEach(this::inAppPackage);
      return this;
    }

    public Builder beforeSend(BeforeSend beforeSend) {
      this.beforeSend = beforeSend;
      return this;
    }

    public Builder maxQueueSize(int maxQueueSize) {
      this.maxQueueSize = maxQueueSize;
      return this;
    }

    public Builder maxBreadcrumbs(int maxBreadcrumbs) {
      this.maxBreadcrumbs = maxBreadcrumbs;
      return this;
    }

    public Builder batchSize(int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Builder flushInterval(Duration flushInterval) {
      this.flushInterval = flushInterval;
      return this;
    }

    public Builder flushTimeout(Duration flushTimeout) {
      this.flushTimeout = flushTimeout;
      return this;
    }

    public Builder sampleRate(double sampleRate) {
      this.sampleRate = sampleRate;
      return this;
    }

    public Builder transport(MonicaTransport transport) {
      this.transport = transport;
      return this;
    }

    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must not be negative");
      this.maxRetries = maxRetries;
      return this;
    }

    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    Builder clock(Supplier<Instant> clock) {
      this.clock = clock;
      return this;
    }

    Builder random(Supplier<Double> random) {
      this.random = random;
      return this;
    }

    public MonicaOptions build() {
      return new MonicaOptions(this);
    }
  }
}
