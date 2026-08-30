# llm-mock

[![CI](https://github.com/ykwyuta/llm-mock/actions/workflows/ci.yml/badge.svg)](https://github.com/ykwyuta/llm-mock/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)

Gemini / OpenAI / Anthropic / Amazon Bedrock の 4 つの LLM API を **同時に**エミュレートする、
テスト用の汎用モックサーバーです。Spring Boot 4.1 (Java 21) + H2 で実装しています。

テスト対象アプリケーションの SDK の base URL をこのサーバーに向けるだけで、実際の API を
呼ばずに、決定論的で、テストごとに自由に制御できる応答が返ります。

> **使い方だけを知りたい方は → [利用ガイド (docs/USAGE.md)](docs/USAGE.md)**
> 起動から接続、スタブの書き方、テストへの組み込み、記録／再生、コスト分析までを
> 手順とレシピで説明しています。この README は設計と仕様の解説です。

3 つの動作モードがあります。

| モード | 動作 |
|---|---|
| **`MOCK`** (既定) | スタブルールと既定テンプレートから応答を生成する |
| **`PROXY`** | **本物の API に転送**し、その応答をそのまま返しつつ**ファイルに記録**する |
| **`REPLAY`** | 記録したファイルから**バイト単位でそのまま**応答する |
| **`CACHED_PROXY`** | 記録があればそれを返し、無ければ**一度だけ**転送して記録する (インテリジェントプロキシ) |

つまり「一度だけ本物を叩いて記録 → 以降はそれをテストデータとして再生」という運用ができます
(→ [7. プロキシ／記録／再生モード](#7-プロキシ記録再生モード))。

また、プロキシした呼び出しについては**どのモデルがどれだけトークンを消費したか**を記録し、
コスト分析ができます (→ [8. トークン消費とコスト分析](#8-トークン消費とコスト分析))。

---

## 1. API 仕様の調査結果

4 社の「チャット補完」API は概念的にはほぼ同じですが、ワイヤーフォーマットは以下のように
すべて異なります。設計はこの差分表がそのまま出発点になっています。

| | **OpenAI** | **Anthropic** | **Gemini** | **Bedrock (Converse)** |
|---|---|---|---|---|
| 主エンドポイント | `POST /v1/chat/completions` | `POST /v1/messages` | `POST /v1beta/models/{model}:generateContent` | `POST /model/{modelId}/converse` |
| 認証 | `Authorization: Bearer` | `x-api-key` + `anthropic-version` | `x-goog-api-key` or `?key=` | AWS SigV4 (`Authorization`) |
| モデル指定 | ボディ `model` | ボディ `model` | **URL パス** | **URL パス** |
| 命名規則 | snake_case | snake_case | camelCase (snake_case も可) | camelCase |
| 会話 | `messages[].content` (文字列 or パート配列) | `messages[].content` + 独立した `system` | `contents[].parts[]` + `systemInstruction` | `messages[].content[]` + `system[]` |
| 生成パラメータ | トップレベル | トップレベル | `generationConfig` | `inferenceConfig` |
| `max_tokens` | 任意 | **必須** | 任意 (`maxOutputTokens`) | 任意 (`maxTokens`) |
| 応答本文 | `choices[0].message.content` | `content[]` (ブロック配列) | `candidates[0].content.parts[]` | `output.message.content[]` |
| 停止理由 | `stop` / `length` / `tool_calls` | `end_turn` / `max_tokens` / `tool_use` | `STOP` / `MAX_TOKENS` / `SAFETY` | `end_turn` / `max_tokens` / `tool_use` |
| トークン数 | `usage.prompt_tokens` / `completion_tokens` | `usage.input_tokens` / `output_tokens` | `usageMetadata.promptTokenCount` ほか | `usage.inputTokens` / `outputTokens` |
| ツール引数 | **JSON 文字列** (`function.arguments`) | **JSON オブジェクト** (`input`) | **JSON オブジェクト** (`args`) | **JSON オブジェクト** (`input`) |
| ストリーム形式 | SSE、イベント名なし、`data: [DONE]` で終端 | SSE、**イベント名あり** (`message_start` ほか) | SSE (`?alt=sse`) **または** JSON 配列 | **`application/vnd.amazon.eventstream`** (バイナリ) |
| エラー本文 | `{"error":{"message","type","param","code"}}` | `{"type":"error","error":{"type","message"}}` | `{"error":{"code","message","status"}}` | `{"message":...}` + `x-amzn-ErrorType` ヘッダー |

とくに実装上の負担が大きかったのは次の 3 点です。

1. **Bedrock のストリーミングはバイナリ**です。SSE ではなく AWS event stream フレーミング
   (prelude + ヘッダー + ペイロード + CRC32 × 2) なので、AWS SDK から使えるモックにするには
   このフレーム構造を正しく組み立てる必要があります → `EventStreamEncoder`。
2. **OpenAI と Anthropic はどちらも `GET /v1/models`** を、まったく異なるレスポンス形状で
   提供します。同一ポートで同居させるため、プロバイダーごとに URL プレフィックスを付けています。
3. **Bedrock の `InvokeModel`** は Converse と違い、モデル固有のネイティブ形式をそのまま
   受け渡します。そのためモデル ID からボディ形状を選び分けています → `BedrockFamily`。

---

## 2. 設計

### レイヤ構成

```
   HTTP (4 プロトコル)
        │
        ▼
┌───────────────────────────────────────────────┐
│ Provider Adapter                              │   provider/{openai,anthropic,gemini,bedrock}
│  各社の DTO ⇄ 正規化モデルの相互変換           │
│  ストリーム形式・エラー封筒もここで吸収         │
└───────────────────┬───────────────────────────┘
                    │  MockRequest (プロバイダー非依存)
                    ▼
┌───────────────────────────────────────────────┐
│ MockEngine                                    │   core/
│  スタブ照合 → 応答決定 → トークン計算 → 記録    │
└──────┬────────────────────────┬───────────────┘
       │ MockCompletion         │
       ▼                        ▼
  Provider Adapter        H2 (stub_rule / request_log)
                                ▲
                                │
                        Admin API (/__admin)
```

中核は **正規化モデル** (`MockRequest` / `MockCompletion`) です。4 つのアダプターはこの型に
変換するだけなので、スタブのルールは 1 つ書けば 4 プロトコルすべてに効きます。

### 応答の決定順序

すべてのフィールドについて、次の優先順位で決まります。

1. リクエストの **`X-Mock-*` ヘッダー** — そのリクエスト 1 回だけの上書き
2. マッチした **スタブルール** — `priority` 降順、同点なら登録が古い方
3. **既定のテンプレート** — `llm-mock.default-response-template`

この順序があるおかげで、スイート全体の共通スタブを崩さずに個別のテストだけ挙動を変えられます。

### なぜ H2 か

スタブと記録されたリクエストは、HTTP リクエストをまたいで残る必要があります (テストが
スタブを登録 → アプリが呼ぶ → テストが検証、という流れのため)。JPA + H2 のインメモリ DB に
置くことで、クエリ (`/__admin/requests?provider=...`) と件数上限による自動剪定が素直に書けます。

---

## 3. 起動と接続

```bash
./mvnw spring-boot:run       # http://localhost:8080
./mvnw test                  # 207 テスト
./mvnw package               # 実行可能 jar
```

プロバイダーごとに URL プレフィックスが付きます (`llm-mock.paths.*` で変更可)。

| SDK | base URL に設定する値 |
|---|---|
| OpenAI (`openai` python / node) | `http://localhost:8080/openai/v1` |
| Anthropic (`anthropic`) | `http://localhost:8080/anthropic` |
| Google GenAI (Gemini) | `http://localhost:8080/gemini` |
| AWS SDK (Bedrock Runtime `endpoint_url`) | `http://localhost:8080/bedrock` |

```python
# 例: OpenAI Python SDK
client = OpenAI(base_url="http://localhost:8080/openai/v1", api_key="dummy")
```

### 実装済みエンドポイント

| プロバイダー | エンドポイント |
|---|---|
| OpenAI | `POST /v1/chat/completions` (SSE 対応)、`POST /v1/completions`、`POST /v1/embeddings`、`GET /v1/models`、`GET /v1/models/{id}` |
| Anthropic | `POST /v1/messages` (SSE 対応)、`POST /v1/messages/count_tokens`、`GET /v1/models` |
| Gemini | `POST /v1beta/models/{model}:generateContent`、`:streamGenerateContent` (SSE / JSON 配列)、`:countTokens`、`:embedContent`、`:batchEmbedContents`、`GET /v1beta/models`、`GET /v1beta/models/{model}` |
| Bedrock | `POST /model/{id}/converse`、`/converse-stream`、`/invoke`、`/invoke-with-response-stream` |

`InvokeModel` は `anthropic.*` / `amazon.titan-text*` / `amazon.nova*` / `meta.llama*` の
ネイティブ形式に対応しています (未知のモデル ID は Anthropic 形式)。

---

## 4. スタブの登録 (Admin API)

`/__admin` 配下は、どのプロバイダープレフィックスとも衝突しない管理面です。

```bash
# プロンプトに "weather" を含む呼び出しに、全プロバイダー共通で応答を固定する
curl -X POST http://localhost:8080/__admin/stubs -H 'content-type: application/json' -d '{
  "name": "weather",
  "provider": "ANY",
  "promptPattern": "(?i)weather",
  "responseText": "It is sunny in Tokyo.",
  "priority": 10
}'

# アプリが実際に送った内容を検証する
curl 'http://localhost:8080/__admin/requests?provider=OPENAI&limit=5'

# テストの @BeforeEach で状態をリセットする
curl -X POST http://localhost:8080/__admin/reset
```

| メソッド | パス | 用途 |
|---|---|---|
| `GET` | `/__admin/health` | 稼働確認とスタブ／記録の件数 |
| `GET` `POST` | `/__admin/stubs` | 一覧 / 登録 (同名は置換) |
| `GET` `PUT` `DELETE` | `/__admin/stubs/{id}` | 個別の取得・更新・削除 |
| `DELETE` | `/__admin/stubs` | 全スタブ削除 |
| `GET` | `/__admin/requests` | 記録の検索 (`provider` `model` `endpoint` `limit`) |
| `DELETE` | `/__admin/requests` | 記録の全削除 |
| `POST` | `/__admin/reset` | スタブ＋記録の全削除 |
| `GET` | `/__admin/usage` | トークン消費の明細 (`provider` `model` `source` `limit`) |
| `GET` | `/__admin/usage/summary` | **コスト集計** (`provider` `source`) |
| `DELETE` | `/__admin/usage` | 消費記録の全削除 |
| `GET` | `/__admin/recordings` | プロキシ記録の一覧 |
| `GET` | `/__admin/recordings/{key}` | 記録 1 件の全内容 |
| `POST` | `/__admin/recordings/reload` | 記録ディレクトリの再スキャン |
| `DELETE` | `/__admin/recordings` | 記録の全削除 |
| — | `/__admin/h2` | H2 コンソール |

### スタブルールの主なフィールド

| フィールド | 意味 |
|---|---|
| `name` | 一意な名前。`X-Mock-Stub` からの指名にも使う |
| `provider` | `ANY` / `OPENAI` / `ANTHROPIC` / `GEMINI` / `BEDROCK` |
| `modelPattern` `promptPattern` `endpointPattern` | 正規表現 (部分一致)。`null` は「何にでもマッチ」 |
| `priority` | 大きいほど優先。同点なら古いルールが勝つ |
| `responseText` | 返す本文 |
| `toolName` / `toolArguments` | ツール呼び出しを返す (引数は JSON 文字列で指定) |
| `httpStatus` / `errorType` / `errorMessage` | 400 以上なら、各社のエラー形式で失敗を再現する |
| `delayMs` | 応答前に待つ時間。タイムアウト試験用 |
| `remainingUses` | 使い切りルール。`null` なら無制限 |
| `enabled` | 無効化 (削除せず一時停止) |

照合は「`null` でない条件がすべて一致したもの」が対象です。正規表現は登録時に検証され、
不正なパターンは 400 で弾かれます。

---

## 5. `X-Mock-*` ヘッダーによる 1 回限りの上書き

スタブを登録するまでもない場合は、リクエストヘッダーだけで挙動を変えられます。

| ヘッダー | 効果 |
|---|---|
| `X-Mock-Text` | 応答本文。**空文字を明示すると本文なし** (ツール呼び出しのみ) |
| `X-Mock-Finish-Reason` | `stop` / `length` / `tool_use` / `content_filter` / `stop_sequence` |
| `X-Mock-Status` | 400 以上を指定するとその HTTP ステータスで失敗させる |
| `X-Mock-Error-Type` / `X-Mock-Error-Message` | 上記失敗の内容 |
| `X-Mock-Delay-Ms` | 応答前の待機時間 |
| `X-Mock-Tool-Name` / `X-Mock-Tool-Arguments` | ツール呼び出しを返す |
| `X-Mock-Input-Tokens` / `X-Mock-Output-Tokens` | 報告するトークン数を固定する |
| `X-Mock-Stub` | 照合を飛ばして名前でスタブを指名する |

```bash
curl -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' \
  -H 'X-Mock-Status: 429' \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}'
# → {"error":{"message":"...","type":"rate_limit_error","code":"rate_limit_exceeded"}}
```

---

## 6. 設定 (`application.yml`)

```yaml
llm-mock:
  mode: MOCK                   # MOCK | PROXY | REPLAY (→ 7 章)
  provider-modes:              # プロバイダーごとの上書き (任意)
    openai: MOCK
  proxy:
    recordings-dir: ./recordings
    record: true               # PROXY 時に記録ファイルを書くか
    targets: {}                # プロバイダー → 上流ベース URL
    headers: {}                # プロバイダー → 転送時に付与するヘッダー
    redact-headers: [authorization, x-api-key, x-goog-api-key, ...]
    redact-query-params: [key, access_token]
    sigv4: {}                  # AWS SigV4 の付け直し (→ 7 章)
    cache:
      ttl:                     # CACHED_PROXY で記録が陳腐化するまでの時間 (未設定 = 無期限)
      source-header: true      # X-Llm-Mock-Source ヘッダーを付ける
  cost:                        # トークン消費とコスト分析 (→ 8 章)
    enabled: true
    currency: USD
    max-entries: 1000000       # 明細の保持件数
    pricing: []                # 100 万トークンあたりの単価。既定は空
    connect-timeout: 10s
    request-timeout: 120s
  replay:
    fallback: MOCK             # MOCK | NOT_FOUND
  paths:                       # プロバイダーごとの URL プレフィックス
    openai: /openai
    anthropic: /anthropic
    gemini: /gemini
    bedrock: /bedrock
  default-response-template: "[llm-mock] echo: {{prompt}}"
  require-auth: false          # true にすると資格情報ヘッダーが無い場合 401
  stream:
    words-per-chunk: 3         # ストリーミング 1 チャンクあたりの単語数
    delay-ms: 0                # チャンク間の待機時間
  recording:
    enabled: true
    max-entries: 1000          # 超過分は古いものから自動削除
    capture-request-body: true
    max-body-bytes: 262144
  models: { openai: [...], anthropic: [...], gemini: [...], bedrock: [...] }
  embedding:
    openai-dimensions: 1536
    gemini-dimensions: 768
```

テンプレートでは `{{prompt}}` (直近の user 発話)、`{{model}}`、`{{provider}}`、
`{{messageCount}}` が使えます。

---

## 7. プロキシ／記録／再生モード

「本物の応答をテストデータにしたい」ための機能です。

```
 [MOCK]    アプリ ──▶ llm-mock ──▶ スタブエンジン

 [PROXY]   アプリ ──▶ llm-mock ──▶ 本物の API
                          │
                          └──▶ recordings/*.json  (記録)

 [REPLAY]  アプリ ──▶ llm-mock ──▶ recordings/*.json  (本物は不要)
```

### 使い方

**手順 1: 本物を叩いて記録する**

```yaml
llm-mock:
  mode: PROXY
  proxy:
    recordings-dir: ./recordings
    targets:
      openai: https://api.openai.com
    headers:
      openai:
        Authorization: "Bearer ${OPENAI_API_KEY}"   # 本物の鍵はここで注入
```

アプリ側の設定は一切変えません。base URL は llm-mock のままで、
呼び出しはそのまま本物へ転送され、応答が `./recordings/*.json` に書き出されます。

**手順 2: 以降は記録を再生する**

```yaml
llm-mock:
  mode: REPLAY
  proxy:
    recordings-dir: ./recordings
  replay:
    fallback: NOT_FOUND    # 記録が無ければ 404。MOCK ならスタブエンジンにフォールバック
```

本物の API も資格情報も一切不要になり、応答は**記録したバイト列そのまま**返ります。
ストリーミング (SSE / Bedrock のバイナリ event stream) も含めて完全に同一です。

### 記録ファイル

1 リクエスト 1 ファイルの素の JSON です。レビューでき、手で編集もできます。

```json
{
  "key" : "38ba99d9d6a28483",
  "provider" : "OPENAI",
  "recordedAt" : "2026-08-30T11:25:42.395680758Z",
  "request" : {
    "method" : "POST",
    "path" : "/v1/chat/completions",
    "headers" : { "Authorization" : [ "REDACTED" ], "content-type" : [ "application/json" ] },
    "body" : "{\"model\":\"gpt-4o\",\"messages\":[...]}"
  },
  "response" : {
    "status" : 200,
    "headers" : { "content-type" : [ "application/json" ] },
    "body" : "{\"id\":\"chatcmpl-...\",...}"
  }
}
```

- ファイル名は `{provider}__{path}__{key}.json`。
- 本文はテキストの content-type ならそのまま、バイナリ (Bedrock の event stream) なら
  `bodyBase64` に入ります。
- **資格情報は記録されません。** `Authorization` / `x-api-key` / `x-goog-api-key` などの
  ヘッダーと、`?key=` などのクエリパラメータは `REDACTED` に置換されます
  (`proxy.redact-headers` / `proxy.redact-query-params` で調整可)。記録はリポジトリに
  コミットする前提なので、これは既定で有効です。

### 照合キー

再生時にどの記録を返すかは **provider・メソッド・パス・クエリ・リクエストボディ**の
ハッシュで決まります。**ヘッダーは含めません** — 記録時と別の API キーで呼んでも同じ記録に
当たる必要があるためです。クエリも、redact 対象のパラメータは除外し、残りは並べ替えてから
ハッシュします (`?key=` が違っても、パラメータ順が違っても同じ記録に当たる)。
一方 `alt=sse` のように**応答形式を変えるパラメータは効きます**。

### プロバイダーごとにモードを変える

```yaml
llm-mock:
  mode: MOCK
  provider-modes:
    openai: PROXY      # OpenAI だけ本物を叩いて記録し、他はモックのまま
```

### インテリジェントプロキシ (`CACHED_PROXY`)

`PROXY` と `REPLAY` を 1 モードにまとめたものです。

```
 リクエスト ──▶ 記録あり? ──yes──▶ 記録から応答 (上流を呼ばない)
                    │
                    no
                    ▼
              上流を 1 回だけ呼ぶ ──▶ 応答を返す + 記録する
```

```yaml
llm-mock:
  mode: CACHED_PROXY
  proxy:
    recordings-dir: ./recordings
    targets:
      openai: https://api.openai.com
    cache:
      ttl: 24h        # 未設定なら記録は無期限に有効
```

**同じリクエストが 2 回来たら 2 回目はキャッシュ**から返るので、上流は呼ばれず、
料金も発生せず、応答も即座です。応答には `X-Llm-Mock-Source` ヘッダーが付き、
`upstream` / `cache` / `recording` のどれで応じたかが分かります。

```
$ curl -D- -X POST .../openai/v1/chat/completions -d '{...}'
X-Llm-Mock-Source: upstream      ← 1 回目。本物を呼んだ
$ curl -D- -X POST .../openai/v1/chat/completions -d '{...}'
X-Llm-Mock-Source: cache         ← 2 回目。記録から返した
```

記録はファイルに残るので、**次回の実行では 1 回目からキャッシュヒット**します。
テストスイートを回すたびにフィクスチャが揃っていき、以降は無料かつオフラインで回せる、
という運用になります。`ttl` を設定すれば、古くなった記録だけを取り直せます。

### AWS SigV4 の付け直し (Bedrock)

Bedrock だけは、他の 3 社のように「`Authorization` ヘッダーをそのまま転送する」ことが
**できません**。SigV4 署名は `Host` ヘッダー・パス・クエリ・ボディを対象に計算されるため、
アプリが llm-mock 向けに作った署名は、本物のエンドポイントでは必ず無効になります。
また通常、アプリ側が持っているのはダミーの鍵です。

そこで **転送前に署名を付け直します**。

```yaml
llm-mock:
  mode: PROXY
  proxy:
    targets:
      bedrock: https://bedrock-runtime.us-east-1.amazonaws.com
    sigv4:
      bedrock:
        enabled: true
        # region  : 未指定ならターゲットのホスト名から抽出 (bedrock-runtime.us-east-1... → us-east-1)、
        #           それも駄目なら AWS の標準リージョン解決チェーン
        # service : 未指定なら "bedrock" (ホストは bedrock-runtime.* でも署名名は bedrock)
        # 資格情報: 未指定なら AWS の標準プロバイダチェーン
        #           (環境変数・プロファイル・インスタンスロールがそのまま使えます)
        access-key-id: "${AWS_ACCESS_KEY_ID}"
        secret-access-key: "${AWS_SECRET_ACCESS_KEY}"
```

これにより、**アプリ側はダミーの鍵のまま**で本物の Bedrock を呼べます。本物の資格情報は
llm-mock の設定内にとどまり、アプリの設定を触る必要はありません。

動作は次のとおりです。

- 呼び出し側の `Authorization` / `X-Amz-Date` / `X-Amz-Content-Sha256` /
  `X-Amz-Security-Token` などは**破棄**されます。別ホスト向けの署名であり、
  新しい署名を汚すだけだからです。
- **リクエスト URI は署名器が返したものを使います。** 送信するバイト列が署名対象と
  食い違うことが原理的に起こらないようにするためです。
- 一時的な資格情報 (`session-token`) にも対応しています。
- 署名処理そのものは **AWS SDK に委譲**しています。SigV4 で自作実装が壊れるのは
  正規化 (canonicalization) の部分で、とくに **Bedrock のモデル ID は URL パスに `:` を
  含み**、正規化 URI ではワイヤー上のエンコードにさらにもう一段エンコードが掛かる、
  という規則があるためです。

`enabled: false` (既定) の場合は、`Authorization` はそのまま転送されます。
モックを別のモックにプロキシする場合など、署名が不要なケースのためです。

### 実装上の注意

- プロキシ／再生は**サーブレットフィルタ**で実装しています。4 つのコントローラに手を入れて
  いないので、**バイト列をそのまま流すだけ**で済み、ストリーミングも含めた全エンドポイントが
  自動的に対象になります。新しいエンドポイントを追加しても、プロキシ対応の追加作業は不要です。
- `X-Mock-*` ヘッダーは**転送されません**。これは llm-mock 自身への制御ヘッダーであり、
  本物の API にとっては意味のないノイズだからです。
- `Accept-Encoding` は `identity` に置き換えます。記録が読める形で残るようにするためです。
- `/__admin` は、どのモードでも常にローカルで処理されます。
- プレフィックスを空にしてルート直下にマウントしたプロバイダーは、パスから判別できないため
  プロキシ対象外です。

## 8. トークン消費とコスト分析

プロキシした呼び出しについて、**どのモデルがどれだけトークンを消費したか**を記録します。
プロキシモードでは応答を作るのは上流なので、トークン数が存在する唯一の場所は
**応答ボディ**です。4 社とも表記が違い、ストリーミングでは複数イベントに分かれるため、
形式ごとに個別に解釈しています。

| プロバイダー | 非ストリーミング | ストリーミング |
|---|---|---|
| OpenAI | `usage.prompt_tokens` / `completion_tokens` | 最終チャンクの `usage` (`stream_options.include_usage` 指定時のみ) |
| Anthropic | `usage.input_tokens` / `output_tokens` | `message_start` に入力、`message_delta` に出力 |
| Gemini | `usageMetadata.promptTokenCount` ほか | 最終チャンクの `usageMetadata` |
| Bedrock | `usage.inputTokens` / `outputTokens` | `metadata` イベント (バイナリ event stream をデコード) |

Bedrock の `InvokeModel` はモデル固有形式なので、anthropic / titan / nova / llama
それぞれの表記に対応しています。プロンプトキャッシュのトークン
(`cache_read_input_tokens` / `cachedContentTokenCount` など) も別枠で記録します。

### 単価の設定

```yaml
llm-mock:
  cost:
    currency: USD
    pricing:                        # 100 万トークンあたりの単価。上から順に最初に一致したものを使う
      - model-pattern: "^gpt-4o$"
        input: 2.50
        output: 10.00
        cache-read: 1.25
      - model-pattern: "^claude-sonnet"
        input: 3.00
        output: 15.00
```

**単価は既定で空です。** ベンダーの価格は変わるので、古い数値を埋め込むと
「自信たっぷりに間違った合計」を黙って出すことになり、合計が出ないより有害だからです。
単価が無いモデルもトークン数は集計され、`unpricedModels` に名前が出ます。

### 集計を見る

```bash
curl 'http://localhost:8080/__admin/usage/summary'
```

```json
{
  "currency": "USD",
  "byModel": [
    { "provider": "OPENAI", "model": "gpt-4o", "requests": 2,
      "inputTokens": 22, "outputTokens": 26, "totalTokens": 48,
      "cost": 0.000315, "priced": true },
    { "provider": "ANTHROPIC", "model": "claude-sonnet-4-5", "requests": 1,
      "inputTokens": 13, "outputTokens": 15, "totalTokens": 28,
      "cost": 0.000264, "priced": true }
  ],
  "totals": {
    "requests": 3, "totalTokens": 76,
    "cost": 0.000579,
    "upstreamCost": 0.0004215,     ← 実際に発生した費用
    "cacheSavings": 0.0001575,     ← キャッシュヒットが節約した分
    "upstreamRequests": 2, "cacheHits": 1
  },
  "unpricedModels": []
}
```

`source` で発生源を区別できます。

| `source` | 意味 |
|---|---|
| `UPSTREAM` | 本物の API を呼んだ。**実際に発生した費用** |
| `CACHE` | `CACHED_PROXY` のキャッシュヒット。**節約できた費用** |
| `RECORDING` | `REPLAY` での再生。オフラインのテストデータ |
| `MOCK` | スタブエンジンが生成。トークン数は合成値 |

```bash
# 実際に課金された分だけ見る
curl 'http://localhost:8080/__admin/usage/summary?source=UPSTREAM'
# モデル別の明細
curl 'http://localhost:8080/__admin/usage?model=gpt-4o&limit=20'
```

キャッシュヒットのトークンも記録するのは、**キャッシュがいくら節約したかを見るため**です。
モックモードのトラフィックも記録しますが `source=MOCK` で区別されるので、
実費の集計からは除外できます。

### 明細の保持件数

既定で **100 万件**です (`llm-mock.cost.max-entries`)。超えた分は古い明細から削除されます。

剪定も集計も**データベース側で実行**しています (剪定は id 範囲から件数を求めて一括 DELETE、
集計は `group by` クエリ)。全行をメモリに読み出さないので、テーブルが大きくなっても
**挿入スループットも集計時間も劣化しません**。20,000 件を投入した実測では、
最初の 1,000 件が 226 req/s、以降の定常が 833 req/s (劣化なし)、その状態での
`/usage/summary` が約 100ms でした。

コストはメモリです。1 行あたり概ね 150 バイト程度なので、100 万件でおよそ 150MB を見込んでください。
インメモリ H2 なのでプロセスのヒープに乗ります。長時間動かす場合は `-Xmx` を確認するか、
`max-entries` を下げてください。

> **注意:** 入力トークンのうちキャッシュ読み出し分は `cache-read` 単価で計算し、
> 通常の入力単価では二重計上しません。

## 9. 決定論について

テストで扱いやすいよう、次はすべて決定論的です。

- **既定の応答**はテンプレートから生成されるため、同じ入力なら常に同じ文字列。
- **トークン数**は 4 文字 ≒ 1 トークン + メッセージあたりの固定オーバーヘッド (`TokenCounter`)。
- **埋め込みベクトル**は入力のハッシュをシードにした正規化済み擬似乱数ベクトル
  (`EmbeddingGenerator`)。同じ入力なら常に同じベクトルが返ります。

一方、識別子 (`chatcmpl-...`、`msg_...`) と `created` タイムスタンプは毎回変わります。
実物と同じく、これらに依存したテストを書かせないためです。

---

## 10. テスト

```bash
./mvnw test     # 207 tests
```

テストは 2 段構えです。

### 10.1 HTTP レベル (仕様どおりか)

| テスト | 対象 |
|---|---|
| `TextChunkerTest` `TokenCounterTest` `EmbeddingGeneratorTest` `MockRequestTest` | 中核ロジックの単体テスト |
| `EventStreamEncoderTest` | AWS event stream フレーミングを**独立したデコーダ**で往復検証 (CRC32 を含む) |
| `MockEngineTest` | 決定順序、優先度、使い切り、無効化、不正な正規表現、エラー再現、記録 |
| `OpenAiApiTest` `AnthropicApiTest` `GeminiApiTest` `BedrockApiTest` | 各社の仕様どおりのリクエスト受理／レスポンス形状／ストリーム／エラー封筒 |
| `AdminApiTest` | スタブ CRUD、記録の検索、リセット |
| `CrossProviderTest` | **1 つのスタブが 4 プロトコルすべてに効くこと**、逆にプロバイダー限定スタブが漏れないこと |
| `RecordingKeyTest` | 照合キーの導出 (何が効いて何が効かないか)、ファイル名の生成 |
| `SigV4SignerTest` | SigV4 署名を**独立実装の検証器**で検算 (`:` を含むパス、クエリ、一時資格情報を含む) |
| `UsageExtractorTest` | 4 社 × 非ストリーミング／ストリーミング／Bedrock の各ネイティブ形式からのトークン数抽出 |

ストリーミングはレスポンスへ直接書き出す実装のため、非同期ディスパッチなしに素の MockMvc で
本文を検証できます。

### 10.2 SDK レベル (**本物のクライアントが動くか**)

HTTP レベルのテストが保証するのは「**こちらのドキュメント解釈どおりに動くこと**」だけで、
実際の SDK が要求するのに実装し忘れたエンドポイントやフィールドは検出できません。
そこで、4 社の**公式 Java SDK をテストスコープで依存に加え**、ランダムポートで起動した
実サーバーに対して実際に呼ばせています。

| テスト | 使用する公式 SDK |
|---|---|
| `OpenAiSdkTest` | `com.openai:openai-java` |
| `AnthropicSdkTest` | `com.anthropic:anthropic-java` |
| `GeminiSdkTest` | `com.google.genai:google-genai` |
| `BedrockSdkTest` | `software.amazon.awssdk:bedrockruntime` (同期＋非同期) |

各 SDK について、非ストリーミング応答、**SDK 自身のパーサによるストリーム復元**、
ツール呼び出し、エラーの例外型へのマッピング (`RateLimitException` など)、モデル一覧、
埋め込み、トークン数、そして **SDK が実際に送信したリクエスト本文が記録されること**を
検証しています。

とくに Bedrock は、AWS SDK の**本物の event stream デコーダ**が `converseStream` と
`invokeModelWithResponseStream` の両方を読めることを確認しています。バイナリフレーミングの
検証としてはこれが最も確実です。

> **このテストで実際にバグが 1 件見つかりました。** 公式 SDK の `embedContent` は内部的に
> `:batchEmbedContents` を呼びますが、これを実装していなかったため 405 を返していました。
> HTTP レベルのテストは (こちらが実装した `:embedContent` だけを叩いていたので) 全て緑の
> ままでした。現在は `:batchEmbedContents` を実装し、両レベルでテストしています。

なお、SDK のリトライは無効化しています。テストを遅くするうえ、後続の成功で本当の失敗が
隠れてしまうためです。

### 10.3 プロキシ／再生 (`ProxyModeTest`)

プロキシ機能のテストで**本物の API を叩くわけにはいかない**ので、
**このアプリケーション自身のもう 1 インスタンスを「本物の上流 API」役**として使っています。

```
 [upstream インスタンス]   MOCK モード。応答テンプレートを "[upstream] answered: ..." にして
                          プロキシ自身の応答と区別できるようにしてある
        ▲
        │ 実際の HTTP ホップ
        │
 [proxy インスタンス]      PROXY モード。target = upstream の URL、recordings-dir = @TempDir
        ▲
        │
      テスト
```

ネットワークも資格情報もレート制限も要らないまま、**実際の HTTP ホップ・実際のストリーミング・
実際のディスク上のファイル**を通ります。検証内容は次のとおりです。

- プロキシが (自分のテンプレートではなく) **上流の応答**を返すこと
- 記録ファイルが書かれ、**資格情報が含まれないこと**
- **上流とプロキシを完全に停止した上で**、REPLAY インスタンスが同じ応答を返すこと
- SSE ストリームと **Bedrock のバイナリ event stream がバイト単位で一致**すること
  (CRC32 を検証するデコーダで確認)
- 照合がパスだけでなく**ボディにも効く**こと、逆に**呼び出し側の API キーには効かない**こと
- エラー応答 (429 とそのエラー封筒) も記録・再生されること
- `fallback` が `MOCK` ならスタブエンジンに、`NOT_FOUND` なら 404 になること
- プロバイダーごとのモード切り替え
- **公式 OpenAI SDK が、プロキシ経由でも再生でも同じ応答を得ること**
  (id が一致する = 生成ではなく記録が返っている証拠)。ストリーミングも同様

### 10.4 SigV4 の付け直し (`SigV4SignerTest` / `SigV4ProxyTest`)

署名の正しさは「動いたように見える」では確かめられないので、**署名仕様から起こした独立実装の
検証器** (`SigV4Verifier`) を用意し、本物の AWS エンドポイントがやるのと同じ手順
— 受け取ったリクエストと `SignedHeaders` から正規リクエストを組み直し、共有鍵で署名を
再計算して突き合わせる — で検算しています。署名の生成は AWS SDK、検証は独立実装、という
2 実装が一致することに意味があります。

`SigV4ProxyTest` は上流役のモックインスタンスに**テスト専用のキャプチャフィルタ**を足して
(モック本体はリクエストヘッダーを記録しません。資格情報をログに書くことになるためです)、
上流が実際に受け取ったリクエストに対して署名を検証します。

- 付け直した署名が**上流のホスト・パス・クエリ・ボディに対して有効**であること
- 呼び出し側の署名が**転送されずに置き換えられている**こと
- **本物の AWS SDK** がモック向けに署名した呼び出しを、プロキシが上流向けに署名し直せること
  (アプリ側はダミーの鍵のまま、という実運用の形)
- ボディを 1 バイト変えると検証が**落ちる**こと (検証器が実際に検算している証拠)
- 署名を無効にすれば `Authorization` がそのまま転送されること

### 10.5 インテリジェントプロキシとコスト分析 (`CachedProxyTest` / `CostAnalysisTest`)

上流役がモックインスタンスであることがここで効きます。**上流自身のリクエストログが
「実際に何回呼ばれたか」の正確なカウンタ**になるので、キャッシュヒットが本当に上流へ
行っていないことを証明できます。

- 2 回目の同一リクエストが `X-Llm-Mock-Source: cache` で返り、**上流の呼び出し回数が
  増えていない**こと
- 上流を停止してもキャッシュヒットは応答できること
- TTL 経過後は取り直し、TTL 内ならヒットすること
- SSE と Bedrock のバイナリストリームもバイト単位でキャッシュされること
- **前回の実行が残した記録が、次回は 1 回目からヒットする**こと (このモードの目的そのもの)
- 単価設定からのコスト計算が、明細のトークン数と一致すること
- キャッシュヒットが `cacheSavings` に、実呼び出しが `upstreamCost` に分かれること
- 単価未設定のモデルはトークンだけ集計され、コストは `null` かつ `unpricedModels` に載ること

## 11. 既知の制限

- **SigV4 は検証しません。** Bedrock では `Authorization` ヘッダーの有無しか見ません
  (`require-auth: true` の場合)。署名そのものの検証はモックの目的外です。
- **`anthropic-version` ヘッダーは必須にしていません。** 実 API は無いと 400 ですが、
  モックとしては寛容な方が使いやすいと判断しました。
- **画像・ドキュメント・音声などの非テキストパートは、プロンプト文字列には寄与しません。**
  受理はしますが、スタブ照合の対象になるのはテキストのみです。
- **`n > 1` (複数候補) は未対応**で、常に 1 候補を返します。
- 記録されるのは**リクエストの生バイト列と応答テキスト**で、応答の生 JSON ではありません。
  ストリーミングを本当に逐次配信するため、レスポンスをバッファリングしない設計にしています。
- 公式 SDK の検証は **Java SDK に対してのみ**行っています。Python / TypeScript / Go の SDK は
  同じ HTTP 仕様に従うため動作するはずですが、実際に検証したわけではありません。
- **プロキシ機能は、本物のベンダー API に対しては検証していません。** 上流役として本アプリの
  別インスタンスを使っています (意図的にそうしています)。転送自体は素の HTTP なので問題ない
  はずですが、実際のベンダー固有の挙動 (SigV4 署名の再計算が必要なケースなど) は未検証です。
  Bedrock の SigV4 付け直しは実装済み (→ 7 章) で、上流役のモックに対して検証していますが、
  **本物の AWS エンドポイントに対しては未検証**です。
- **コスト集計は `max-entries` (既定 100 万件) を超えると古い明細から削除される**ため、
  それを超えて動かした場合の合計は下振れします。恒久的な課金台帳ではなく、テスト実行単位の
  分析用と考えてください。100 万件でおよそ 150MB のヒープを消費します。
- **単価表は同梱していません。** 実際の請求額と突き合わせるには自分で設定する必要があります。
- 記録は 1 リクエスト 1 ファイルで、**同一リクエストに対する複数の異なる応答**
  (呼ぶたびに違う応答を返すシナリオ) は表現できません。それが必要な場合は、
  記録ではなく `remainingUses` 付きのスタブルールを使ってください。

---

## 12. 貢献

バグ報告・機能提案・プルリクエストを歓迎します。
ビルド方法、テストの置き場所、よくある変更の手順は
[CONTRIBUTING.md](CONTRIBUTING.md) にまとめてあります。
参加にあたっては [行動規範](CODE_OF_CONDUCT.md) に同意したものとみなします。

テストは**本物の API を一切呼びません** (プロキシ機能のテストは、このアプリのもう 1
インスタンスを上流役として使っています)。API キーも AWS の資格情報も無しで、
`./mvnw test` だけで全部通ります。

セキュリティ上の注意と脆弱性の報告方法は [SECURITY.md](SECURITY.md) を参照してください。
とくに、**このサーバーは公開ネットワークに置かない**でください
(管理 API に認証がなく、H2 コンソールが既定で有効です)。

---

## 13. ライセンス

[MIT License](LICENSE) です。Copyright (c) 2026 ykwyuta。

### 依存ライブラリ

同梱・配布されるのは実行時依存だけです。テスト用の 4 社の公式 SDK は
`scope=test` なので、ビルドされた jar には含まれません。

| ライブラリ | スコープ | ライセンス |
|---|---|---|
| Spring Boot / Spring Framework | runtime | Apache-2.0 |
| Jackson | runtime | Apache-2.0 |
| H2 Database | runtime | **MPL-2.0 / EPL-1.0 (デュアル)** |
| AWS SDK for Java v2 (`http-auth-aws` / `auth` / `regions`) | runtime | Apache-2.0 |
| JUnit 5 | test | EPL-2.0 |
| `com.openai:openai-java` | test | Apache-2.0 |
| `com.anthropic:anthropic-java` | test | MIT |
| `com.google.genai:google-genai` | test | Apache-2.0 |
| `software.amazon.awssdk:bedrockruntime` ほか | test | Apache-2.0 |

> **H2 のみコピーレフト系 (MPL-2.0) です。** MPL-2.0 はファイル単位のコピーレフトなので、
> 改変せずにそのまま同梱する分には問題ありませんが、実行可能 jar
> (`./mvnw package` が作る fat jar) を再配布する場合は、H2 のライセンス条項に従ってください。
> H2 を使わずに別の DataSource に差し替えることもできます。

llm-mock は、4 社の API の**ワイヤーフォーマットを公開仕様から実装**したものです。
ベンダーのコードは含んでおらず、各社とは無関係の非公式なプロジェクトです。
"OpenAI"、"Anthropic"、"Claude"、"Gemini"、"Amazon Bedrock" は各社の商標です。
