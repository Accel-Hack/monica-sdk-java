package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Reports a failed SpringApplication startup at level {@code fatal}.
 *
 * <p>When refresh fails, Spring destroys every bean before {@link #failed} runs, so
 * {@link MonicaLifecycle} leaves the client open and hands it here instead of closing it.
 */
final class MonicaStartupFailureReporter implements SpringApplicationRunListener {
  private static final String BEAN_NAME = "monicaStartupFailureReporter";

  // Set once nothing is left to report: after a successful refresh or after failed().
  private volatile boolean closeDirectly;
  private volatile MonicaClient pending;

  public MonicaStartupFailureReporter(SpringApplication application, String[] args) {
  }

  @Override
  public void contextPrepared(ConfigurableApplicationContext context) {
    try {
      context.getBeanFactory().registerSingleton(BEAN_NAME, this);
    } catch (Throwable ignored) {
      // Without it a failed startup is not reported; the client is still closed on destroy.
    }
  }

  @Override
  @SuppressWarnings("deprecation") // started(context, Duration) delegates here on Boot 2.6+.
  public void started(ConfigurableApplicationContext context) {
    closeDirectly = true;
  }

  @Override
  public void failed(ConfigurableApplicationContext context, Throwable exception) {
    MonicaClient client = pending;
    try {
      // Failure after refresh (e.g. an ApplicationRunner): the client is still a live bean and
      // is closed by the context close that SpringApplication performs next.
      if (client == null && context != null && context.isActive()) {
        client = context.getBeanProvider(MonicaClient.class).getIfUnique();
      }
      if (client != null) {
        client.captureException(exception, CaptureContext.create()
            .level("fatal")
            .handled(false)
            .tag("integration", "spring_boot_startup"));
      }
    } catch (Throwable ignored) {
      // Monitoring must not change how the application's startup failure is handled.
    } finally {
      closeDirectly = true;
      if (pending != null) pending.close();
    }
  }

  /** Returns true when the client is left open for {@link #failed} to report and close. */
  static boolean deferClose(BeanFactory beanFactory, MonicaClient client) {
    if (!(beanFactory instanceof SingletonBeanRegistry)) return false;
    Object candidate = ((SingletonBeanRegistry) beanFactory).getSingleton(BEAN_NAME);
    if (!(candidate instanceof MonicaStartupFailureReporter)) return false;
    MonicaStartupFailureReporter reporter = (MonicaStartupFailureReporter) candidate;
    if (reporter.closeDirectly) return false;
    reporter.pending = client;
    return true;
  }
}
