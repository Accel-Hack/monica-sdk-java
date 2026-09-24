package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * Closes the {@link MonicaClient} bean when it is destroyed.
 *
 * <p>Attached to the client bean itself rather than to a separate bean, so the client is closed
 * even when startup fails before any other MONICA bean exists. While a SpringApplication is
 * still starting, the close is handed to {@link MonicaStartupFailureReporter}, which reports the
 * failure first: Spring destroys the beans before it tells run listeners what went wrong.
 */
public final class MonicaLifecycle implements DestructionAwareBeanPostProcessor, BeanFactoryAware,
    PriorityOrdered {
  private BeanFactory beanFactory;

  MonicaLifecycle() {
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  // Registered first, so a client created while other post-processors are being set up is
  // still covered.
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public boolean requiresDestruction(Object bean) {
    return bean instanceof MonicaClient;
  }

  @Override
  public void postProcessBeforeDestruction(Object bean, String beanName) {
    if (!(bean instanceof MonicaClient)) return;
    MonicaClient client = (MonicaClient) bean;
    if (!MonicaStartupFailureReporter.deferClose(beanFactory, client)) client.close();
  }
}
