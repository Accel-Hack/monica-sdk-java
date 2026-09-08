# monica-logback

Logback の `ERROR` 以上かつ Throwable 付き event を MONICA の non-blocking queue へ渡す。
formatted arguments と MDC は既定で送らず、MDC は明示 allowlist に含めた key だけを送る。

Spring Boot 2 starter は classpath 上の Logback を検出して appender を設定する。単独利用では
`MonicaAppender#setClient` を呼んでから LoggerContext へ追加する。
