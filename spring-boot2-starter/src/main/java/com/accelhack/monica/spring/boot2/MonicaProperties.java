package com.accelhack.monica.spring.boot2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("monica")
public class MonicaProperties {
  private boolean enabled = true;
  private String dsn;
  private String environment = "production";
  private String release;
  private String serverName;
  private List<String> inAppPackages = new ArrayList<>();
  private Duration flushTimeout = Duration.ofSeconds(2);
  private Duration flushInterval = Duration.ofSeconds(5);
  private int maxQueueSize = 100;
  private int batchSize = 30;
  private double sampleRate = 1;
  private final Logback logback = new Logback();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getDsn() { return dsn; }
  public void setDsn(String dsn) { this.dsn = dsn; }
  public String getEnvironment() { return environment; }
  public void setEnvironment(String environment) { this.environment = environment; }
  public String getRelease() { return release; }
  public void setRelease(String release) { this.release = release; }
  public String getServerName() { return serverName; }
  public void setServerName(String serverName) { this.serverName = serverName; }
  public List<String> getInAppPackages() { return inAppPackages; }
  public void setInAppPackages(List<String> inAppPackages) { this.inAppPackages = inAppPackages; }
  public Duration getFlushTimeout() { return flushTimeout; }
  public void setFlushTimeout(Duration flushTimeout) { this.flushTimeout = flushTimeout; }
  public Duration getFlushInterval() { return flushInterval; }
  public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
  public int getMaxQueueSize() { return maxQueueSize; }
  public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public double getSampleRate() { return sampleRate; }
  public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }
  public Logback getLogback() { return logback; }

  public static class Logback {
    private boolean enabled = true;
    private boolean captureMessages;
    private List<String> allowedMdcKeys = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isCaptureMessages() { return captureMessages; }
    public void setCaptureMessages(boolean captureMessages) { this.captureMessages = captureMessages; }
    public List<String> getAllowedMdcKeys() { return allowedMdcKeys; }
    public void setAllowedMdcKeys(List<String> allowedMdcKeys) { this.allowedMdcKeys = allowedMdcKeys; }
  }
}
