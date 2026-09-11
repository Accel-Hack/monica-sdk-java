# monica-logback

Logback の log event を `monica-core` の queue へ渡す appender。

## 対応環境

Java 11 以上、Logback（検証は 1.2.13）。`logback-classic` は optional 依存なので、
アプリケーション側の版が使われる。

## インストール

```xml
<dependency>
  <groupId>com.accelhack.monica</groupId>
  <artifactId>monica-logback</artifactId>
  <version>0.1.1</version>
</dependency>
```

GitHub Packages の repository と credential の設定は
[ルート README のインストール](../README.md#インストール)。

## 使い方

appender は `MonicaClient` を setter で受け取るので、`logback.xml` だけでは設定できない。
登録は Java で行う。

```java
LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
MonicaAppender appender = new MonicaAppender();
appender.setName("MONICA");
appender.setContext(context);
appender.setClient(monica);
appender.start();
context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
```

Spring Boot 2 を使う場合は、この登録を
[`monica-spring-boot2-starter`](../spring-boot2-starter/README.md) が行う。

## オプション

| setter | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `setClient` | `MonicaClient` | なし（必須） | 未設定の間、appender は何もしない |
| `setThreshold` | `String` | `ERROR` | この level 以上の log だけを対象にする。読めない値は `ERROR` |
| `setCaptureMessages` | `boolean` | `false` | `true` にすると Throwable の無い log も message として送る |
| `setAllowedMdcKeys` | `String`（comma 区切り） | 空 | 挙げた key の MDC だけを event に入れる |

log の level は event の level に写す（`ERROR`→`error`、`WARN`→`warning`、`INFO`→`info`、
それ未満は `debug`）。

## 自動で収集するもの

tag `logger`（logger 名）と `log_level`、context `logback` の `thread`（thread 名）、
`allowedMdcKeys` に挙げた MDC。event の message は `{}` を置換する前の log pattern で、
formatted argument は送らない。
logger 名が `com.accelhack.monica` で始まる event は、SDK 自身の警告が送り返されないよう無視する。

## ライセンス

Apache License 2.0（[LICENSE](../LICENSE)）。
