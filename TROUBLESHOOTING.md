# トラブルシューティング

monica-sdk-java で event が届かないとき、ingest に拒否されたときに見るもの。

## 警告の見方

拒否の警告は SDK が `System.Logger`（logger 名 `com.accelhack.monica`、level `WARNING`）へ
1 行で出す。SLF4J / Logback を JDK の `System.Logger` に橋渡ししていれば、アプリケーションの
log にも同じ logger 名で出る。`MonicaAppender` は `com.accelhack.monica` で始まる logger の
event を無視するので、この警告自体が MONICA へ送り返されることはない。

```text
monica: ingest rejected the envelope with 422 (invalid_envelope): 1 issue(s); $.items[0].request.method: Invalid type: Expected string
monica: ingest rejected the envelope with 401 (unauthorized); no further envelopes will be sent
```

- 括弧の中はレスポンス body の `error.code`。body が無い・読めない場合は `unknown` になる。
- `422` は `N issue(s)` の後に `; <path>: <message>` が issue の数だけ続く。
- 警告に API key と envelope の中身は入らない。
- 出力先は `onDiagnostic(...)` で差し替え、`MonicaDiagnostic.silent()` で止められる。

```java
MonicaClient.builder()
    .dsn(dsn)
    .environment("production")
    .onDiagnostic(message -> LoggerFactory.getLogger("monica").warn(message))
    .build();
```

`transport(...)` で自前の transport を渡した場合、`onDiagnostic` はその transport には届かない。
`JdkHttpTransport` を自分で組み立てるなら、4 引数の constructor に `MonicaDiagnostic` を渡す。

Spring Boot 2 starter は `onDiagnostic` を property に出していない。差し替えるときは
`MonicaClient` bean を自分で定義する。

## ingest が envelope を拒否したとき

| status | 挙動 | 警告 | 対処 |
| --- | --- | --- | --- |
| `2xx` | 受理 | なし | — |
| `400` | 破棄（再送しない） | なし | `lastSendResult()` の `getErrorCode()` / `getErrorMessage()` を見る |
| `401` | 破棄し、以後この client は POST しない | 初回のみ | 鍵を入れ替え、`MonicaClient` を作り直す |
| `413` | 破棄（分割再送はしない） | なし | 大きい context や message を `beforeSend` で削る |
| `422` | 破棄（再送しない） | 毎回 | `issues` の `path` が指す field を直す |
| `429` | `Retry-After` 秒（整数、最大 60 秒）待って再送 | なし | 送信量を減らす（`sampleRate`、`batchSize`） |
| `5xx` | backoff して再送 | なし | 再送が尽きたら envelope は失われる |
| network 失敗 | backoff して再送 | なし | 同上 |

`422` の `issues` は envelope 中の JSON path を指す。`$.items[0].contexts.order` のように
event のどこが受理されなかったかが分かるので、まず `beforeSend` や `CaptureContext.context(...)`
で入れている値を疑う。レスポンス body は 64 KiB まで読み、それを超える body は空として扱う。

## 送信結果の受け取り

直近の envelope の結果は `MonicaClient.lastSendResult()`、自前 transport なら
`MonicaTransport.deliver(...)` の戻り値で読む（まだ 1 件も送っていなければ `null`）。

| メンバ | 型 | 意味 |
| --- | --- | --- |
| `isAccepted()` | `boolean` | ingest が受理した（HTTP 2xx） |
| `getStatus()` | `OptionalInt` | HTTP status。network 失敗や timeout では空 |
| `getErrorCode()` | `String` | `error.code`。人が読むためのもので、分岐には使わない。nullable |
| `getErrorMessage()` | `String` | `error.message`。nullable |
| `getIssues()` | `List<SendResult.Issue>` | `422` が返した field 単位の問題。`getPath()` / `getMessage()`。他の status では空 |
| `isStopped()` | `boolean` | `401` を受けて送信を止めた |

送信が止まっているかは `JdkHttpTransport.isStopped()` でも判別できる。

```java
SendResult result = monica.lastSendResult();
if (result != null && !result.isAccepted()) {
  result.getIssues().forEach(issue -> log.warn("{}: {}", issue.getPath(), issue.getMessage()));
}
```

## 再送・queue の挙動

- 再送するのは `429` / `5xx` / network 失敗だけ。その他の 4xx は 1 回で破棄する。
- 再送回数は `maxRetries`（既定 5）。`requestTimeout`（既定 2 秒）は connect と request の両方に効く。
- backoff は base 1 秒、factor 2、上限 30 秒、jitter 0.5〜1.0 倍。`429` に整数秒の `Retry-After`
  が付いていればそれを優先し、60 秒で頭打ちにする。
- queue は `maxQueueSize`（既定 100）。あふれると古い event から捨て、捨てた件数を次の envelope の
  `discarded` として ingest に報告する。
- 再送が尽きた batch も破棄して `discarded` に加える。
- 送信 thread は daemon で、`flushInterval`（既定 5 秒）ごとに queue を drain する。`fatal` の
  event と `batchSize` 到達時は待たずに送る。

## よくある原因と対処

**`MonicaClient.builder().build()` が `IllegalArgumentException` を投げる**

| message | 原因 |
| --- | --- |
| `dsn must not be empty` | `dsn` 未設定 |
| `dsn must contain an API key` | DSN に key 部分が無い |
| `Java dsn must contain a secret msk_ key` | public key（`mpk_`）を渡している |
| `dsn must use https except for localhost` | `http` の DSN |
| `dsn must be a valid URL` | DSN が URL として読めない |
| `environment must not be empty` | `environment` 未設定 |
| `environment must not exceed 128 characters` | `environment` が長すぎる |
| `sampleRate must be between 0 and 1` | 範囲外 |
| `maxRetries must not be negative` | 負値 |
| `<option> must be positive` | `maxQueueSize` / `batchSize` / `flushInterval` / `flushTimeout` などが 0 以下 |

**event が MONICA に出てこない**

- プロセスが終わる前に `flush(...)` か `close(...)` を呼んでいない。queue に残った event は失われる。
- `sampleRate` が 1 未満。
- `beforeSend` が `null` を返している。
- 同じ `Throwable` instance を 1 秒以内に 2 回 capture している（2 件目は落ちる）。
- `401` で送信が止まっている（`lastSendResult().isStopped()`）。
- Logback appender: 既定は `ERROR` 以上かつ Throwable 付きの log だけ。Throwable の無い log も
  送るなら `captureMessages` を有効にする。
- Spring Boot: `monica.dsn` が未設定だと `MonicaClient` bean が作られず、auto-configuration が
  丸ごと効かない。`monica.enabled=false` でも同じ。
- Spring MVC: `@ExceptionHandler` などが先に例外を解決すると capture されない（MONICA の
  resolver は最後に動く）。

**queue に溜まっているか確かめたい**

`MonicaClient.stats()` の `getQueued()` / `getDiscarded()` を見る。Spring Boot では Actuator の
health `monica` に同じ値が `queued` / `discarded` として出る（この indicator は常に UP を返す）。

**疎通を確認したい**

Spring Boot では `MonicaTestService.sendTestEvent()` が `info` の event を 1 件送って flush し、
受理されたかを `boolean` で返す。
