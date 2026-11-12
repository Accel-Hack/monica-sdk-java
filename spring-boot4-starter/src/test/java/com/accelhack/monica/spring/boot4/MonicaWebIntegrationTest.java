package com.accelhack.monica.spring.boot4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.accelhack.monica.BeforeSend;
import com.accelhack.monica.MonicaClient;
import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import com.accelhack.monica.MonicaPresenceStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class MonicaWebIntegrationTest {
  @Test
  void capturesAnUnresolvedControllerExceptionWithItsRouteTemplate() {
    List<MonicaEvent> events = new CopyOnWriteArrayList<>();
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            MonicaAutoConfiguration.class, MonicaWebAutoConfiguration.class))
        .withUserConfiguration(OrdersApp.class)
        .withBean(BeforeSend.class, () -> (event, hint) -> {
          events.add(event);
          return null;
        })
        .withPropertyValues(
            "monica.dsn=https://msk_test@localhost:9876/project",
            "monica.environment=test")
        .run(context -> {
          MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
              .addFilters(context.getBean(MonicaRequestScopeFilter.class))
              .build();

          assertThrows(Exception.class, () -> mvc.perform(get("/orders/secret-42")));

          assertEquals(1, events.size());
          MonicaEvent event = events.get(0);
          assertEquals("order lookup failed", event.get("message"));
          assertEquals("spring_mvc", map(event.get("tags")).get("integration"));
          Map<String, Object> request = map(map(event.get("contexts")).get("request"));
          assertEquals("GET", request.get("method"));
          assertEquals("/orders/{id}", request.get("route"));
          assertTrue(request.values().stream().noneMatch(value ->
              String.valueOf(value).contains("secret-42")));
        });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class OrdersApp {
    @Bean
    OrdersController ordersController() {
      return new OrdersController();
    }
  }

  @RestController
  static class OrdersController {
    @GetMapping("/orders/{id}")
    String order() {
      throw new IllegalStateException("order lookup failed");
    }
  }

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
      public void doFilter(jakarta.servlet.ServletRequest servletRequest,
          jakarta.servlet.ServletResponse servletResponse) {
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
        .presenceStore(alreadyReported())
        .environment("test")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build();
  }

  /** Keeps the init heartbeat out of what these tests count. */
  private static MonicaPresenceStore alreadyReported() {
    MonicaPresenceStore store = MonicaPresenceStore.inMemory();
    store.setLastReportedAt(System.currentTimeMillis());
    return store;
  }
}
