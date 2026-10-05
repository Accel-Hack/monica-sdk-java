# monica-sdk-java

Java アプリケーションで起きた例外とメッセージを MONICA の ingest へ送る SDK。
core・Logback appender・Spring Boot 2 starter の 3 artifact を提供する。

## パッケージ

| ディレクトリ | artifact | 用途・要求環境 |
| --- | --- | --- |
| [`core/`](core/README.md) | `com.accelhack.monica:monica-core` | framework 非依存の client。Java 11+ |
| [`logback/`](logback/README.md) | `com.accelhack.monica:monica-logback` | Logback appender。Java 11+ / Logback（検証は 1.2.13） |
| [`spring-boot2-starter/`](spring-boot2-starter/README.md) | `com.accelhack.monica:monica-spring-boot2-starter` | Spring Boot 2 auto-configuration。Spring Boot 2 系（検証は 2.6.15）/ `javax.servlet` |

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
    <version>0.3.1</version>
  </dependency>
</dependencies>
```

Spring を使わない場合は `monica-core`（必要なら `monica-logback`）を同じ座標で指定する。

GitHub Packages の Maven registry は、同じ owner の package ならその owner 配下のどの
repository URL からでも返す。Accel-Hack の repository を既に宣言していれば、上の
`<repository>` は足さなくてよい。

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

job に `packages: read` を与え、その repository の `GITHUB_TOKEN` を渡す。package は public
なので、Accel-Hack 以外の organization の repository でも personal access token は要らない。

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
`monica.dsn` が無いとき（`${MONICA_DSN:}` が空文字に展開された場合を含め、空白だけの値も同じ）は
client を作らないので、何も起きない。

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
- context 停止時に `MonicaClient` を close（`monica.flush-timeout` まで flush を待つ）
- `SpringApplication` の起動失敗の capture（level `fatal`、tag `integration=spring_boot_startup`）
- Actuator の health indicator `monica`（`queued` / `discarded` を出す。常に UP）
- 疎通確認用の `MonicaTestService` bean（`sendTestEvent()` が `info` event を 1 件送って flush する）

MVC の capture と request scope は servlet の Spring MVC アプリケーションのとき、appender は
Logback が SLF4J の binding のとき、health indicator は Actuator が classpath にあるときだけ入る。
`@Scheduled` の capture は Spring Boot が作る `TaskScheduler` へ error handler を
差し込む形なので、`TaskScheduler` や `SchedulingConfigurer` を自分で定義している場合は入らない。

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
| `sdk` | `(String name, String version)` | `com.accelhack.monica:monica-core` / `0.3.1` | envelope の `sdk` |
| `transport` | `MonicaTransport` | JDK HttpClient 実装 | 送信経路の差し替え |
| `onDiagnostic` | `MonicaDiagnostic` | `System.Logger` へ `WARNING` | 拒否の警告先。[TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
| `presenceStore` | `MonicaPresenceStore` | プロセス内メモリ | 稼働確認の状態の保存先。配布物（monica-android）が端末のストレージに差し替える。サーバでは指定しない |

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

`maxBreadcrumbs` / `maxRetries` / `requestTimeout` / `transport` / `onDiagnostic` は properties に
無い。変えるときは `MonicaClient` bean を自分で定義する。appender の `threshold` も properties に
無く、starter は既定（`ERROR`）で登録する。変えるときは `monica.logback.enabled=false` にして
`MonicaAppender` を自分で登録する。

## 自動で収集するもの

すべての event: `event_id`、`timestamp`、`level`、`platform`（`java`）、`environment`、
設定していれば `release` と `server_name`。

例外: 原因の連鎖をたどった各例外の class 名・message・stack frame（`filename`、`function`、
`lineno`、`in_app`）と `mechanism.handled`。frame は throw 地点に近い 200 件までで、古い呼び出し元から落とす。

Logback appender: tag `logger` と `log_level`、context `logback` の thread 名。MDC は
`allowedMdcKeys` に挙げた key だけ。message は `{}` を置換する前の log pattern で、formatted
argument は送らない。

Spring Boot starter: HTTP method と Spring MVC の route template（`/orders/{id}` 形式）。
生の URL・query string・header・request body は収集しない。MVC と `@Scheduled` の capture には
`integration` tag が付く。

ユーザーを特定する情報は自動では読まない。`Scope.setUser(...)` を呼んだときだけ event に入る。
tag・context・breadcrumb も、アプリケーションが入れたものだけを送る。

## Issue のまとめ方

どのエラーを同じ Issue にまとめるかは MONICA 側で決まり、SDK は決めない。規則は
[`spec/v1/grouping.md`](spec/v1/grouping.md) にある。分かれ方が意外なときは、管理画面の
Issue 詳細の「まとめ方」で、その Issue がどの値でまとめられたかを確かめる。

この SDK に固有の点は次のとおり。

- frame の関数名は `完全修飾クラス名.メソッド名` で送る。lambda や匿名クラスの連番
  （`lambda$run$0`、`Outer$1`）と、CGLIB などの proxy が付ける `$$` 以降の接尾辞は MONICA が無視する
- `in_app: true` になるのは、`inAppPackage`（Spring Boot では `monica.in-app-packages`）に
  前方一致した class の frame だけ。既定は空で、そのままだと全 frame が `in_app: false` になり、
  throw 地点に近い JDK や framework の frame で Issue が決まる。自分の package を必ず設定する
- `fingerprint` を渡す専用の API は無い。`beforeSend` で `event.put("fingerprint", List.of(...))`
  とする。元の例外は `hint.getOriginalException()` で取れる。`fingerprint` は既定の分け方を
  置き換えるので、どこで起きたかの区別も値に含める
- Logback appender で Throwable の無い log を送ると（`setCaptureMessages(true)`）、message は
  `{}` を置換する前の pattern なので、引数だけが違う log は 1 つの Issue になる

## 稼働確認

アプリケーションが動いていることを MONICA に知らせるため、`client_report` item 1 件だけの
envelope（heartbeat）を送る。endpoint・認証・リトライは error の送信と同じで、背景 thread から送る。

- init 時に `trigger: "start"` を 1 通送る。
- 定期送信（`flushInterval` ごと）のたびに判定し、直近 1 間隔（既定 1 日）に受理（`202`）された
  envelope が無く、queue が空なら `trigger: "interval"` を送る。error の envelope が受理されても
  期限は伸びる。
- 送信に失敗した heartbeat は次の tick で再送せず、1 間隔後の判定まで待つ。
- 状態（最後に受理された時刻または heartbeat を試みた時刻、MONICA から届いた間隔）はプロセス内
  メモリに持つ。再起動や `MonicaClient` の作り直しのたびに `start` が出る。複数プロセスはそれぞれ
  送る。
- 判定の間隔は `202` の応答 header `X-Monica-Presence-Interval-Ms` を読んで次の判定から使う。
  既定は 1 日で、60 秒未満や数値でない値は無視する。`X-Monica-Presence-Sample-Rate` は読むが、
  この SDK は間引かない。SDK 側に設定項目は無い。
- 配布物（monica-android）は、`presenceSuspended(true)` で構築して init の `start` を止め、
  バックグラウンド中は `setPresenceSuspended(true)` で heartbeat を止める。フォアグラウンドに
  なるたびに `checkPresence()` を呼び、suspend を解除して判定する。サーバでは使わない。

導入側で気を付けること:

- Spring Boot では application context が起動するたびに `start` が 1 通出る。実 DSN を設定した
  test も同じなので、test では `monica.enabled=false` にするか DSN を外す。
- MONICA に届かないと、init 直後の `start` のリトライが送信 thread を塞ぐ（既定の設定で backoff
  の待ちだけで最大 31 秒、各回の `requestTimeout` が加わる）。その間の error は queue に溜まり、
  後で送る。error の送信が失敗したときと同じ挙動。

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
- 1 秒以内に capture した `Throwable` instance と、その原因の連鎖に含まれる例外は送らない（app の
  log・MVC・container の root cause の log が同じ失敗を報告しても 1 件になる）。capture 済みの
  例外を包む例外は送る。
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
