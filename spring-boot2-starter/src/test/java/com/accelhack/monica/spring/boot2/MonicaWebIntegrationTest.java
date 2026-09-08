package com.accelhack.monica.spring.boot2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerMapping;

class MonicaWebIntegrationTest {
  @Test
  void capturesMvcExceptionBeforeResponseResolution() {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = client(sent);
    MonicaExceptionResolver resolver = new MonicaExceptionResolver(client);

    assertEquals(Ordered.LOWEST_PRECEDENCE, resolver.getOrder());
    assertNull(resolver.resolveException(new MockHttpServletRequest(),
        new MockHttpServletResponse(), new Object(), new IllegalStateException("mvc failed")));
    assertTrue(client.flush(Duration.ofSeconds(1)));
    assertEquals("mvc failed", sent.get(0).getItems().get(0).get("message"));
    client.close();
  }

  @Test
  void requestScopeIsAlwaysRemoved() throws Exception {
    List<MonicaEnvelope> sent = new ArrayList<>();
    MonicaClient client = client(sent);
    MonicaRequestScopeFilter filter = new MonicaRequestScopeFilter(client);
    MockHttpServletRequest request = new MockHttpServletRequest("GET",
        "/form/follow/secret-token");
    request.setServerName("app.example.test");
    request.setScheme("https");
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
      @Override
      public void doFilter(javax.servlet.ServletRequest servletRequest,
          javax.servlet.ServletResponse servletResponse) {
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
            "/form/follow/{token}");
        new MonicaRequestContextInterceptor().preHandle(request,
            (MockHttpServletResponse) servletResponse, new Object());
        client.captureMessage("inside request");
      }
    });
    client.captureMessage("after request");
    client.flush(Duration.ofSeconds(1));

    List<MonicaEvent> items = sent.get(0).getItems();
    @SuppressWarnings("unchecked")
    Map<String, Object> contexts = (Map<String, Object>) items.get(0).get("contexts");
    assertTrue(contexts.containsKey("request"));
    @SuppressWarnings("unchecked")
    Map<String, Object> requestContext = (Map<String, Object>) contexts.get("request");
    assertEquals("GET", requestContext.get("method"));
    assertEquals("/form/follow/{token}", requestContext.get("route"));
    assertFalse(requestContext.containsKey("url"));
    assertTrue(requestContext.values().stream().noneMatch(value ->
        String.valueOf(value).contains("secret-token")));
    assertNull(items.get(1).get("contexts"));
    client.close();
  }

  private MonicaClient client(List<MonicaEnvelope> sent) {
    return MonicaClient.builder()
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
  }
}
