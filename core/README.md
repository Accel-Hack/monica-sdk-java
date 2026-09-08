# monica-core

Spring や logging framework に依存しない Java 11+ SDK。

```java
MonicaClient monica = MonicaClient.builder()
    .dsn(System.getenv("MONICA_DSN"))
    .environment(System.getenv().getOrDefault("MONICA_ENVIRONMENT", "production"))
    .release(System.getenv("GIT_SHA"))
    .inAppPackage("com.example.app")
    .beforeSend((event, hint) -> event)
    .build();

monica.captureException(error);
monica.flush(Duration.ofSeconds(2));
```

Capture は bounded queue への追加だけを行い、送信失敗を host application へ伝播しない。
送信前にenvelopeのJSON byte sizeを検査し、安全上限へ収まるようbatchを分割する。単体で
上限を超えるeventは破棄件数へ加え、後続の正常eventは継続して送信する。
