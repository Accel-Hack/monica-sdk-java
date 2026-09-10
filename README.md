# MONICA Java SDK

Java 11 以上向けの MONICA SDK。Maven reactor は次の公開 artifact を検証する。

| モジュール | artifact | 要求環境 |
| --- | --- | --- |
| `core/` | `com.accelhack.monica:monica-core` | Java 11+ |
| `logback/` | `com.accelhack.monica:monica-logback` | Logback 1.2+ |
| `spring-boot2-starter/` | `com.accelhack.monica:monica-spring-boot2-starter` | Spring Boot 2.6+ |

```bash
mvn verify
```

Android 向けの `monica-android` は別 repository（`Accel-Hack/monica-sdk-android`）が正本で、
この reactor には入っていない。`monica-core` は Android アプリにも埋め込まれるので、
animal-sniffer で API 26 に無い JDK API を build で落としている。

Spring Boot 2 starter の DSN は secret key を含むため、設定ファイルへ実値を保存せず
`MONICA_DSN` などの環境変数から渡す。

## 公開契約

protocol は言語に依存しない契約なので、この repository は持たない。MONICA が
<https://spec.monica.accelhack.net/v1/> に配信しているものを取り込んだコピーが
`spec/` にある。

```text
spec.lock.json   取り込んだ内容の記録（origin、version、revision、全ファイルの sha256）
spec/v1/         取り込んだコピー（jar には入らない）
```

取り込みは script でやる。手で `spec/` を編集しても、次の取り込みで消える。
Python 3 の標準ライブラリだけで動き、Maven の build には乗せない。

```sh
python3 scripts/spec-sync.py                 # 配信元から取り込み直す
python3 scripts/spec-sync.py --check         # 取り込んだコピーが spec.lock.json と一致するか（network 不要）
python3 scripts/spec-sync.py --check-remote  # さらに配信元が動いていないか
```

起点は配信元の `index.json`。他の全ファイルのパスと sha256、バンドル全体の
`revision` がそこに並んでいるので、**何を取り込むかは配信元が決める**。この
repository は取り込む対象の一覧を持たない。

`revision` はバンドル全体の指紋（各ファイルの `"<sha256>  <path>"` を path の
byte 順に改行で繋いだ文字列の sha256）で、版番号ではないので新旧や大小は読めない。
`--check` はこれを `spec.lock.json` の `files` から再計算するので、`spec/` を
書き換えて lock の digest を揃えただけの改竄も落ちる。

契約テストは `core/src/test/java/com/accelhack/monica/ProtocolContractTest.java`。
`mvn verify` の一部として走り、spec が見つからないと skip せず失敗する。契約が
変わったときに Java だけ気付けない状態を作らないため。schema を通ることは受理される
ことと同じではない（`payload.md` が prose で定めている義務がある）ので、4 層を見る。

1. `envelope.json` / `limits.json` / `error.json` が、この SDK の前提どおりであること
2. MONICA の test vector が、bundle の言うとおりの判定になること
3. この SDK が出す envelope が、schema と `payload.md` の義務を満たすこと
4. この SDK が投げる request と retry の定数が、`transport.json` の値と一致すること

4 は `ingest.md` の散文から定数を写すのではなく、`transport.json` を読んで
`JdkHttpTransport` の定数と突き合わせる。MONICA 側が endpoint やヘッダを変えると、ここが落ちる。

CI の `公開契約` job は `--check-remote` で配信元の `revision` を取り込み済みのものと
比べる。落ちたら `python3 scripts/spec-sync.py` で取り込み直し、`mvn verify` を
通してから commit する。

### まだ実装していない契約

`transport.json` の `status` のうち、`401`（`drop_and_stop`）は破棄するだけで以後の
送信を止めない。`413`（`split_and_retry`）は分割せず破棄する。`MonicaClient` が
送信前に JSON の byte 数を検査して envelope を分割しているので、ingest が `413` を
返す状況を作らないことで代えている。`error.json` の body は読んでいない。

黙って取り残されないように、契約テストは `transport.json` の section 名と status
の語彙を固定している。MONICA 側が section や status を増やすと、
「この SDK が考慮していない契約が増えた」として落ちる。

## Release

開発中の POM は `X.Y.Z-SNAPSHOT` にする。`core` の `MonicaOptions.DEFAULT_SDK_VERSION`
は同じ版にする（契約テストが pom.xml と突き合わせる）。

4 個の repository secret `MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_TOKEN`、
`MAVEN_GPG_PRIVATE_KEY`、`MAVEN_GPG_PASSPHRASE` を設定し、対応する main commit へ
`vX.Y.Z` tag を付けると `.github/workflows/maven-release.yml` が動く。

workflow は tag と POM version の対応を検証し、release version へ一時変換してから、
source / Javadoc jar と GPG signature を含む 3 artifact を Maven Central へ公開する。
