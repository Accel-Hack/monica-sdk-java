# monica-spring-boot2-starter

Spring Boot 2 アプリケーションへ MONICA を組み込む auto-configuration。

## 対応環境

Spring Boot 2 系（検証は 2.6.15）/ Spring Framework 5 / `javax.servlet`、Java 11 以上。
`monica-core` と `monica-logback` を推移的に持ってくる。

## インストール

```xml
<dependency>
  <groupId>com.accelhack.monica</groupId>
  <artifactId>monica-spring-boot2-starter</artifactId>
  <version>0.1.1</version>
</dependency>
```

GitHub Packages の repository と credential の設定は
[ルート README のインストール](../README.md#インストール)。

## 初期化

DSN は secret key を含むので、設定ファイルへ実値を置かず環境変数から渡す。

```yaml
monica:
  dsn: ${MONICA_DSN}
  environment: ${SPRING_PROFILES_ACTIVE:production}
  release: ${GIT_SHA:unknown}
  in-app-packages:
    - com.example.app
  flush-timeout: 2s
  logback:
    allowed-mdc-keys:
      - request_id
```

`monica.dsn` があるときだけ `MonicaClient` bean を作る。未設定、または `monica.enabled=false` の
ときは auto-configuration が丸ごと効かない。

## 使い方

capture は自動で行われる。手で送るときは `MonicaClient` bean を inject する。

```java
@Service
public class CheckoutService {
  private final MonicaClient monica;

  public CheckoutService(MonicaClient monica) {
    this.monica = monica;
  }

  public void charge(Order order) {
    try {
      gateway.charge(order);
    } catch (GatewayException error) {
      monica.captureException(error,
          CaptureContext.create().tag("order_id", String.valueOf(order.getId())));
    }
  }
}
```

auto-configuration が入れるもの:

- Spring MVC で解決されなかった例外の capture（tag `integration=spring_mvc`）。
  `@ExceptionHandler` などが先に解決した例外は対象外
- request ごとの scope（`request` context に HTTP method と route template）
- `@Scheduled` task の例外 capture（tag `integration=spring_scheduled`）。capture 後も Spring 既定の
  ERROR log と繰り返し抑制はそのまま残る
- Logback root logger への appender 登録（`monica.logback.enabled=false` で止める）
- context 停止時に `MonicaClient` を close（`monica.flush-timeout` まで flush を待つ）
- Actuator の health indicator `monica`（`queued` / `discarded` を出す。常に UP）
- 疎通確認用の `MonicaTestService` bean（`sendTestEvent()` が `info` event を 1 件送って flush する）

MVC の capture と request scope は servlet の Spring MVC アプリケーションのとき、appender は
Logback が SLF4J の binding のとき、health indicator は Actuator が classpath にあるときだけ入る。
`@Scheduled` の capture は Spring Boot が作る `TaskScheduler` へ error handler を
差し込む形なので、`TaskScheduler` や `SchedulingConfigurer` を自分で定義している場合は入らない。

`BeforeSend` bean を定義すると auto-configured client に適用される。`MonicaClient` bean を自分で
定義した場合は、そちらが使われる。

## オプション

properties の一覧と既定値は [ルート README のオプション](../README.md#オプション)。
`max-breadcrumbs` / `max-retries` / `request-timeout` / `transport` / `on-diagnostic` は properties に
無いので、変えるときは `MonicaClient` bean を自分で定義する。appender の `threshold` も properties に
無く、starter は既定（`ERROR`）で登録する。変えるときは `monica.logback.enabled=false` にして
`MonicaAppender` を自分で登録する。

## 自動で収集するもの

request からは HTTP method と Spring MVC の route template（`/orders/{id}` 形式）だけを取る。
生の URL・query string・header・request body は収集しない。

## ライセンス

Apache License 2.0（[LICENSE](../LICENSE)）。
