# Ingest API (v1)

MONICA へ event を送る唯一の経路。

```http
POST /v1/envelope
Content-Type: application/json
Content-Encoding: gzip
```

## DSN

```
https://<api key>@<host>/<任意のパス>
```

- API キーは URL の user info に入れる。password 部分は使わない
- 送信先は DSN の origin に `/v1/envelope` を付けたもの。DSN のパス・クエリ・フラグメントは捨てる。DSN 末尾の数字を project id として送信先パスに使ってはいけない
- `https` 以外は `localhost` と `127.0.0.1` に限って許す

## 認証

| 鍵の種別 | 接頭辞 | ヘッダ | 使いどころ |
| --- | --- | --- | --- |
| secret | `msk_` | `Authorization: Bearer <key>` | サーバ SDK。配布物に埋め込まない |
| public | `mpk_` | `X-Monica-Key: <key>` | ブラウザとモバイル。DSN ごと配布される前提の書き込み専用キー |

鍵の種別とヘッダが食い違うと `401` になる。public key で読み取り API は叩けない。

送信先・ヘッダ・status ごとの挙動・リトライの定数は
[`transport.json`](./transport.json) にも同じ値がある。SDK
の契約テストはこの表を読んで定数を写すのではなく、そちらと突き合わせる。
`status` のキーは HTTP status の文字列で、`5xx` のように末尾の
`x` で範囲を表すものがある。個別のコードが無ければ範囲の方を引く。

## リクエスト

- body は envelope 1 通の JSON を gzip したもの。`Content-Encoding: gzip` を必ず付ける
- 1 リクエスト = 1 envelope。複数 envelope を連結して送らない
- 上限を超える分は SDK 側で envelope を分割する。分割の境界は item
- schema を持つ item の type は `error` と `client_report`。`client_report` は SDK の稼働確認に使い、送り方は `payload.md` にある。それ以外の type は受理したうえで捨てる
- `sdk.name` は配布 registry での package 名（`@ah-monica/core`、`com.accelhack.monica:monica-core` のように npm / Maven / Composer で公開している名前）、`sdk.version` はその package の版。どちらも空文字にしない。取り込み状況の集計単位になる

## 上限

| 対象 | 上限 |
| --- | --- |
| envelope（gzip 後） | 1 MiB（1048576 bytes） |
| envelope（展開後） | 8 MiB（8388608 bytes） |
| envelope あたりの item 数 | 100 |
| stacktrace あたりの frame 数 | 200 |

機械可読な値は [`limits.json`](./limits.json) にある。

## レスポンス

| コード | 意味 | SDK の挙動 |
| --- | --- | --- |
| `202` | 受理（処理はこれから） | キューから除去する |
| `400` | gzip / リクエスト形式の不正 | 破棄する。リトライしない |
| `401` | キー不正・失効 | 破棄し、以後の送信を止める |
| `413` | サイズ超過 | 分割して再送する |
| `422` | envelope schema 不正 | 破棄する。`issues` の path を見て payload を直す |
| `429` | レート制限 | `Retry-After` 秒だけ待ってから再送する |
| `5xx` | サーバ側の障害 | backoff してリトライする |

エラー時の body の形は [`error.json`](./error.json)。`422` だけが
field-level の `issues` を持つ。

```json
{
  "error": {
    "code": "invalid_envelope",
    "message": "The envelope does not match the MONICA schema",
    "issues": [
      { "path": "$.items[0].exception.values[0].mechanism.type", "message": "Invalid type: Expected ..." }
    ]
  }
}
```

`code` は人が読む用で、SDK の分岐は HTTP status で行う。新しい `code`
が増えることは互換性を壊す変更ではない。

## リトライ

- リトライするのは `429` と `5xx`、そしてネットワーク障害だけ。他の 4xx は恒久的な失敗として扱う
- `Retry-After` は整数秒だけ解釈する。HTTP-date は解釈せず backoff に落とす。上限は 60 秒
- backoff は `min(1000 * 2^attempt, 30000)` ミリ秒に 50〜100% の jitter を掛ける
- リトライ回数には上限を持たせ、尽きたら envelope を捨てる。無限に溜めない

## source map

```http
PUT /v1/sourcemaps?project=<project の slug>&release=<release>&file=<bundle のファイル>
Authorization: Bearer <GitHub Actions の OIDC トークン>
Content-Type: application/json
Content-Encoding: gzip
```

- ブラウザ（`platform: "javascript"`）と Cloudflare Workers（`platform: "cloudflare"`）の bundle の source map を、release ごと、bundle のファイルごとに上げる。MONICA はイベントの frame を元のファイルと行に戻してから Issue にまとめる（`grouping.md`）。SDK は関わらない
- 送信先は DSN の origin に `/v1/sourcemaps` を付けたもの
- `project` は管理画面のプロジェクトの slug。`release` はイベントに載せる `release` と同じ値にする。release の無いイベントには map を当てない
- `file` はイベントの frame の `filename` と同じファイルを指す。URL（`https://cdn.example.com/assets/index-BkX9a2Qz.js`）でもパス（`/assets/index-BkX9a2Qz.js`、`assets/index-BkX9a2Qz.js`）でもよい。scheme と host、クエリ、フラグメント、先頭の `/` を除いて照合する
- body は source map v3 の JSON 1 つ。`version` が `3`、`mappings` が文字列、`sources` が文字列の配列であること。index map（`sections` を持つもの）は受けない。gzip してよく、そのときは `Content-Encoding: gzip` を付ける
- `sourcesContent` は受けたときに除き、保存しない。元のソースコードは MONICA に残らない
- 同じ project、release、file に上げ直すと上書きする。map に保存期限は無い
- 上げた map と上げ直した map が効くまで最大 60 秒かかる。それより前に届いたイベントは bundle のまま Issue にまとまり、後からまとめ直さない。deploy の前に上げる

### 認証

- 認証は GitHub Actions の OIDC トークンだけ。API キーでは上げられず、GitHub Actions 以外の CI からは上げられない
- workflow に `permissions: id-token: write` を付け、audience に送信先の origin（例: `https://ingest.example.com`）を指定してトークンを取る（`@actions/core` の `getIDToken(audience)`、または `ACTIONS_ID_TOKEN_REQUEST_URL` に `&audience=` を付けて取る）。`Authorization: Bearer <token>` で送る
- トークンの `repository` が、そのプロジェクトの GitHub 連携で選んだリポジトリのどれかと一致すれば受ける。大文字と小文字は区別しない。branch、tag、workflow では絞らない

### 上限

| 対象 | 上限 |
| --- | --- |
| 展開した body（`sourcesContent` を含む） | 10 MiB（10485760 bytes） |
| `release`（UTF-8） | 200 bytes |
| `file`（UTF-8、照合に使う形にした後） | 1024 bytes |

### レスポンス

| コード | 意味 |
| --- | --- |
| `204` | 保存した |
| `400` | `project`、`release`、`file` のどれかが無いか空（`file` は照合に使う形にした後）、`release` か `file` が上限を超える、または gzip を展開できない |
| `401` | トークンが無い。署名、`iss`、`aud`、期限のどれかが合わない |
| `403` | トークンの `repository` がプロジェクトに連携されていない。プロジェクトが無いときも同じ応答になる |
| `413` | 展開した body が上限を超える。`sourcesContent` を除いてから上げる |
| `415` | `Content-Type` が `application/json` でない、または `Content-Encoding` が `gzip` 以外 |
| `422` | source map v3 でない、または index map |
| `5xx` | サーバ側の障害。間を置いて上げ直してよい |

エラー時の body の形は envelope と同じ [`error.json`](./error.json)。
