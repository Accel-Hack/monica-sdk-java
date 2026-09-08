package com.accelhack.monica.spring.boot2;

import com.accelhack.monica.MonicaClient;
import javax.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.DispatcherServlet;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(MonicaAutoConfiguration.class)
@ConditionalOnBean(MonicaClient.class)
@ConditionalOnClass({DispatcherServlet.class, Filter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MonicaWebAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public MonicaExceptionResolver monicaExceptionResolver(MonicaClient client) {
    return new MonicaExceptionResolver(client);
  }

  @Bean
  @ConditionalOnMissingBean
  public MonicaRequestScopeFilter monicaRequestScopeFilter(MonicaClient client) {
    return new MonicaRequestScopeFilter(client);
  }

  @Bean
  @ConditionalOnMissingBean(name = "monicaRequestContextWebMvcConfigurer")
  public WebMvcConfigurer monicaRequestContextWebMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MonicaRequestContextInterceptor());
      }
    };
  }
}
