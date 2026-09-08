package com.accelhack.monica.spring.boot2;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

final class MonicaRequestContextInterceptor implements HandlerInterceptor {
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    Object contextValue = request.getAttribute(MonicaRequestScopeFilter.REQUEST_CONTEXT_ATTRIBUTE);
    Object routeValue = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (contextValue instanceof Map && routeValue != null) {
      String route = String.valueOf(routeValue).trim();
      if (!route.isEmpty()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestContext = (Map<String, Object>) contextValue;
        requestContext.put("route", route);
      }
    }
    return true;
  }
}
