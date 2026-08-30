# llm-mock

Gemini / OpenAI / Anthropic / Amazon Bedrock の 4 つの LLM API を **同時に**エミュレートする、
テスト用の汎用モックサーバーです。Spring Boot 4.1 (Java 21) + H2 で実装しています。

テスト対象アプリケーションの SDK の base URL をこのサーバーに向けるだけで、実際の API を
呼ばずに、決定論的で、テストごとに自由に制御できる応答が返ります。

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
mvn spring-boot:run          # http://localhost:8080
mvn test                     # 94 テスト
mvn package                  # 実行可能 jar
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
| Gemini | `POST /v1beta/models/{model}:generateContent`、`:streamGenerateContent` (SSE / JSON 配列)、`:countTokens`、`:embedContent`、`GET /v1beta/models`、`GET /v1beta/models/{model}` |
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

## 7. 決定論について

テストで扱いやすいよう、次はすべて決定論的です。

- **既定の応答**はテンプレートから生成されるため、同じ入力なら常に同じ文字列。
- **トークン数**は 4 文字 ≒ 1 トークン + メッセージあたりの固定オーバーヘッド (`TokenCounter`)。
- **埋め込みベクトル**は入力のハッシュをシードにした正規化済み擬似乱数ベクトル
  (`EmbeddingGenerator`)。同じ入力なら常に同じベクトルが返ります。

一方、識別子 (`chatcmpl-...`、`msg_...`) と `created` タイムスタンプは毎回変わります。
実物と同じく、これらに依存したテストを書かせないためです。

---

## 8. テスト

```bash
mvn test     # 94 tests
```

| テスト | 対象 |
|---|---|
| `TextChunkerTest` `TokenCounterTest` `EmbeddingGeneratorTest` `MockRequestTest` | 中核ロジックの単体テスト |
| `EventStreamEncoderTest` | AWS event stream フレーミングを**独立した デコーダ**で往復検証 (CRC32 を含む) |
| `MockEngineTest` | 決定順序、優先度、使い切り、無効化、不正な正規表現、エラー再現、記録 |
| `OpenAiApiTest` `AnthropicApiTest` `GeminiApiTest` `BedrockApiTest` | 各社の仕様どおりのリクエスト受理／レスポンス形状／ストリーム／エラー封筒 |
| `AdminApiTest` | スタブ CRUD、記録の検索、リセット |
| `CrossProviderTest` | **1 つのスタブが 4 プロトコルすべてに効くこと**、逆にプロバイダー限定スタブが漏れないこと |

ストリーミングはレスポンスへ直接書き出す実装のため、非同期ディスパッチなしに素の MockMvc で
本文を検証できます。

---

## 9. 既知の制限

- **SigV4 は検証しません。** Bedrock では `Authorization` ヘッダーの有無しか見ません
  (`require-auth: true` の場合)。署名そのものの検証はモックの目的外です。
- **`anthropic-version` ヘッダーは必須にしていません。** 実 API は無いと 400 ですが、
  モックとしては寛容な方が使いやすいと判断しました。
- **画像・ドキュメント・音声などの非テキストパートは、プロンプト文字列には寄与しません。**
  受理はしますが、スタブ照合の対象になるのはテキストのみです。
- **`n > 1` (複数候補) は未対応**で、常に 1 候補を返します。
- 記録されるのは**リクエストの生バイト列と応答テキスト**で、応答の生 JSON ではありません。
  ストリーミングを本当に逐次配信するため、レスポンスをバッファリングしない設計にしています。
