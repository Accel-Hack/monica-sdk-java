# monica-spring-boot2-starter

Spring Boot 2.6+ / Spring Framework 5 / `javax.servlet` 用 starter。

```yaml
monica:
  enabled: true
  dsn: ${MONICA_DSN}
  environment: ${SPRING_PROFILES_ACTIVE:development}
  release: ${GIT_SHA:unknown}
  in-app-packages:
    - com.example.app
  flush-timeout: 2s
  logback:
    enabled: true
    allowed-mdc-keys:
      - request_id
```

MVC 例外、scheduled task、request scope、shutdown flush、常に host の稼働を優先する
health indicator を自動設定する。request scopeはraw URLを収集せず、methodとSpring MVCの
route templateだけを使う。scheduled task例外はcapture後もSpring既定相当のERROR logへ残す。
任意の`BeforeSend` beanを定義するとauto-configured clientへ適用できる。Spring Boot 3 は別
artifactで提供する。
