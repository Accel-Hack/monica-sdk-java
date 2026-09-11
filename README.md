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

### 拒否された envelope の扱い

4xx（`429` を除く）のレスポンス body を 64 KiB を上限に `error.json` として読み、
`422` は既定で警告を 1 行出す。

```text
monica: ingest rejected the envelope with 422 (invalid_envelope): 1 issue(s); $.items[0].request.method: Invalid type: Expected string
```

| 事項 | 既定 / 指定方法 |
| --- | --- |
| 出力先 | `System.Logger`（logger 名 `com.accelhack.monica`、`WARNING`） |
| 差し替え | `MonicaClient.builder().onDiagnostic(...)`（`MonicaOptions.Builder` にも同名） |
| 無効化 | `onDiagnostic(MonicaDiagnostic.silent())` |

`transport(...)` で自前の transport を渡した場合、`onDiagnostic` は届かない。その transport で
受け取る。

送信結果は `MonicaTransport.deliver()` の戻り値、または `MonicaClient.lastSendResult()` で読む。
`SendResult` は HTTP status（network 失敗時は空）、`error.code` / `error.message`、
`issues` の `path` / `message` を持つ。

`401` を受けた transport は以後 ingest へ POST しない。`JdkHttpTransport.isStopped()` または
`SendResult.isStopped()` で判別できる。鍵を入れ替えたら `MonicaClient` を作り直す。

### まだ実装していない契約

- `transport.json` の `413`（`split_and_retry`）: 分割せず破棄する。`MonicaClient` が送信前に
  JSON の byte 数（1,000,000 byte）を検査して envelope を分割する。

黙って取り残されないように、契約テストは `transport.json` の section 名と status
の語彙を固定している。MONICA 側が section や status を増やすと、
「この SDK が考慮していない契約が増えた」として落ちる。


## 利用側の設定

公開先は GitHub Packages（<https://github.com/Accel-Hack/monica-sdk-java/packages>）。
`0.1.0` は Maven Central にあるが、`0.1.1` 以降は GitHub Packages にしか出さない。

Maven registry は匿名で取得できないので、repository の宣言と token の両方が要る。

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Accel-Hack/monica-sdk-java</url>
  </repository>
</repositories>
```

手元から使う場合は credential を `gh` から借りる。**token を発行して置く方法は採らない。**
`~/.m2/settings.xml` には environment variable 名だけを書く。`<id>` は上の
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

値は build のたびに env から渡す。`gh auth token` に `read:packages` が要るので、
401 で取れないときは権限を足す。

```bash
gh auth refresh -s read:packages   # 権限が無いときだけ

export MONICA_PACKAGES_ACTOR="$(gh api user --jq .login)"
export MONICA_PACKAGES_TOKEN="$(gh auth token)"
mvn verify
```

### 別 repository の GitHub Actions から取る場合

job に `permissions: packages: read` を与えて、その repository の `GITHUB_TOKEN` を
渡す。

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

これで 401 / 403 になる場合は、**token を発行して利用側の secret に置くのではなく、
package 側から利用側 repository へ read を渡す。**
<https://github.com/orgs/Accel-Hack/packages> の `monica-core` → Package settings →
Manage Actions access → 利用側 repository を Read で追加する。相手の `GITHUB_TOKEN`
がそのまま通るようになる。

Gradle から取る場合も同じで、`maven { url = ... ; credentials { ... } }` へ
`GITHUB_ACTOR` と `GITHUB_TOKEN` を渡す。

## Release

開発中の POM は `X.Y.Z-SNAPSHOT` にする。`core` の `MonicaOptions.DEFAULT_SDK_VERSION`
は同じ版にする（契約テストが pom.xml と突き合わせる）。

対応する main commit へ `vX.Y.Z` tag を付けると
`.github/workflows/maven-release.yml` が動く。認証は workflow が受け取る
`GITHUB_TOKEN` で足りるので、公開のための repository secret は要らない。

workflow は tag と POM version の対応を検証し、release version へ一時変換してから、
parent pom と 3 module の jar を、それぞれの source / Javadoc jar とともに
GitHub Packages へ公開する。

同じ version は再公開できない（409 で落ちる）。公開済みの版を直すときは patch
version を上げる。手元から `mvn deploy` すると POM の `X.Y.Z-SNAPSHOT` がそのまま
上がってしまうので、公開は tag 経由の workflow に任せる。
