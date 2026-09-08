# monica-android

Android アプリへ組み込む MONICA SDK。`monica-core` の queue、batch、sampling、
`beforeSend`、envelope 分割をそのまま使い、Android で成立しない部分だけを差し替える。

- transport は `HttpURLConnection`。Android に `java.net.http.HttpClient` は無い
- DSN は **public key（`mpk_`）だけ**を受け付ける。APK は誰でも展開できるので、
  `msk_` を渡すと SDK は無効になり、何も送らない（logcat の tag `MONICA` に理由が出る）
- 端末 / OS / アプリ version を `contexts` へ載せる。個体を特定する値は読まない
- 未捕捉例外を `level: fatal` で捕まえ、送り切ってから元の handler へ委譲する

設計と決定の経緯は MONICA 本体の設計ドキュメントにある。

## 導入

```groovy
dependencies {
  implementation 'com.accelhack.monica:monica-android:0.1.0'
}

android {
  compileOptions {
    sourceCompatibility JavaVersion.VERSION_11
    targetCompatibility JavaVersion.VERSION_11
  }
  defaultConfig {
    minSdk 26
  }
}
```

`minSdk 26` / AGP 7.0 以上。`monica-core` が `java.time`、`java.util.function`、
`CompletableFuture` を使うため、これらが素で使える API level を下限にしている。
21〜25 は core library desugaring を有効にすれば動くと思われるが、**未確認**。
core と android の両 module は animal-sniffer（`gummy-bears-api-26`）で API 26 に無い
JDK API を参照していないことをビルドで検査している。

インターネット権限が要る。

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## 初期化

```java
public final class ExampleApp extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    MonicaAndroid.install(this, MonicaAndroidOptions.builder()
        .dsn(BuildConfig.MONICA_DSN)
        .environment(BuildConfig.DEBUG ? "development" : "production")
        .release(BuildConfig.VERSION_NAME)
        .inAppPackage("com.example.app")
        .beforeSend((event, hint) -> event)
        .build());
  }
}
```

`install()` を呼ぶまで何も送らない。classpath に置いただけでは動き出さない。

```java
MonicaAndroid monica = MonicaAndroid.current();
monica.captureException(error, CaptureContext.create().tag("feature", "checkout"));
monica.captureMessage("payment retry exhausted", CaptureContext.create().level("warning"));
monica.addBreadcrumb("ui.click", "submitButton");
monica.setUser("u_123");
monica.setScreen("CheckoutFragment");
```

### SDK の失敗はアプリへ波及させない

- **設定ミスでも投げない。** DSN（空、`msk_`、https 以外）、`environment`（空、129 文字以上）、
  0 以下の `maxQueueSize` / `batchSize` / `maxBreadcrumbs`、0 以下や `null` の Duration、
  0..1 の外の `sampleRate` は `MonicaAndroidOptions.build()` が見つけて `problems()` に集める。
  `install()` は問題があれば logcat に理由を書き、何もしない instance を返す。`null` 引数も同じ
- **開発中に厳しくしたいときは自分で投げる。** SDK は落とさないので、必要ならアプリ側で

  ```java
  MonicaAndroidOptions options = builder.build();
  if (BuildConfig.DEBUG && !options.problems().isEmpty()) {
    throw new IllegalStateException("MONICA: " + options.problems());
  }
  ```
- **`current()` は `null` を返さない。** install 前、install 失敗後、`close()` 後は何もしない
  instance を返す。`captureXxx` は `null`、`flush` は `false` を返す。`isInstalled()` で見分ける
- **public メソッドは全て `Throwable` を握る。** SDK の不具合でアプリは落ちない
- **握り潰した失敗は logcat の tag `MONICA` に `Log.w` で 1 行残す。** 「event が届かない」ときは
  `adb logcat -s MONICA` を見る
- **`flush()` と `close()` はブロックする。main thread で呼ばない。** `close()` は
  `flushTimeout`（既定 2 秒）までしか待たず、クラッシュ経路の `shutdownTimeout` とは別

## option

| option | 既定 | 意味 |
| --- | --- | --- |
| `dsn` | 必須 | `mpk_` の public key を含む DSN |
| `environment` | 必須 | `production` など。前後の空白は落とす。128 文字まで |
| `release` | `versionName` | 未指定ならアプリの versionName |
| `inAppPackage` | アプリの package 名 | frame の `in_app` 判定 |
| `beforeSend` | なし | 送信前の最後の関門。PII の除去はここ |
| `sampleRate` | `1.0` | |
| `maxQueueSize` / `batchSize` | `100` / `30` | |
| `maxBreadcrumbs` | `50` | 超えた分は古い順に落とす |
| `flushInterval` / `flushTimeout` | `5s` / `2s` | 通常時の送信間隔と、`flush()` / `close()` が待つ上限 |
| `shutdownTimeout` | `5s` | クラッシュ時に送信を待つ上限。fatal を含む envelope の送信締切にもなる |
| `requestTimeout` / `maxRetries` | `10s` / `2` | 通常の event 用。fatal では `shutdownTimeout` の残り時間に切り詰められる |
| `captureUncaughtExceptions` | `true` | |
| `trackScreens` | `true` | Activity 遷移の breadcrumb |
| `attachDeviceContext` | `true` | 端末 / OS / アプリ context |

## 自動で集めるもの / 集めないもの

`contexts.device` に manufacturer / brand / model、`contexts.os` に `Android` と
version / API level、`contexts.app` に package 名と versionName / versionCode。
Activity 遷移は `ui.lifecycle` breadcrumb と `screen` tag に残す。値は Activity の
単純クラス名で、実行時の入力は含まない。

`ANDROID_ID`、serial、IMEI、広告 ID、アカウント、位置情報、実ファイルパスは
**一切読まない**。パーミッションを要求する API も呼ばない。何が PII かはアプリ側にしか
判断できないので、`setUser()` と `beforeSend` で明示した値だけを送る。

## クラッシュ

未捕捉例外は `level: fatal` / `handled: false` で capture し、クラッシュしたスレッドを
`shutdownTimeout` まで待たせてから、元々登録されていた handler へ委譲する。HTTP 送信自体は
core の sender thread で走るので、main thread から `NetworkOnMainThreadException` にはならない。

fatal を含む envelope は `shutdownTimeout` を締切として送る。`requestTimeout`（既定 10 秒）と
`maxRetries`（既定 2）をそのまま使うと 1 回目の試行だけで締切を超えうるので、transport は
残り時間を connect / read のタイムアウトにし、backoff を挟む余裕が無ければ再試行しない。
初回リクエストは TLS 確立込みで 3〜5 秒かかることがあるため、`shutdownTimeout` を短くすると
その分クラッシュを取りこぼしやすくなる。

**ディスクへの永続キューは持たない。** 時間内に送れなかったクラッシュは失われる。
オフラインのクラッシュを拾うかどうかは Q8 で決める。

## R8 / ProGuard

SDK 自身が動くための keep ルールは jar の `META-INF/proguard/monica-android.pro` に入れてあり、
AGP が自動で読む。envelope の getter 名が難読化されると ingest が弾くため、model class と
`SourceFile` / `LineNumberTable` を保持している。

**難読化するアプリは、次の 1 行を自分の `proguard-rules.pro` に足す。** これは SDK からは配れない
（アプリの package 名を SDK は知らない）。

```
-keepnames class com.example.app.** { *; }   # inAppPackage() に渡す package と一致させる
```

無いと、クラス名が `c6` になってアプリの frame が 1 つも `in_app` にならない。グルーピングが
フレームワークの frame に落ちて、**同じクラッシュが OS ごと・ビルドごとに別 Issue になり、
スタックも読めない**。`-keepnames` は名前だけ残して shrink と最適化は効かせる指定なので、
APK サイズへの影響はほぼ無い。

ファイル名は気にしなくてよい。R8 は難読化時に SourceFile を必ず書き換える（既定は
`r8-map-id-<ビルドごとのハッシュ>`、`-renamesourcefileattribute` を書けばその文字列）ので、
SDK は **`.` を含まない SourceFile を「ファイル名無し」と見て**、残っているクラス名から
`MainActivity.java` を導出する。`-renamesourcefileattribute` に何を書いても同じ扱いになる。
難読化を維持したい場合は Q9 を参照。

**Kotlin の注意:** 難読化していないビルドは `MainActivity.kt`、難読化したビルドは導出した
`MainActivity.java` が frame の filename になる。grouping は拡張子を潰さないので、同じクラッシュが
debug と release で別 Issue になる。release だけを送るアプリでは問題にならない。
