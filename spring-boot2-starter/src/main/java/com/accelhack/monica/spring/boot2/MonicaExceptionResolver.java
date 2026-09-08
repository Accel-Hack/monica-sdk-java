package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.CaptureContext;
import com.accelhack.monica.MonicaClient;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

public final class MonicaExceptionResolver implements HandlerExceptionResolver, Ordered {
  private final MonicaClient client;

  MonicaExceptionResolver(MonicaClient client) {
    this.client = client;
  }

  @Override
  public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception exception) {
    client.captureException(exception, CaptureContext.create()
        .handled(false)
        .tag("integration", "spring_mvc"));
    return null;
  }

  @Override
  public int getOrder() {
    // Application resolvers and Spring's standard composite get the first
    // opportunity to handle an exception. Only exceptions still unresolved
    // reach MONICA, matching the starter's unhandled-exception contract.
    return Ordered.LOWEST_PRECEDENCE;
  }
}
