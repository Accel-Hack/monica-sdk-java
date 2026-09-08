package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public final class MonicaRequestScopeFilter extends OncePerRequestFilter {
  static final String REQUEST_CONTEXT_ATTRIBUTE = MonicaRequestScopeFilter.class.getName()
      + ".requestContext";
  private final MonicaClient client;

  MonicaRequestScopeFilter(MonicaClient client) {
    this.client = client;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    try (MonicaClient.ScopeHandle scope = client.pushScope()) {
      Map<String, Object> requestContext = new LinkedHashMap<>();
      requestContext.put("method", request.getMethod());
      scope.scope().setContext("request", requestContext);
      request.setAttribute(REQUEST_CONTEXT_ATTRIBUTE, requestContext);
      try {
        filterChain.doFilter(request, response);
      } finally {
        request.removeAttribute(REQUEST_CONTEXT_ATTRIBUTE);
      }
    }
  }
}
