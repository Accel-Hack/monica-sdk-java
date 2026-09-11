# monica-core

Spring や logging framework に依存しない Java client。例外とメッセージを MONICA の ingest へ送る。

## 対応環境

Java 11 以上。依存は `com.fasterxml.jackson.core:jackson-databind` だけ。

## インストール

```xml
<dependency>
  <groupId>com.accelhack.monica</groupId>
  <artifactId>monica-core</artifactId>
  <version>0.1.1</version>
</dependency>
```

GitHub Packages の repository と credential の設定は
[ルート README のインストール](../README.md#インストール)。

## 初期化

```java
MonicaClient monica = MonicaClient.builder()
    .dsn(System.getenv("MONICA_DSN"))
    .environment(System.getenv().getOrDefault("MONICA_ENVIRONMENT", "production"))
    .release(System.getenv("GIT_SHA"))
    .inAppPackage("com.example.app")
    .build();
```

DSN は secret key（`msk_` 始まり）で、`localhost` / `127.0.0.1` 以外は `https` が必要。
`environment` は必須で 128 文字以内。

## 使い方

```java
String eventId = monica.captureException(error);
monica.captureMessage("payment retry exhausted", "warning");

monica.captureException(error, CaptureContext.create()
    .level("fatal")
    .handled(false)
    .tag("tenant", tenantId)
    .context("order", Map.of("id", orderId)));

monica.globalScope()
    .setTag("service", "checkout")
    .setUser(Map.of("id", userId))
    .addBreadcrumb("http", "POST /orders");

try (MonicaClient.ScopeHandle scope = monica.pushScope()) {
  scope.scope().setTag("job", "nightly-batch");   // このブロックの capture にだけ付く
}

monica.flush(Duration.ofSeconds(2));
monica.close();
```

capture は bounded queue へ積むだけで、送信失敗を呼び出し元へ伝えない。戻り値は `event_id`、
sampling や `beforeSend` で落ちたときは `null`。scope は thread ごとに積まれる。

送信前に JSON の byte 数を検査して batch を分割し、単体で上限を超える event は破棄して
`discarded`（`monica.stats().getDiscarded()`）に数える。

option の一覧と既定値は [ルート README のオプション](../README.md#オプション)、拒否と診断は
[TROUBLESHOOTING.md](../TROUBLESHOOTING.md)。

## ライセンス

Apache License 2.0（[LICENSE](../LICENSE)）。
