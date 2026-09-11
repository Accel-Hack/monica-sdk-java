# monica-sdk-java

Java アプリケーションで起きた例外とメッセージを MONICA の ingest へ送る SDK。
core・Logback appender・Spring Boot 2 starter の 3 artifact を提供する。

## パッケージ

| ディレクトリ | artifact | 用途・要求環境 |
| --- | --- | --- |
| [`core/`](core/README.md) | `com.accelhack.monica:monica-core` | framework 非依存の client。Java 11+ |
| [`logback/`](logback/README.md) | `com.accelhack.monica:monica-logback` | Logback appender。Java 11+ / Logback 1.2+（build 検証は 1.2.13） |
| [`spring-boot2-starter/`](spring-boot2-starter/README.md) | `com.accelhack.monica:monica-spring-boot2-starter` | Spring Boot 2 auto-configuration。Spring Boot 2.6+（build 検証は 2.6.15）/ `javax.servlet` |

Android アプリ向けの `monica-android` は別 repository（`Accel-Hack/monica-sdk-android`）にある。

## インストール

公開先は GitHub Packages（<https://github.com/Accel-Hack/monica-sdk-java/packages>）。
Maven registry は匿名で取得できないので、repository の宣言と token の両方が要る。

`pom.xml` に repository と依存を書く。`monica-logback` と `monica-core` は starter が推移的に持ってくる。

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Accel-Hack/monica-sdk-java</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.accelhack.monica</groupId>
    <artifactId>monica-spring-boot2-starter</artifactId>
    <version>0.1.1</version>
  </dependency>
</dependencies>
```

Spring を使わない場合は `monica-core`（必要なら `monica-logback`）を同じ座標で指定する。

credential は `~/.m2/settings.xml` に environment variable 名だけを書く。`<id>` は
`<repository>` の id と一致させる。

```xml
<servers>
  <server>
    <id>github</id>
    <username>${env.MONICA_PACKAGES_ACTOR}</username>
    <password>${env.MONICA_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

値は build のたびに `gh` から渡す。`read:packages` scope が要る。

```bash
gh auth refresh -s read:packages   # 権限が無いときだけ

export MONICA_PACKAGES_ACTOR="$(gh api user --jq .login)"
export MONICA_PACKAGES_TOKEN="$(gh auth token)"
mvn verify
```

### GitHub Actions から取る場合

job に `packages: read` を与え、その repository の `GITHUB_TOKEN` を渡す。

```yaml
permissions:
  contents: read
  packages: read

steps:
  - uses: actions/setup-java@v5
    with:
      distribution: temurin
      java-version: "17"
      server-id: github
      server-username: GITHUB_ACTOR
      server-password: GITHUB_TOKEN

  - run: mvn --batch-mode verify
    env:
      GITHUB_ACTOR: ${{ github.actor }}
      GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

401 / 403 になる場合は <https://github.com/orgs/Accel-Hack/packages> の対象 package →
Package settings → Manage Actions access で、利用側 repository を Read で追加する。

Gradle から取る場合も同じで、`maven { url = ...; credentials { ... } }` へ
`GITHUB_ACTOR` と `GITHUB_TOKEN` を渡す。

## 初期化

DSN は secret key を含むので、設定ファイルへ実値を置かず環境変数から渡す。

```java
MonicaClient monica = MonicaClient.builder()
    .dsn(System.getenv("MONICA_DSN"))
    .environment(System.getenv().getOrDefault("MONICA_ENVIRONMENT", "production"))
    .release(System.getenv("GIT_SHA"))
    .inAppPackage("com.example.app")
    .build();
```

DSN は secret key（`msk_` 始まり）でなければならず、`localhost` / `127.0.0.1` 以外は
`https` が必要。`environment` は必須で 128 文字以内。

Spring Boot 2 starter を使う場合は `MonicaClient` を自分で作らず、`monica.dsn` を設定する
（「使い方」参照）。

## 使い方

### core を直接使う

```java
monica.captureException(error);
monica.captureMessage("payment retry exhausted", "warning");

// level・tag・context を足す
monica.captureException(error, CaptureContext.create()
    .level("fatal")
    .handled(false)
    .tag("tenant", tenantId)
    .context("order", Map.of("id", orderId)));

monica.flush(Duration.ofSeconds(2));
monica.close();   // AutoCloseable。close 時に flush する
```

`captureException` / `captureMessage` は送った event の `event_id` を返す。sampling や
`beforeSend` で落ちた場合は `null` を返す。level は `fatal` / `error` / `warning` / `info` /
`debug`（既定 `error`、未知の値は `error` として扱う）。`fatal` だけは batch を待たず即送信する。

同じ情報を複数 event に付けるときは scope を使う。

```java
monica.globalScope()
    .setTag("service", "checkout")
    .setUser(Map.of("id", userId))
    .addBreadcrumb("http", "POST /orders");

try (MonicaClient.ScopeHandle scope = monica.pushScope()) {
  scope.scope().setTag("job", "nightly-batch");
  // このブロック内の capture にだけ付く（scope は thread ごと）
}
```

送信前に event を加工・破棄するときは `beforeSend` を渡す。`null` を返すと送らない。

```java
MonicaClient.builder()
    .dsn(dsn)
    .environment("production")
    .beforeSend((event, hint) -> {
      event.remove("server_name");
      return event;
    })
    .build();
```

queue の状態は `monica.stats()` で読む（`getQueued()` / `getDiscarded()`）。

### Logback appender

`ERROR` 以上かつ Throwable 付きの log event を MONICA へ渡す。appender は `MonicaClient` を
setter で受け取るので、`logback.xml` だけでは設定できず、登録は Java で行う。

```java
LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
MonicaAppender appender = new MonicaAppender();
appender.setName("MONICA");
appender.setContext(context);
appender.setClient(monica);
appender.setThreshold("ERROR");            // 既定 ERROR
appender.setCaptureMessages(false);        // 既定 false（Throwable 無しの log は送らない）
appender.setAllowedMdcKeys("request_id");  // comma 区切り。既定は空（MDC を送らない）
appender.start();
context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
```

Spring Boot 2 starter を使う場合は、この登録を starter が行う。

### Spring Boot 2 starter

`monica.dsn` を設定すると `MonicaClient` bean が作られ、auto-configuration が有効になる。
`monica.dsn` が無いときは client を作らないので、何も起きない。

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

auto-configuration が入れるもの:

- Spring MVC で解決されなかった例外の capture（`HandlerExceptionResolver`、tag `integration=spring_mvc`）
- request ごとの scope（`request` context に HTTP method と route template）
- `@Scheduled` task の例外 capture（tag `integration=spring_scheduled`）。capture 後も Spring 既定の ERROR log は残る
- Logback root logger への appender 登録（`monica.logback.enabled=false` で止める）
- context 停止時の flush（`monica.flush-timeout` まで待つ）
- Actuator の health indicator `monica`（`queued` / `discarded` を出す。常に UP）
- 疎通確認用の `MonicaTestService` bean（`sendTestEvent()` が `info` event を 1 件送って flush する）

`BeforeSend` bean を定義すると auto-configuration の client に適用される。`MonicaClient` bean を
自分で定義した場合は、そちらが優先される。

## オプション

### `MonicaClient.builder()` / `MonicaOptions.builder()`

| option | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `dsn` | `String` | なし（必須） | ingest の DSN。`msk_` secret key を含む |
| `environment` | `String` | なし（必須） | 128 文字以内 |
| `release` | `String` | 未設定 | 設定すると event の `release` に入る |
| `serverName` | `String` | 未設定 | 設定すると event の `server_name` に入る |
| `inAppPackage` / `inAppPackages` | `String` / `Iterable<String>` | 空 | 前方一致した package の frame を `in_app: true` にする |
| `beforeSend` | `BeforeSend` | 未設定 | 送信前の加工。`null` を返すと破棄 |
| `maxQueueSize` | `int` | `100` | 超えると古い event から捨て、`discarded` に加算 |
| `maxBreadcrumbs` | `int` | `100` | scope が保持する breadcrumb 数 |
| `batchSize` | `int` | `30` | 1 envelope の item 数。`min(maxQueueSize, 100)` で頭打ち |
| `flushInterval` | `Duration` | `5s` | 定期送信の間隔 |
| `flushTimeout` | `Duration` | `2s` | `flush()` / `close()` の既定待ち時間 |
| `sampleRate` | `double` | `1` | 0〜1。event ごとに判定する |
| `maxRetries` | `int` | `5` | 再送回数（`429` / `5xx` / network 失敗のみ） |
| `requestTimeout` | `Duration` | `2s` | connect と request の timeout |
| `sdk` | `(String name, String version)` | `com.accelhack.monica:monica-core` / `0.1.1` | envelope の `sdk` |
| `transport` | `MonicaTransport` | JDK HttpClient 実装 | 送信経路の差し替え |
| `onDiagnostic` | `MonicaDiagnostic` | `System.Logger` へ `WARNING` | 拒否の警告先。[TROUBLESHOOTING.md](TROUBLESHOOTING.md) |

### Spring Boot properties

| property | 型 | 既定 |
| --- | --- | --- |
| `monica.enabled` | `boolean` | `true` |
| `monica.dsn` | `String` | 未設定（未設定なら client を作らない） |
| `monica.environment` | `String` | `production` |
| `monica.release` | `String` | 未設定 |
| `monica.server-name` | `String` | 未設定 |
| `monica.in-app-packages` | `List<String>` | 空 |
| `monica.flush-timeout` | `Duration` | `2s` |
| `monica.flush-interval` | `Duration` | `5s` |
| `monica.max-queue-size` | `int` | `100` |
| `monica.batch-size` | `int` | `30` |
| `monica.sample-rate` | `double` | `1` |
| `monica.logback.enabled` | `boolean` | `true` |
| `monica.logback.capture-messages` | `boolean` | `false` |
| `monica.logback.allowed-mdc-keys` | `List<String>` | 空 |

`maxBreadcrumbs` / `maxRetries` / `requestTimeout` / `transport` / `onDiagnostic` / appender の
`threshold` は properties に無い。変えるときは `MonicaClient` bean を自分で定義する。

## 自動で収集するもの

すべての event: `event_id`、`timestamp`、`level`、`platform`（`java`）、`environment`、
設定していれば `release` と `server_name`。

例外: 原因の連鎖をたどった各例外の class 名・message・stack frame（`filename`、`function`、
`lineno`、`in_app`）と `mechanism.handled`。frame は throw 地点に近い 200 件までで、古い呼び出し元から落とす。

Logback appender: tag `logger` と `log_level`、context `logback` の thread 名。MDC は
`allowedMdcKeys` に挙げた key だけ。log の formatted argument は送らない。

Spring Boot starter: HTTP method と Spring MVC の route template（`/orders/{id}` 形式）。
生の URL・query string・header・request body は収集しない。MVC と `@Scheduled` の capture には
`integration` tag が付く。

ユーザーを特定する情報は自動では読まない。`Scope.setUser(...)` を呼んだときだけ event に入る。
tag・context・breadcrumb も、アプリケーションが入れたものだけを送る。

## 送信結果と診断

送信は背景 thread で行い、失敗をアプリケーションへ伝播しない。ingest が envelope を拒否したときは
`monica: ingest rejected the envelope with ...` の 1 行が `System.Logger`（logger 名
`com.accelhack.monica`、`WARNING`）に出る。出力先は `onDiagnostic(...)` で差し替え、
`MonicaDiagnostic.silent()` で止められる。

直近の結果は `MonicaClient.lastSendResult()` で読む。`SendResult` は HTTP status、
`error.code` / `error.message`、`422` の `issues`、`401` で送信を止めたかどうかを持つ。

警告の読み方、status ごとの挙動、`SendResult` の詳細、再送と queue の挙動は
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) にある。

## 制約

- DSN は secret key（`msk_`）専用。public key（`mpk_`）は受け付けない。secret key を含むので、
  利用者へ配布する成果物に DSN を埋め込まない。
- `https` 必須（`localhost` / `127.0.0.1` のみ例外）。
- 1 envelope は item 100 件まで、JSON 1,000,000 byte まで。超える batch は送信前に分割し、
  単体で超える event は破棄して `discarded` に数える。
- `413` を受けた envelope は分割再送せず破棄する（`transport.json` の `split_and_retry` は未実装）。
- 同じ `Throwable` instance を 1 秒以内に再 capture しても 1 件しか送らない。
- `401` を受けた後、その client は ingest へ POST しない。鍵を入れ替えたら `MonicaClient` を作り直す。
- capture は queue へ積むだけで、送信失敗を呼び出し元へ伝えない。プロセス終了前に `flush()` か
  `close()` を呼ばないと queue に残った event は失われる。
- Spring Boot 2 starter は `javax.servlet` 系専用。`jakarta.servlet` の Spring Boot 3 では動かない。

## ライセンス

Apache License 2.0（[LICENSE](LICENSE)）。

## 開発者向け

### ビルドとテスト

```bash
mvn verify
```

CI は Java 11 と 18 で同じ `mvn verify` を走らせる。

### 公開契約（spec/）

```bash
python3 scripts/spec-sync.py                 # 配信元から取り込み直す
python3 scripts/spec-sync.py --check         # 取り込んだコピーが spec.lock.json と一致するか
python3 scripts/spec-sync.py --check-remote   # 配信元と一致するか
```

`spec/` は手で編集しない。契約テストは `core/src/test/java/com/accelhack/monica/ProtocolContractTest.java`
で、`mvn verify` の一部として走る。

### リリース

1. POM の version を `X.Y.Z-SNAPSHOT` に、`MonicaOptions.DEFAULT_SDK_VERSION` を `X.Y.Z` にして main へ merge する。
2. その commit に `vX.Y.Z` tag を付けて push すると `.github/workflows/maven-release.yml` が
   GitHub Packages へ公開する。

同じ version は再公開できない。公開済みの版を直すときは patch version を上げる。
