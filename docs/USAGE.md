# llm-mock 利用ガイド

llm-mock は、**Gemini / OpenAI / Anthropic / Amazon Bedrock の 4 つの LLM API を 1 つのポートで
同時にエミュレートする**テスト用サーバーです。アプリケーションの SDK の base URL をこのサーバーに
向けるだけで、本物の API を呼ばずに、決定論的で自由に制御できる応答が返ります。

このガイドは**使い方**だけを扱います。「なぜそう作ったか」「4 社の仕様差」「テスト戦略」といった
設計の話は [README.md](../README.md) を参照してください。

> **はじめての方へ:** [2. 5 分ではじめる](#2-5-分ではじめる) →
> [4. アプリから接続する](#4-アプリケーションから接続する) →
> [8. スタブルール](#8-方法-b-スタブルールで応答を決める) の順に読めば、
> ひととおり使えるようになります。

---

## 目次

| 章 | 内容 |
|---|---|
| [1. できること](#1-できること) | 何のためのツールか、3 つの使い方 |
| [2. 5 分ではじめる](#2-5-分ではじめる) | 起動して 1 回叩くまで |
| [3. 起動と設定の上書き](#3-起動と設定の上書き) | 起動方法、ポート変更、設定の与え方 |
| [4. アプリケーションから接続する](#4-アプリケーションから接続する) | 各言語・各 SDK の base URL 設定 |
| [5. 何も設定しないときの応答](#5-何も設定しないときの応答) | 既定テンプレートとその変更 |
| [6. 応答を変える 3 つの方法](#6-応答を変える-3-つの方法と優先順位) | 優先順位の全体像 |
| [7. 方法 A: `X-Mock-*` ヘッダー](#7-方法-a-x-mock-ヘッダーで-1-回だけ変える) | 1 リクエストだけ挙動を変える |
| [8. 方法 B: スタブルール](#8-方法-b-スタブルールで応答を決める) | 条件付きの応答定義とレシピ集 |
| [9. 呼び出しを検証する](#9-呼び出しを検証する) | アプリが何を送ったかを確認する |
| [10. ストリーミング](#10-ストリーミング) | SSE / Bedrock event stream |
| [11. チャット以外のエンドポイント](#11-チャット以外のエンドポイント) | モデル一覧・トークン数・埋め込み |
| [12. テストに組み込む](#12-テストに組み込む) | JUnit / pytest / CI / Docker |
| [13. 本物の応答を使う](#13-本物の応答を使う-proxy--replay--cached_proxy) | 記録と再生、SigV4 |
| [14. コスト分析](#14-コスト分析) | トークン消費と費用の集計 |
| [15. 設定リファレンス](#15-設定リファレンス) | 全設定項目と環境変数名 |
| [16. Admin API リファレンス](#16-admin-api-リファレンス) | 管理エンドポイント一覧 |
| [17. トラブルシューティング](#17-トラブルシューティング) | 症状別の原因と対処 |
| [18. FAQ](#18-faq) | よくある質問 |
| [19. 制限事項](#19-制限事項) | できないこと |

---

## 1. できること

### 1.1 3 つの使い方

llm-mock には 4 つの動作モードがあり、大きく 3 つの使い方に分かれます。

| やりたいこと | モード | 本物の API | 資格情報 |
|---|---|---|---|
| **応答を自分で決めてテストしたい** (基本) | `MOCK` | 不要 | 不要 |
| **本物の応答を 1 度だけ取って、以降はそれを使い回したい** | `CACHED_PROXY` | 初回のみ | 必要 |
| **記録済みの応答だけでオフラインで回したい** | `REPLAY` | 不要 | 不要 |
| (記録を作る目的で毎回本物に転送する) | `PROXY` | 毎回 | 必要 |

既定は `MOCK` です。まずはこれだけ理解していれば十分で、
[13 章](#13-本物の応答を使う-proxy--replay--cached_proxy)は必要になってから読めば足ります。

### 1.2 何がうれしいか

- **1 つのスタブが 4 プロトコルすべてに効く。** 「プロンプトに `weather` を含んだら
  `It is sunny in Tokyo.` と答える」を 1 回登録すれば、OpenAI でも Anthropic でも Gemini でも
  Bedrock でも同じように効きます。マルチプロバイダー対応のアプリのテストで威力を発揮します。
- **エラーや遅延を再現できる。** 429、500、タイムアウト、ツール呼び出し、途中打ち切り
  (`finish_reason: length`) を、リクエストヘッダー 1 本で起こせます。
- **アプリが何を送ったかを検証できる。** リクエスト本文が記録されるので、
  「プロンプトに正しくコンテキストが入っているか」をテストからアサートできます。
- **決定論的。** 同じ入力なら常に同じ応答・同じトークン数・同じ埋め込みベクトルです。
- **本物の SDK が動く。** 4 社の公式 Java SDK で実際に検証済みです
  (詳細は [README の 10.2](../README.md#102-sdk-レベル-本物のクライアントが動くか))。

---

## 2. 5 分ではじめる

### 手順 1: 起動する

```bash
git clone https://github.com/ykwyuta/llm-mock.git
cd llm-mock
mvn spring-boot:run
```

`Started LlmMockApplication` と出れば起動完了です (初回はビルドのため数十秒かかります)。

### 手順 2: 生きているか確認する

```bash
curl http://localhost:8080/__admin/health
```

```json
{"recordings":0,"stubs":0,"requests":0,"mode":"MOCK","status":"UP","usageRecords":0}
```

### 手順 3: 4 プロバイダーすべてを叩いてみる

```bash
# OpenAI 形式
curl -s -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"こんにちは"}]}'

# Anthropic 形式
curl -s -X POST http://localhost:8080/anthropic/v1/messages \
  -H 'content-type: application/json' \
  -d '{"model":"claude-sonnet-4-5","max_tokens":100,"messages":[{"role":"user","content":"hello"}]}'

# Gemini 形式
curl -s -X POST 'http://localhost:8080/gemini/v1beta/models/gemini-2.5-flash:generateContent' \
  -H 'content-type: application/json' \
  -d '{"contents":[{"role":"user","parts":[{"text":"hello"}]}]}'

# Bedrock 形式 (モデル ID の ':' は %3A にエンコードします)
curl -s -X POST 'http://localhost:8080/bedrock/model/anthropic.claude-sonnet-4-5-20250929-v1%3A0/converse' \
  -H 'content-type: application/json' \
  -d '{"messages":[{"role":"user","content":[{"text":"hello"}]}]}'
```

OpenAI の応答例 (実際の出力):

```json
{
  "id": "chatcmpl-5de49acb9d7a44b88951035d",
  "object": "chat.completion",
  "created": 1788094500,
  "model": "gpt-4o",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "[llm-mock] echo: こんにちは"},
    "finish_reason": "stop",
    "logprobs": null
  }],
  "usage": {"prompt_tokens": 5, "completion_tokens": 6, "total_tokens": 11},
  "system_fingerprint": "fp_llmmock"
}
```

4 社とも**それぞれの本物と同じ形**で返ってきます。中身の文言は既定テンプレート
`[llm-mock] echo: {{prompt}}` によるものです。

### 手順 4: 応答を固定してみる

```bash
curl -X POST http://localhost:8080/__admin/stubs \
  -H 'content-type: application/json' \
  -d '{"name":"weather","promptPattern":"(?i)weather","responseText":"It is sunny in Tokyo."}'
```

以降、プロンプトに `weather` を含む呼び出しは**どのプロバイダーでも**この文言を返します。

```bash
curl -s -X POST http://localhost:8080/anthropic/v1/messages \
  -H 'content-type: application/json' \
  -d '{"model":"claude-sonnet-4-5","max_tokens":50,"messages":[{"role":"user","content":"What is the weather?"}]}'
```

```json
{"id":"msg_ca11...","type":"message","role":"assistant","model":"claude-sonnet-4-5",
 "content":[{"type":"text","text":"It is sunny in Tokyo."}],
 "stop_reason":"end_turn","usage":{"input_tokens":8,"output_tokens":6, ...}}
```

### 手順 5: 片付ける

```bash
curl -X POST http://localhost:8080/__admin/reset   # スタブ・リクエストログ・消費記録をすべて削除
```

ここまでできれば基本操作は終わりです。

---

## 3. 起動と設定の上書き

### 3.1 起動方法

| 方法 | コマンド | 用途 |
|---|---|---|
| Maven から | `mvn spring-boot:run` | 開発中。コードを触りながら使う |
| jar から | `mvn package` → `java -jar target/llm-mock-0.1.0-SNAPSHOT.jar` | CI やチーム配布。起動が速い |
| テストから | `@SpringBootTest(webEnvironment = RANDOM_PORT)` | Java のテストスイートに埋め込む ([12.2](#122-junit-5-java)) |

停止は `Ctrl-C`、バックグラウンド起動なら `kill <pid>` です。

### 3.2 ポートを変える

```bash
java -jar target/llm-mock-0.1.0-SNAPSHOT.jar --server.port=9000
# または
SERVER_PORT=9000 java -jar target/llm-mock-0.1.0-SNAPSHOT.jar
```

ポート `0` を指定するとランダムな空きポートになります (CI で衝突を避けたいとき)。

### 3.3 設定を与える 4 つの方法

優先度は **コマンドライン引数 > 環境変数 > 外部 `application.yml` > 同梱の既定値** です。

**(a) コマンドライン引数** — 一時的な変更に最適です。

```bash
java -jar target/llm-mock-0.1.0-SNAPSHOT.jar \
  --llm-mock.default-response-template='テスト用の固定応答です' \
  --llm-mock.require-auth=true \
  --llm-mock.stream.delay-ms=50
```

**(b) 環境変数** — Docker や CI 向けです。`llm-mock.xxx-yyy` は
**大文字にして、ドットを `_`、ハイフンを削除**した名前になります。

| 設定キー | 環境変数名 |
|---|---|
| `llm-mock.mode` | `LLMMOCK_MODE` |
| `llm-mock.default-response-template` | `LLMMOCK_DEFAULTRESPONSETEMPLATE` |
| `llm-mock.require-auth` | `LLMMOCK_REQUIREAUTH` |
| `llm-mock.proxy.recordings-dir` | `LLMMOCK_PROXY_RECORDINGSDIR` |
| `llm-mock.stream.words-per-chunk` | `LLMMOCK_STREAM_WORDSPERCHUNK` |

```bash
LLMMOCK_DEFAULTRESPONSETEMPLATE='[upstream] answered: {{prompt}}' \
  java -jar target/llm-mock-0.1.0-SNAPSHOT.jar
```

**(c) 外部の `application.yml`** — 設定が増えたらこれが読みやすいです。

```bash
java -jar target/llm-mock-0.1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:./my-llm-mock.yml
```

**(d) プロファイル** — 「記録用」「再生用」を切り替えたいときに便利です。
`application-record.yml` / `application-replay.yml` を用意して:

```bash
java -jar target/llm-mock-0.1.0-SNAPSHOT.jar --spring.profiles.active=replay
```

設定項目の一覧は [15. 設定リファレンス](#15-設定リファレンス) にあります。

---

## 4. アプリケーションから接続する

### 4.1 base URL 一覧

プロバイダーごとに **URL プレフィックス**が付きます。4 社の API はパスが衝突する
(OpenAI と Anthropic がどちらも `GET /v1/models` を別形式で提供する) ためです。

| SDK / クライアント | 設定する base URL |
|---|---|
| OpenAI (`openai` python / node / java) | `http://localhost:8080/openai/v1` |
| Anthropic (`anthropic`) | `http://localhost:8080/anthropic` |
| Google GenAI (Gemini) | `http://localhost:8080/gemini` |
| AWS SDK (Bedrock Runtime の `endpoint_url`) | `http://localhost:8080/bedrock` |

> **`/v1` を付けるのは OpenAI だけです。** OpenAI SDK は base URL の末尾にそのまま
> `/chat/completions` を足すため `/openai/v1` まで指定します。他の 3 社の SDK は
> `/v1/messages` や `/v1beta/models/...` を自分で組み立てるので、プレフィックスまでで止めます。

プレフィックスは `llm-mock.paths.*` で変更できます (→ [15 章](#15-設定リファレンス))。

### 4.2 Python

```python
# OpenAI
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/openai/v1", api_key="dummy")
print(client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "hello"}]
).choices[0].message.content)

# Anthropic
from anthropic import Anthropic
client = Anthropic(base_url="http://localhost:8080/anthropic", api_key="dummy")
print(client.messages.create(
    model="claude-sonnet-4-5", max_tokens=100,
    messages=[{"role": "user", "content": "hello"}]
).content[0].text)

# Gemini (google-genai)
from google import genai
client = genai.Client(api_key="dummy",
                      http_options={"base_url": "http://localhost:8080/gemini"})
print(client.models.generate_content(model="gemini-2.5-flash", contents="hello").text)

# Bedrock (boto3)
import boto3
bedrock = boto3.client("bedrock-runtime", region_name="us-east-1",
                       endpoint_url="http://localhost:8080/bedrock",
                       aws_access_key_id="dummy", aws_secret_access_key="dummy")
print(bedrock.converse(modelId="anthropic.claude-sonnet-4-5-20250929-v1:0",
                       messages=[{"role": "user", "content": [{"text": "hello"}]}]))
```

**資格情報はダミーで構いません。** 既定では認証チェックをしないためです
(`llm-mock.require-auth: true` にすると資格情報ヘッダーの有無だけを検査します)。
ただし SDK 側が「キーが空だと起動時にエラー」にすることがあるので、`"dummy"` のような
何かしらの文字列を渡してください。

### 4.3 Node.js / TypeScript

```ts
import OpenAI from "openai";
const openai = new OpenAI({ baseURL: "http://localhost:8080/openai/v1", apiKey: "dummy" });

import Anthropic from "@anthropic-ai/sdk";
const anthropic = new Anthropic({ baseURL: "http://localhost:8080/anthropic", apiKey: "dummy" });

import { GoogleGenAI } from "@google/genai";
const gemini = new GoogleGenAI({ apiKey: "dummy",
  httpOptions: { baseUrl: "http://localhost:8080/gemini" } });
```

### 4.4 Java

```java
// OpenAI
OpenAIClient openai = OpenAIOkHttpClient.builder()
        .baseUrl("http://localhost:8080/openai/v1")
        .apiKey("dummy")
        .build();

// Anthropic
AnthropicClient anthropic = AnthropicOkHttpClient.builder()
        .baseUrl("http://localhost:8080/anthropic")
        .apiKey("dummy")
        .build();

// Gemini
Client gemini = Client.builder()
        .apiKey("dummy")
        .httpOptions(HttpOptions.builder().baseUrl("http://localhost:8080/gemini").build())
        .build();

// Bedrock
BedrockRuntimeClient bedrock = BedrockRuntimeClient.builder()
        .endpointOverride(URI.create("http://localhost:8080/bedrock"))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("dummy", "dummy")))
        .build();
```

### 4.5 base URL をアプリのコードに直書きしない

テストのために本番コードを書き換えるのは避けたいので、**環境変数で差し替えられるように**
しておくのが定石です。多くの SDK は標準で対応しています。

| SDK | base URL を差し替える環境変数 |
|---|---|
| OpenAI (python / node) | `OPENAI_BASE_URL` |
| Anthropic (python / node) | `ANTHROPIC_BASE_URL` |
| Google GenAI | `GOOGLE_GEMINI_BASE_URL` (SDK のバージョンにより異なるため、`http_options` 指定が確実) |
| AWS SDK / boto3 | `AWS_ENDPOINT_URL_BEDROCK_RUNTIME` |

```bash
export OPENAI_BASE_URL=http://localhost:8080/openai/v1
export OPENAI_API_KEY=dummy
pytest                      # アプリのコードは 1 行も変えずにモックを向く
```

---

## 5. 何も設定しないときの応答

スタブもヘッダーも無いとき、応答本文は**既定テンプレート**から作られます。

```yaml
llm-mock:
  default-response-template: "[llm-mock] echo: {{prompt}}"
```

使えるプレースホルダは次の 4 つです。

| プレースホルダ | 展開されるもの |
|---|---|
| `{{prompt}}` | **直近の user 発話**のテキスト |
| `{{model}}` | リクエストのモデル名 |
| `{{provider}}` | `openai` / `anthropic` / `gemini` / `bedrock` |
| `{{messageCount}}` | 会話に含まれるメッセージ数 |

```bash
java -jar target/llm-mock-0.1.0-SNAPSHOT.jar \
  --llm-mock.default-response-template='{{provider}}/{{model}} が {{messageCount}} 件受け取りました'
```

**トークン数**も自動で計算されます (4 文字 ≒ 1 トークン + メッセージあたりの固定オーバーヘッド)。
同じ入力なら常に同じ数になるので、テストでアサートできます。

---

## 6. 応答を変える 3 つの方法と優先順位

```
  リクエスト
      │
      ├─ ① X-Mock-* ヘッダーがある?      ──yes──▶ それを使う (このリクエストだけ)
      │
      ├─ ② マッチするスタブルールがある?  ──yes──▶ それを使う (priority 降順)
      │
      └─ ③ どちらも無い                  ────────▶ 既定テンプレート
```

**フィールドごとに独立して**この順序が適用されます。たとえばスタブが `responseText` だけを
定めていて、ヘッダーで `X-Mock-Delay-Ms` だけを指定した場合、本文はスタブ・遅延はヘッダーから、
という組み合わせになります。

| 方法 | 効果範囲 | 向いている場面 |
|---|---|---|
| ① `X-Mock-*` ヘッダー | そのリクエスト 1 回だけ | 1 つのテストだけ挙動を変えたい。エラー注入 |
| ② スタブルール | 削除するまで永続 | スイート全体の共通応答。プロンプト内容で応答を出し分ける |
| ③ 既定テンプレート | 全体 | 「とりあえず何か返ればいい」テスト |

この順序のおかげで、**スイート共通のスタブを崩さずに個別のテストだけ挙動を変えられます**。

---

## 7. 方法 A: `X-Mock-*` ヘッダーで 1 回だけ変える

### 7.1 ヘッダー一覧

| ヘッダー | 効果 | 例 |
|---|---|---|
| `X-Mock-Text` | 応答本文を差し替える | `X-Mock-Text: 固定応答` |
| `X-Mock-Finish-Reason` | 停止理由。`stop` / `length` / `tool_use` / `content_filter` / `stop_sequence` | `X-Mock-Finish-Reason: length` |
| `X-Mock-Status` | 400 以上ならその HTTP ステータスで失敗させる | `X-Mock-Status: 429` |
| `X-Mock-Error-Type` | 上の失敗のエラー種別 | `X-Mock-Error-Type: rate_limit` |
| `X-Mock-Error-Message` | 上の失敗のメッセージ | `X-Mock-Error-Message: slow down` |
| `X-Mock-Delay-Ms` | 応答前に待つミリ秒 | `X-Mock-Delay-Ms: 3000` |
| `X-Mock-Tool-Name` | この名前のツール呼び出しを返す | `X-Mock-Tool-Name: get_weather` |
| `X-Mock-Tool-Arguments` | ツール引数 (生の JSON) | `X-Mock-Tool-Arguments: {"city":"Tokyo"}` |
| `X-Mock-Input-Tokens` | 報告する入力トークン数を固定 | `X-Mock-Input-Tokens: 1000` |
| `X-Mock-Output-Tokens` | 報告する出力トークン数を固定 | `X-Mock-Output-Tokens: 500` |
| `X-Mock-Stub` | 照合をスキップし、名前でスタブを指名する | `X-Mock-Stub: weather` |

> `X-Mock-Text` は**空文字を明示すると「本文なし」**になります (ツール呼び出しだけを返したいとき)。
> curl では `-H 'X-Mock-Text;'` (セミコロン) で空ヘッダーを送れます。

> `X-Mock-Stub` は**照合条件をすべて飛ばします**。`remainingUses` を使い切ったスタブでも
> 名前で指名すれば返ります。「このテストではこの応答」と決め打ちしたいときに使ってください。

### 7.2 レシピ

**レート制限 (429) を再現する**

```bash
curl -s -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' -H 'X-Mock-Status: 429' \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}'
```

```json
{"error":{"message":"Simulated 429 from llm-mock","type":"rate_limit_error",
          "param":null,"code":"rate_limit_exceeded"}}
```

ステータスだけ指定すれば、エラー種別は自動で埋まります。

| ステータス | 既定のエラー種別 |
|---|---|
| 400 | `invalid_request` |
| 401 | `authentication` |
| 403 | `permission` |
| 404 | `not_found` |
| 408 | `timeout` |
| 429 | `rate_limit` |
| 503 | `service_unavailable` |
| その他 5xx | `server_error` |

**エラー封筒は各社の形式に変換されます**。同じ `X-Mock-Status: 429` でも、Anthropic なら
`{"type":"error","error":{"type":"rate_limit_error","message":...}}`、Gemini なら
`{"error":{"code":429,"message":...,"status":"RESOURCE_EXHAUSTED"}}` の形で返るので、
**SDK 側は `RateLimitError` 等の正しい例外型を投げます**。

**タイムアウトを試す**

```bash
curl -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' -H 'X-Mock-Delay-Ms: 30000' \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}'
```

アプリ側のタイムアウト処理・リトライ処理の確認に使えます。

**ツール呼び出しを返す**

```bash
curl -s -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' \
  -H 'X-Mock-Tool-Name: get_weather' \
  -H 'X-Mock-Tool-Arguments: {"city":"Tokyo"}' \
  -H 'X-Mock-Text;' \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}'
```

```json
{"choices":[{"index":0,"message":{"role":"assistant","content":"",
  "tool_calls":[{"id":"call_5d2e...","type":"function",
    "function":{"name":"get_weather","arguments":"{\"city\":\"Tokyo\"}"}}]},
  "finish_reason":"tool_calls"}], ...}
```

引数の渡し方も各社の形式に合わせて変換されます (OpenAI は **JSON 文字列**、
Anthropic / Gemini / Bedrock は **JSON オブジェクト**)。

**トークン上限で打ち切られた応答**

```bash
curl -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' \
  -H 'X-Mock-Finish-Reason: length' -H 'X-Mock-Output-Tokens: 4096' \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"長文を書いて"}]}'
```

**コスト計算のテスト用に大きなトークン数を報告させる**

```bash
-H 'X-Mock-Input-Tokens: 100000' -H 'X-Mock-Output-Tokens: 50000'
```

### 7.3 SDK からヘッダーを付ける

```python
# OpenAI (python)
client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "hi"}],
    extra_headers={"X-Mock-Status": "429"})

# Anthropic (python)
client.messages.create(..., extra_headers={"X-Mock-Delay-Ms": "5000"})
```

```java
// OpenAI (java)
ChatCompletionCreateParams.builder()
    .model("gpt-4o")
    .addUserMessage("hi")
    .putAdditionalHeader("X-Mock-Status", "429")
    .build();
```

クライアント生成時に既定ヘッダーとして付ければ、そのクライアント経由の呼び出しすべてに効きます
(`OpenAI(default_headers={...})` など)。

---

## 8. 方法 B: スタブルールで応答を決める

### 8.1 登録する

```bash
curl -X POST http://localhost:8080/__admin/stubs \
  -H 'content-type: application/json' -d '{
    "name": "weather",
    "provider": "ANY",
    "promptPattern": "(?i)weather",
    "responseText": "It is sunny in Tokyo.",
    "priority": 10
  }'
```

```json
{"id":1,"name":"weather","provider":"ANY","promptPattern":"(?i)weather","priority":10,
 "responseText":"It is sunny in Tokyo.","delayMs":0,"enabled":true,
 "createdAt":"2026-08-30T12:55:10.630241Z"}
```

**同じ `name` で POST すると置き換え**になります。テストの前処理で毎回同じスタブを登録しても
重複しません。

### 8.2 フィールド全リファレンス

| フィールド | 型 | 既定 | 意味 |
|---|---|---|---|
| `name` | string | **必須** | 一意な名前。`X-Mock-Stub` での指名にも使う |
| `provider` | enum | `ANY` | `ANY` / `OPENAI` / `ANTHROPIC` / `GEMINI` / `BEDROCK` |
| `modelPattern` | regex | `null` | モデル名に対する正規表現 (部分一致) |
| `promptPattern` | regex | `null` | 会話テキストに対する正規表現 (部分一致) |
| `endpointPattern` | regex | `null` | エンドポイント名に対する正規表現 (部分一致) |
| `priority` | int | `0` | 大きいほど優先。同点なら**古いルールが勝つ** |
| `responseText` | string | `null` | 返す本文。`null` なら既定テンプレート |
| `finishReason` | enum | 自動 | `STOP` / `LENGTH` / `TOOL_USE` / `CONTENT_FILTER` / `STOP_SEQUENCE` |
| `toolName` | string | `null` | ツール呼び出しを返す |
| `toolArguments` | string | `{}` | その引数 (**JSON 文字列**として渡す) |
| `inputTokens` | int | 自動 | 報告する入力トークン数 |
| `outputTokens` | int | 自動 | 報告する出力トークン数 |
| `httpStatus` | int | `null` | 400 以上なら失敗を再現 |
| `errorType` | string | 自動 | その失敗の種別 |
| `errorMessage` | string | 自動 | その失敗のメッセージ |
| `delayMs` | long | `0` | 応答前に待つミリ秒 |
| `remainingUses` | int | `null` | 残り使用回数。`null` は無制限 |
| `enabled` | bool | `true` | `false` で一時停止 (削除しない) |

### 8.3 照合の仕組み — 何にマッチするのか

**`null` でない条件がすべて一致したルール**が対象になります。ここを誤解すると
「スタブが効かない」で詰まるので、正確に押さえてください。

**`promptPattern` は「会話全体」にマッチします。** 直近の user 発話だけではありません。
照合対象の文字列は次の形に組み立てられます。

```
system: あなたは親切なアシスタントです
user: 東京の weather を教えて
assistant: はい
user: もう一度
```

つまり `"(?i)^user: weather"` のようなパターンは**当たりません** (行頭の `^` は文字列先頭を指すため)。
役割まで含めて狙いたいときは `"user: .*weather"` のように書きます。
正規表現は `DOTALL` (`.` が改行にもマッチ) で、**部分一致** (`find()`) です。

**`endpointPattern` はパスではなく「エンドポイント名」にマッチします。** 値は次のいずれかです。

| プロバイダー | エンドポイント名 |
|---|---|
| OpenAI | `chat.completions` / `completions` / `embeddings` |
| Anthropic | `messages` / `messages.count_tokens` |
| Gemini | `generateContent` / `streamGenerateContent` / `countTokens` / `embedContent` / `batchEmbedContents` |
| Bedrock | `converse` / `converse-stream` / `invoke` / `invoke-with-response-stream` |

**`modelPattern` はリクエストのモデル名にマッチします。** Bedrock ではモデル ID
(`anthropic.claude-sonnet-4-5-20250929-v1:0`) がそのまま対象です。

**不正な正規表現は登録時に 400 で弾かれます。**

```bash
curl -X POST http://localhost:8080/__admin/stubs -H 'content-type: application/json' \
  -d '{"name":"bad","promptPattern":"([","responseText":"x"}'
```

```json
{"error":{"message":"promptPattern is not a valid regex: Unclosed character class near index 1\n([\n ^",
          "type":"invalid_request"}}
```

### 8.4 レシピ集

**(1) 全プロバイダー共通の固定応答**

```json
{"name": "always", "responseText": "OK", "priority": -100}
```

`priority` を低くしておけば、他のルールが当たらなかったときの受け皿になります。

**(2) プロバイダー限定**

```json
{"name": "openai-only", "provider": "OPENAI", "responseText": "from openai"}
```

**(3) モデルごとに応答を変える**

```json
{"name": "mini", "modelPattern": "mini$", "responseText": "軽量モデルの応答", "priority": 20}
{"name": "pro",  "modelPattern": "^gpt-4o$", "responseText": "高性能モデルの応答", "priority": 20}
```

**(4) ツール呼び出しを返す**

```json
{
  "name": "weather-tool",
  "promptPattern": "天気|weather",
  "toolName": "get_weather",
  "toolArguments": "{\"city\":\"Tokyo\",\"unit\":\"celsius\"}",
  "responseText": "",
  "priority": 30
}
```

`toolArguments` は **JSON を文字列にエスケープして**渡します。`responseText: ""` にすると
ツール呼び出しだけの応答になります。

**(5) 特定のプロンプトだけ失敗させる**

```json
{
  "name": "fail-on-secret",
  "promptPattern": "(?i)password|secret",
  "httpStatus": 400,
  "errorType": "invalid_request",
  "errorMessage": "Content policy violation",
  "priority": 100
}
```

**(6) 1 回だけ失敗して 2 回目は成功する (リトライ処理のテスト)**

```json
{"name": "flaky", "httpStatus": 503, "remainingUses": 1, "priority": 100}
{"name": "recovered", "responseText": "2 回目は成功", "priority": 50}
```

1 回目は `flaky` (priority 100) が当たって 503、使い切られるので 2 回目は `recovered` が当たります。
`remainingUses` は**失敗を返した場合でも消費されます**。

**(7) 遅い応答**

```json
{"name": "slow", "modelPattern": "gpt-4o", "delayMs": 5000, "responseText": "遅い応答"}
```

**(8) 長い会話の後半だけに反応する**

```json
{"name": "followup", "promptPattern": "user: .*\\n[\\s\\S]*user: ", "responseText": "2 往復目です"}
```

**(9) 一時的に無効化する**

```bash
curl -X PUT http://localhost:8080/__admin/stubs/1 -H 'content-type: application/json' \
  -d '{"name":"weather","promptPattern":"(?i)weather","responseText":"...","enabled":false}'
```

削除せずに止められるので、「このテストの間だけ無効」といった使い方ができます。

### 8.5 一覧・更新・削除

```bash
curl http://localhost:8080/__admin/stubs                    # 一覧
curl http://localhost:8080/__admin/stubs/1                  # 1 件
curl -X PUT http://localhost:8080/__admin/stubs/1 -H 'content-type: application/json' -d '{...}'
curl -X DELETE http://localhost:8080/__admin/stubs/1        # 1 件削除
curl -X DELETE http://localhost:8080/__admin/stubs          # 全削除
```

`PUT` は**全フィールド置換**です (`null` のフィールドは既定値に戻ります)。部分更新ではありません。

---

## 9. 呼び出しを検証する

llm-mock は受け取ったリクエストをすべて記録します。**アプリが本当に意図した内容を送ったか**を
テストからアサートできます。

```bash
curl 'http://localhost:8080/__admin/requests?provider=OPENAI&limit=5'
```

```json
[{
  "id": 8,
  "provider": "ANTHROPIC",
  "endpoint": "messages",
  "model": "claude-sonnet-4-5",
  "streaming": false,
  "httpStatus": 200,
  "matchedStub": "weather",
  "inputTokens": 8,
  "outputTokens": 6,
  "requestBody": "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":50,\"messages\":[...]}",
  "responseText": "It is sunny in Tokyo.",
  "createdAt": "2026-08-30T12:55:18.727171Z"
}]
```

| フィールド | 意味 |
|---|---|
| `endpoint` | エンドポイント名 ([8.3 の表](#83-照合の仕組み--何にマッチするのか)) |
| `streaming` | ストリーミング呼び出しだったか |
| `matchedStub` | 当たったスタブの名前。`null` なら既定テンプレートで応答した |
| `requestBody` | **アプリが送った生の JSON** (最大 256KB、`llm-mock.recording.max-body-bytes` で変更可) |
| `responseText` | 返した本文 (エラー時はエラーメッセージ) |

クエリパラメータは `provider` / `model` / `endpoint` / `limit` (既定 100) です。
**新しい順**に返ります。

```bash
curl 'http://localhost:8080/__admin/requests?endpoint=chat.completions&model=gpt-4o&limit=1'
curl -X DELETE http://localhost:8080/__admin/requests    # 全削除
```

記録は既定で **1,000 件**まで保持され、超えた分は古いものから自動削除されます
(`llm-mock.recording.max-entries`)。

> **注意:** リクエスト**ヘッダーは記録しません**。資格情報がログに残るのを避けるためです。

---

## 10. ストリーミング

4 社ともストリーミングに対応しています。**モックの応答本文を単語単位で分割して**配信します。

```yaml
llm-mock:
  stream:
    words-per-chunk: 3    # 1 チャンクあたりの単語数
    delay-ms: 0           # チャンク間の待機時間。UI の描画テストなら 100 など
```

**OpenAI** — `"stream": true`。イベント名なしの SSE、`data: [DONE]` で終端します。

```bash
curl -N -X POST http://localhost:8080/openai/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"count one two three"}]}'
```

```
data: {"id":"chatcmpl-858...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

data: {"id":"chatcmpl-858...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"[llm-mock] echo: count "}}]}

data: {"id":"chatcmpl-858...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"one two three "}}]}
...
data: [DONE]
```

**Anthropic** — `"stream": true`。**イベント名付き**の SSE です。

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_5c6...","usage":{"input_tokens":5,"output_tokens":0,...}}}

event: ping
data: {"type":"ping"}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
...
```

**Gemini** — `:streamGenerateContent`。`?alt=sse` を付けると SSE、付けないと **JSON 配列**で返ります
(本物と同じ挙動です)。

```bash
curl -N -X POST 'http://localhost:8080/gemini/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse' \
  -H 'content-type: application/json' -d '{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}'
```

**Bedrock** — `/converse-stream` と `/invoke-with-response-stream`。SSE ではなく
**AWS event stream のバイナリフレーム**を返すので、curl で読むのには向きません。
AWS SDK から使ってください (SDK のデコーダで読めることを検証済みです)。

ストリーミング中に `X-Mock-Delay-Ms` を指定すると、**最初のチャンクの前**に待機します
(初回トークン遅延の再現)。チャンク間の間隔は `llm-mock.stream.delay-ms` です。

---

## 11. チャット以外のエンドポイント

### 11.1 モデル一覧

```bash
curl http://localhost:8080/openai/v1/models
curl http://localhost:8080/openai/v1/models/gpt-4o
curl http://localhost:8080/anthropic/v1/models
curl http://localhost:8080/gemini/v1beta/models
```

返るモデルは設定できます。既定は次のとおりです。

| プロバイダー | 既定のモデル |
|---|---|
| OpenAI | `gpt-4o`, `gpt-4o-mini`, `gpt-4.1`, `o3-mini` |
| Anthropic | `claude-opus-4-5`, `claude-sonnet-4-5`, `claude-haiku-4-5` |
| Gemini | `gemini-2.5-pro`, `gemini-2.5-flash`, `text-embedding-004` |
| Bedrock | `anthropic.claude-sonnet-4-5-20250929-v1:0`, `amazon.nova-pro-v1:0`, `amazon.titan-text-express-v1`, `meta.llama3-70b-instruct-v1:0` |

```yaml
llm-mock:
  models:
    openai: [gpt-4o, my-fine-tuned-model]
```

> **モデル一覧に無いモデル名でも、チャット呼び出しは通ります。** 一覧は
> 「モデル選択 UI を作るアプリ」のためのもので、リクエストの検証には使っていません。

### 11.2 トークン数の見積もり

```bash
curl -X POST http://localhost:8080/anthropic/v1/messages/count_tokens \
  -H 'content-type: application/json' \
  -d '{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hello world"}]}'
```

```json
{"input_tokens":6}
```

Gemini は `:countTokens` です。

### 11.3 埋め込み

```bash
curl -X POST http://localhost:8080/openai/v1/embeddings \
  -H 'content-type: application/json' \
  -d '{"model":"text-embedding-3-small","input":"hello"}'
```

```json
{"object":"list","data":[{"object":"embedding","index":0,
  "embedding":[-0.003636,0.011457,0.013214,-0.01603, ...]}], ...}
```

- ベクトルは**入力のハッシュをシードにした擬似乱数**で、**正規化済み** (ノルム 1) です。
- **同じ入力なら常に同じベクトル**が返るので、類似度計算やベクトル DB への投入をテストできます。
- 次元数は既定で OpenAI 1536 / Gemini 768 (`llm-mock.embedding.*-dimensions` で変更可)。
- Gemini は `:embedContent` と `:batchEmbedContents` の両方に対応しています
  (公式 SDK の `embedContent` は内部的に後者を呼びます)。

---

## 12. テストに組み込む

### 12.1 テストの基本形

```
1. モックサーバーを起動する (スイート全体で 1 回)
2. @BeforeEach で /__admin/reset を呼ぶ         ← 前のテストの状態を消す
3. そのテスト用のスタブを登録する
4. テスト対象のコードを実行する
5. 応答を検証する + /__admin/requests でリクエスト内容を検証する
```

`POST /__admin/reset` は**スタブ・リクエストログ・トークン消費記録**をまとめて削除します
(記録ファイル (recordings) は消えません)。

> **並列実行に注意:** 状態はサーバー全体で共有されます。テストを並列実行する場合は、
> `reset` が他のテストの状態を壊さないよう、テストごとにポートを分ける
> (`webEnvironment = RANDOM_PORT` のインスタンスをテストクラスごとに立てる)、
> または `X-Mock-*` ヘッダーだけで完結させるのが安全です。

### 12.2 JUnit 5 (Java)

同じ JVM 内で起動するのがいちばん手軽です。

```java
@SpringBootTest(classes = LlmMockApplication.class, webEnvironment = RANDOM_PORT)
class MyServiceTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    OpenAIClient client;

    @BeforeEach
    void setUp() {
        rest.postForEntity("http://localhost:" + port + "/__admin/reset", null, Void.class);
        client = OpenAIOkHttpClient.builder()
                .baseUrl("http://localhost:" + port + "/openai/v1")
                .apiKey("dummy")
                .build();
    }

    @Test
    void 天気を聞かれたら固定応答を返す() {
        // スタブを登録
        rest.postForEntity("http://localhost:" + port + "/__admin/stubs",
                Map.of("name", "weather", "promptPattern", "(?i)weather",
                       "responseText", "It is sunny in Tokyo."), Void.class);

        var response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("gpt-4o")
                        .addUserMessage("What is the weather?")
                        .build());

        assertThat(response.choices().get(0).message().content())
                .hasValue("It is sunny in Tokyo.");
    }

    @Test
    void レート制限のときリトライする() {
        // 1 回目だけ 429、2 回目は成功 (registerStub は POST /__admin/stubs を呼ぶ自作ヘルパー)
        registerStub(Map.of("name", "limit", "httpStatus", 429,
                            "remainingUses", 1, "priority", 100));
        registerStub(Map.of("name", "ok", "responseText", "成功", "priority", 50));

        assertThat(myService.askWithRetry("hi")).isEqualTo("成功");
    }
}
```

別プロセスで起動したサーバーを使う場合は、`@BeforeAll` で jar を起動するか、
CI のサービスコンテナとして立てます ([12.4](#124-ci-github-actions))。

### 12.3 pytest (Python)

```python
import subprocess, time, httpx, pytest
from openai import OpenAI

MOCK = "http://localhost:8080"

@pytest.fixture(scope="session", autouse=True)
def mock_server():
    proc = subprocess.Popen(
        ["java", "-jar", "target/llm-mock-0.1.0-SNAPSHOT.jar"],
        stdout=subprocess.DEVNULL)
    for _ in range(60):                      # 起動待ち
        try:
            if httpx.get(f"{MOCK}/__admin/health", timeout=1).status_code == 200:
                break
        except httpx.HTTPError:
            time.sleep(1)
    else:
        proc.kill()
        raise RuntimeError("llm-mock が起動しませんでした")
    yield
    proc.terminate()

@pytest.fixture(autouse=True)
def reset():
    httpx.post(f"{MOCK}/__admin/reset")

@pytest.fixture
def client():
    return OpenAI(base_url=f"{MOCK}/openai/v1", api_key="dummy")

def stub(**rule):
    httpx.post(f"{MOCK}/__admin/stubs", json=rule).raise_for_status()


def test_プロンプトにコンテキストが含まれる(client):
    stub(name="any", responseText="OK")      # フィールド名は camelCase

    my_service.summarize("社外秘の資料本文", client=client)

    logs = httpx.get(f"{MOCK}/__admin/requests", params={"limit": 1}).json()
    assert "社外秘の資料本文" in logs[0]["requestBody"]
    assert logs[0]["model"] == "gpt-4o"


def test_レート制限が例外になる(client):
    import openai
    with pytest.raises(openai.RateLimitError):
        client.chat.completions.create(
            model="gpt-4o",
            messages=[{"role": "user", "content": "hi"}],
            extra_headers={"X-Mock-Status": "429"})
```

> スタブのフィールド名は **camelCase** (`responseText`, `promptPattern`, `httpStatus`) です。
> Python から辞書で渡すときは間違えやすいので注意してください。

### 12.4 CI (GitHub Actions)

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }

      - name: llm-mock を起動
        run: |
          mvn -q -DskipTests package
          nohup java -jar target/llm-mock-0.1.0-SNAPSHOT.jar &
          for i in $(seq 1 60); do
            curl -sf http://localhost:8080/__admin/health && break
            sleep 1
          done

      - name: テスト
        env:
          OPENAI_BASE_URL: http://localhost:8080/openai/v1
          OPENAI_API_KEY: dummy
        run: pytest
```

### 12.5 Docker / docker compose

同梱の Dockerfile はありませんが、jar があれば数行です。

```dockerfile
FROM eclipse-temurin:21-jre
COPY target/llm-mock-0.1.0-SNAPSHOT.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

```yaml
# docker-compose.yml
services:
  llm-mock:
    build: .
    ports: ["8080:8080"]
    environment:
      LLMMOCK_DEFAULTRESPONSETEMPLATE: "[mock] {{prompt}}"
    volumes:
      - ./recordings:/recordings        # 記録を再生する場合
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/__admin/health"]
      interval: 5s
      retries: 20

  app:
    build: ./app
    depends_on:
      llm-mock: { condition: service_healthy }
    environment:
      OPENAI_BASE_URL: http://llm-mock:8080/openai/v1
      OPENAI_API_KEY: dummy
```

---

## 13. 本物の応答を使う (`PROXY` / `REPLAY` / `CACHED_PROXY`)

「モックの作り物ではなく、**本物の API が実際に返した応答**でテストしたい」ときの機能です。

```
 [MOCK]          アプリ ──▶ llm-mock ──▶ スタブエンジン

 [PROXY]         アプリ ──▶ llm-mock ──▶ 本物の API
                                │
                                └──▶ recordings/*.json  (記録)

 [REPLAY]        アプリ ──▶ llm-mock ──▶ recordings/*.json  (本物は不要)

 [CACHED_PROXY]  アプリ ──▶ llm-mock ──▶ 記録があればそれ、無ければ本物を 1 回だけ
```

### 13.1 どれを選ぶか

| 状況 | 使うモード |
|---|---|
| とりあえず記録を作りたい | `PROXY` |
| 記録を作りつつ、2 回目以降は無料・オフラインにしたい | **`CACHED_PROXY`** (おすすめ) |
| 記録済み。CI では本物を絶対に呼びたくない | `REPLAY` |
| 応答を自分で決めたい | `MOCK` |

**迷ったら `CACHED_PROXY`** です。ローカルでは初回だけ本物を呼んで記録が貯まり、
CI では記録をコミットしておけば 1 回目からキャッシュヒットします。

### 13.2 記録する (`PROXY`)

```yaml
llm-mock:
  mode: PROXY
  proxy:
    recordings-dir: ./recordings
    targets:
      openai: https://api.openai.com
    headers:
      openai:
        Authorization: "Bearer ${OPENAI_API_KEY}"   # 本物の鍵はここだけに置く
```

```bash
OPENAI_API_KEY=sk-... java -jar target/llm-mock-0.1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:./proxy.yml
```

**アプリ側の設定は一切変えません。** base URL は llm-mock のままで、
呼び出しはそのまま本物に転送され、応答が `./recordings/*.json` に書き出されます。
アプリが持つ API キーはダミーのままで構いません
(`proxy.headers` で本物の資格情報に差し替わります)。

上流の base URL には**その API のルート**を指定します。

| プロバイダー | `targets` の例 |
|---|---|
| `openai` | `https://api.openai.com` |
| `anthropic` | `https://api.anthropic.com` |
| `gemini` | `https://generativelanguage.googleapis.com` |
| `bedrock` | `https://bedrock-runtime.us-east-1.amazonaws.com` |

> llm-mock のプレフィックス (`/openai` など) は転送時に取り除かれ、残りのパス
> (`/v1/chat/completions`) が上流に付きます。**上流が別の llm-mock の場合は、
> そのプレフィックスまで含めて** `http://other-host:8081/openai` と書いてください。
> ここを間違えると 404 になります ([17 章](#17-トラブルシューティング))。

### 13.3 再生する (`REPLAY`)

```yaml
llm-mock:
  mode: REPLAY
  proxy:
    recordings-dir: ./recordings
  replay:
    fallback: NOT_FOUND      # 記録が無ければ 404。MOCK ならスタブエンジンにフォールバック
```

**本物の API も資格情報も一切不要**になり、応答は記録したバイト列そのままが返ります。
ストリーミング (SSE / Bedrock のバイナリ event stream) も完全に同一です。

```bash
$ curl -D- -X POST http://localhost:8080/openai/v1/chat/completions \
    -H 'content-type: application/json' \
    -d '{"model":"gpt-4o","messages":[{"role":"user","content":"ping"}]}'
HTTP/1.1 200
X-Llm-Mock-Source: recording
{"id":"chatcmpl-9d9b...","object":"chat.completion", ... }

$ # 記録に無いリクエスト (fallback: NOT_FOUND のとき)
$ curl -o /dev/null -w '%{http_code}\n' -X POST ... -d '{"...":"unknown"}'
404
```

`fallback` の選び方:

| 値 | 挙動 | 向いている場面 |
|---|---|---|
| `MOCK` (既定) | 記録が無ければスタブエンジンが応答 | 記録があるものだけ本物、残りはモックで済ませたい |
| `NOT_FOUND` | 記録が無ければ 404 | **記録漏れを検出したい** (CI 向き) |

### 13.4 インテリジェントプロキシ (`CACHED_PROXY`)

```yaml
llm-mock:
  mode: CACHED_PROXY
  proxy:
    recordings-dir: ./recordings
    targets:
      openai: https://api.openai.com
    headers:
      openai:
        Authorization: "Bearer ${OPENAI_API_KEY}"
    cache:
      ttl: 24h        # 未設定なら記録は無期限に有効
```

同じリクエストの 2 回目以降はキャッシュから返るので、**上流は呼ばれず、料金も発生せず、
応答も即座**です。どこから応答したかは `X-Llm-Mock-Source` ヘッダーで分かります。

```bash
$ curl -D- -X POST .../openai/v1/chat/completions -d '{...}' | grep -i x-llm-mock-source
X-Llm-Mock-Source: upstream      ← 1 回目。本物を呼んだ

$ curl -D- -X POST .../openai/v1/chat/completions -d '{...}' | grep -i x-llm-mock-source
X-Llm-Mock-Source: cache         ← 2 回目。記録から返した
```

| `X-Llm-Mock-Source` | 意味 |
|---|---|
| `upstream` | 本物の API を呼んだ (課金が発生した) |
| `cache` | `CACHED_PROXY` で記録から返した |
| `recording` | `REPLAY` で記録から返した |

記録はファイルに残るので、**次回の実行では 1 回目からキャッシュヒット**します。
テストを回すたびにフィクスチャが揃っていき、以降は無料かつオフラインで回せます。

### 13.5 記録ファイル

1 リクエスト 1 ファイルの素の JSON です。レビューでき、手で編集もできます。

```json
{
  "key" : "946b4b45f58f4aad",
  "provider" : "OPENAI",
  "recordedAt" : "2026-08-30T12:56:44.927427268Z",
  "request" : {
    "method" : "POST",
    "path" : "/v1/chat/completions",
    "headers" : { "content-type" : [ "application/json" ] },
    "body" : "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"
  },
  "response" : {
    "status" : 200,
    "headers" : { "content-type" : [ "application/json" ] },
    "body" : "{\"id\":\"chatcmpl-9d9b...\",\"object\":\"chat.completion\", ... }"
  }
}
```

- ファイル名は `{provider}__{path}__{key}.json`
  (例: `openai__v1-chat-completions__946b4b45f58f4aad.json`)。
- 本文はテキストならそのまま、バイナリ (Bedrock の event stream) なら `bodyBase64` に入ります。
- **資格情報は記録されません。** `Authorization` / `x-api-key` / `x-goog-api-key` などの
  ヘッダーと `?key=` などのクエリは `REDACTED` に置換されます。記録をリポジトリにコミットする
  前提の設計です。
- **応答本文を手で書き換えれば**、そのまま再生されます。本物の応答を土台にして
  エッジケースを作る、といった使い方ができます。

記録は Admin API からも見られます。

```bash
curl http://localhost:8080/__admin/recordings          # 一覧 (本文なし)
curl http://localhost:8080/__admin/recordings/946b4b45f58f4aad   # 1 件の全内容
curl -X POST http://localhost:8080/__admin/recordings/reload     # ディレクトリを再スキャン
curl -X DELETE http://localhost:8080/__admin/recordings          # 全削除
```

`reload` は、**サーバーを起動したまま記録ファイルを追加・編集したとき**に使います。

### 13.6 どの記録が選ばれるか (照合キー)

キーは **provider・HTTP メソッド・パス・クエリ・リクエストボディ**のハッシュです。

| 変えると別の記録になるもの | 変えても同じ記録に当たるもの |
|---|---|
| リクエストボディ (プロンプト、model、temperature…) | リクエストヘッダー全般 |
| パス | **API キー** (ヘッダーでもクエリでも) |
| `alt=sse` など応答形式を変えるクエリ | クエリパラメータの**順序** |

**API キーが違っても同じ記録に当たる**のは意図的です。記録時と別のキー (ダミー) で
再生できなければ意味がないためです。

一方、**プロンプトが 1 文字でも違えば別の記録**になります。プロンプトにタイムスタンプや
UUID を埋め込むアプリでは、毎回キャッシュミスして上流を呼んでしまうので注意してください
(その場合は `MOCK` モードのスタブのほうが向いています)。

### 13.7 Bedrock の AWS SigV4 付け直し

Bedrock だけは `Authorization` ヘッダーをそのまま転送**できません**。SigV4 署名は
Host ヘッダー・パス・クエリ・ボディを対象に計算されるため、アプリが llm-mock 向けに
作った署名は本物のエンドポイントでは必ず無効になるからです。

そこで**転送前に署名を付け直します**。

```yaml
llm-mock:
  mode: PROXY
  proxy:
    targets:
      bedrock: https://bedrock-runtime.us-east-1.amazonaws.com
    sigv4:
      bedrock:
        enabled: true
        # region  : 未指定ならターゲットのホスト名から抽出 (bedrock-runtime.us-east-1... → us-east-1)
        # service : 未指定なら "bedrock"
        # 資格情報: 未指定なら AWS の標準プロバイダチェーン
        #           (環境変数・~/.aws/credentials・インスタンスロールがそのまま使えます)
        access-key-id: "${AWS_ACCESS_KEY_ID}"
        secret-access-key: "${AWS_SECRET_ACCESS_KEY}"
        session-token: "${AWS_SESSION_TOKEN}"      # 一時資格情報を使う場合
```

これで**アプリ側はダミーの鍵のまま**本物の Bedrock を呼べます。本物の資格情報は
llm-mock の設定内にとどまります。

`enabled: false` (既定) なら `Authorization` はそのまま転送されます
(モックを別のモックにプロキシする場合など)。

### 13.8 プロバイダーごとにモードを変える

```yaml
llm-mock:
  mode: MOCK
  provider-modes:
    openai: PROXY      # OpenAI だけ本物を叩いて記録し、他はモックのまま
```

「OpenAI の応答だけは本物が欲しいが、Bedrock はモックで十分」といった使い分けができます。

### 13.9 実運用での注意

- **`X-Mock-*` ヘッダーは転送されません。** llm-mock 自身への制御ヘッダーであり、
  本物の API にとってはノイズだからです。
- `/__admin` は**どのモードでも常にローカルで処理**されます。
- `Accept-Encoding` は `identity` に置き換えられます (記録が読める形で残るようにするため)。
- 記録は**同一リクエストに 1 応答**です。「呼ぶたびに違う応答」が必要なら、
  記録ではなく `remainingUses` 付きのスタブを使ってください。

---

## 14. コスト分析

llm-mock は、**どのモデルがどれだけトークンを消費したか**を記録します。
プロキシした本物の呼び出しについては、そのまま**実際の課金額の見積もり**になります。

### 14.1 単価を設定する

```yaml
llm-mock:
  cost:
    enabled: true
    currency: USD
    pricing:                        # 100 万トークンあたりの単価。上から順に最初に一致したもの
      - model-pattern: "^gpt-4o$"
        input: 2.50
        output: 10.00
        cache-read: 1.25
      - model-pattern: "^claude-sonnet"
        input: 3.00
        output: 15.00
```

**単価表は同梱していません (既定は空です)。** ベンダーの価格は変わるので、古い数値を
埋め込むと「自信たっぷりに間違った合計」を黙って出すことになるためです。
単価が無いモデルもトークン数は集計され、`unpricedModels` に名前が出ます。

### 14.2 集計を見る

```bash
curl 'http://localhost:8080/__admin/usage/summary'
```

```json
{
  "currency": "USD",
  "byModel": [
    { "provider": "OPENAI", "model": "gpt-4o", "requests": 1,
      "inputTokens": 4, "outputTokens": 7, "totalTokens": 11,
      "cacheReadTokens": 0, "cacheWriteTokens": 0,
      "cost": 0.0000800000, "priced": true }
  ],
  "totals": {
    "requests": 1, "inputTokens": 4, "outputTokens": 7, "totalTokens": 11,
    "cost": 0.0000800000,
    "upstreamRequests": 0, "cacheHits": 0
  },
  "unpricedModels": []
}
```

`totals` には、本物を呼んだ分とキャッシュで浮いた分が別々に出ます。

| フィールド | 意味 |
|---|---|
| `upstreamCost` | **実際に発生した費用** (本物を呼んだ分) |
| `cacheSavings` | **キャッシュヒットが節約した費用** |
| `upstreamRequests` / `cacheHits` | それぞれの回数 |
| `priced` | 単価が設定されていたか。`false` ならトークンだけ集計 |

### 14.3 発生源で絞り込む

| `source` | 意味 |
|---|---|
| `UPSTREAM` | 本物の API を呼んだ。**実際に発生した費用** |
| `CACHE` | `CACHED_PROXY` のキャッシュヒット。**節約できた費用** |
| `RECORDING` | `REPLAY` での再生。オフラインのテストデータ |
| `MOCK` | スタブエンジンが生成。トークン数は合成値 |

```bash
# 実際に課金された分だけ見る
curl 'http://localhost:8080/__admin/usage/summary?source=UPSTREAM'

# モデル別の明細 (新しい順)
curl 'http://localhost:8080/__admin/usage?model=gpt-4o&limit=20'

# プロバイダーで絞る
curl 'http://localhost:8080/__admin/usage/summary?provider=ANTHROPIC'

# 消費記録を消す
curl -X DELETE http://localhost:8080/__admin/usage
```

### 14.4 使いどころ

- **「この機能の 1 回の実行でいくらかかるか」**を、本物を 1 回だけ呼んで測る
  (`CACHED_PROXY` + `source=UPSTREAM`)。
- **プロンプト改善の前後比較。** 入力トークンが何割減ったかを `byModel` で見る。
- **CI での回帰検出。** `totalTokens` が想定より増えていたらプロンプトが膨らんだサイン。
- **キャッシュの効果測定。** `cacheSavings` と `cacheHits` を見る。

> 明細は既定で **100 万件**保持され、超えると古いものから削除されます
> (`llm-mock.cost.max-entries`)。1 行あたり約 150 バイト、100 万件で約 150MB のヒープを
> 使うので、長時間動かすなら `-Xmx` を確認するか `max-entries` を下げてください。
> 恒久的な課金台帳ではなく、テスト実行単位の分析用と考えてください。

---

## 15. 設定リファレンス

### 15.1 全体

| キー | 既定値 | 意味 |
|---|---|---|
| `llm-mock.mode` | `MOCK` | `MOCK` / `PROXY` / `REPLAY` / `CACHED_PROXY` |
| `llm-mock.provider-modes.{provider}` | — | プロバイダーごとのモード上書き |
| `llm-mock.default-response-template` | `[llm-mock] echo: {{prompt}}` | 既定応答のテンプレート |
| `llm-mock.require-auth` | `false` | `true` で資格情報ヘッダーが無いと 401 |
| `server.port` | `8080` | 待ち受けポート |

### 15.2 URL プレフィックス

| キー | 既定値 |
|---|---|
| `llm-mock.paths.openai` | `/openai` |
| `llm-mock.paths.anthropic` | `/anthropic` |
| `llm-mock.paths.gemini` | `/gemini` |
| `llm-mock.paths.bedrock` | `/bedrock` |

```bash
# プレフィックスを短くする
java -jar app.jar --llm-mock.paths.openai=/oai
# → http://localhost:8080/oai/v1/chat/completions
```

> プレフィックスを空 (`""`) にしてルート直下にマウントすると、パスからプロバイダーを
> 判別できないため**プロキシ／再生の対象外**になります。`MOCK` モードでは使えます。

### 15.3 ストリーミング

| キー | 既定値 | 意味 |
|---|---|---|
| `llm-mock.stream.words-per-chunk` | `3` | 1 チャンクあたりの単語数 |
| `llm-mock.stream.delay-ms` | `0` | チャンク間の待機時間 |

### 15.4 リクエスト記録

| キー | 既定値 | 意味 |
|---|---|---|
| `llm-mock.recording.enabled` | `true` | リクエストログを取るか |
| `llm-mock.recording.max-entries` | `1000` | 保持件数。超過分は古いものから削除 |
| `llm-mock.recording.capture-request-body` | `true` | リクエスト本文を保存するか |
| `llm-mock.recording.max-body-bytes` | `262144` | 保存する本文の最大バイト数 |

### 15.5 プロキシ／再生

| キー | 既定値 | 意味 |
|---|---|---|
| `llm-mock.proxy.recordings-dir` | `./recordings` | 記録ファイルの置き場 |
| `llm-mock.proxy.record` | `true` | `PROXY` 時に記録を書くか |
| `llm-mock.proxy.targets.{provider}` | — | 上流のベース URL |
| `llm-mock.proxy.headers.{provider}.{name}` | — | 転送時に付与するヘッダー |
| `llm-mock.proxy.redact-headers` | `authorization`, `x-api-key`, `x-goog-api-key` ほか | 記録から伏せるヘッダー |
| `llm-mock.proxy.redact-query-params` | `key`, `access_token` | 記録から伏せるクエリ |
| `llm-mock.proxy.connect-timeout` | `10s` | 上流への接続タイムアウト |
| `llm-mock.proxy.request-timeout` | `120s` | 上流への要求タイムアウト |
| `llm-mock.proxy.cache.ttl` | 未設定 (無期限) | `CACHED_PROXY` で記録が陳腐化するまで |
| `llm-mock.proxy.cache.source-header` | `true` | `X-Llm-Mock-Source` を付けるか |
| `llm-mock.proxy.sigv4.bedrock.enabled` | `false` | SigV4 の付け直し |
| `llm-mock.proxy.sigv4.bedrock.region` / `.service` | 自動判定 | 署名対象のリージョン／サービス |
| `llm-mock.proxy.sigv4.bedrock.access-key-id` ほか | AWS 標準チェーン | 署名に使う資格情報 |
| `llm-mock.replay.fallback` | `MOCK` | 記録が無いとき。`MOCK` / `NOT_FOUND` |

### 15.6 コスト

| キー | 既定値 | 意味 |
|---|---|---|
| `llm-mock.cost.enabled` | `true` | トークン消費を記録するか |
| `llm-mock.cost.currency` | `USD` | 表示通貨 (計算はしません。ラベルです) |
| `llm-mock.cost.max-entries` | `1000000` | 明細の保持件数 |
| `llm-mock.cost.pricing[].model-pattern` | — | モデル名の正規表現 |
| `llm-mock.cost.pricing[].input` / `.output` / `.cache-read` / `.cache-write` | — | 100 万トークンあたりの単価 |

### 15.7 モデル一覧と埋め込み

| キー | 既定値 |
|---|---|
| `llm-mock.models.openai` | `[gpt-4o, gpt-4o-mini, gpt-4.1, o3-mini]` |
| `llm-mock.models.anthropic` | `[claude-opus-4-5, claude-sonnet-4-5, claude-haiku-4-5]` |
| `llm-mock.models.gemini` | `[gemini-2.5-pro, gemini-2.5-flash, text-embedding-004]` |
| `llm-mock.models.bedrock` | `[anthropic.claude-sonnet-4-5-20250929-v1:0, amazon.nova-pro-v1:0, ...]` |
| `llm-mock.embedding.openai-dimensions` | `1536` |
| `llm-mock.embedding.gemini-dimensions` | `768` |

---

## 16. Admin API リファレンス

`/__admin` 配下はどのプロバイダープレフィックスとも衝突しない管理面です。
**どのモードでも常にローカルで処理**されます (プロキシされません)。

| メソッド | パス | 用途 |
|---|---|---|
| `GET` | `/__admin/health` | 稼働確認。モードとスタブ／記録の件数 |
| `GET` | `/__admin/stubs` | スタブ一覧 |
| `POST` | `/__admin/stubs` | スタブ登録 (**同名は置換**) |
| `GET` | `/__admin/stubs/{id}` | スタブ 1 件 |
| `PUT` | `/__admin/stubs/{id}` | スタブ更新 (**全フィールド置換**) |
| `DELETE` | `/__admin/stubs/{id}` | スタブ 1 件削除 |
| `DELETE` | `/__admin/stubs` | スタブ全削除 |
| `GET` | `/__admin/requests` | リクエストログ検索 (`provider` `model` `endpoint` `limit`) |
| `DELETE` | `/__admin/requests` | リクエストログ全削除 |
| `POST` | `/__admin/reset` | **スタブ + リクエストログ + 消費記録**を全削除 |
| `GET` | `/__admin/usage` | トークン消費の明細 (`provider` `model` `source` `limit`) |
| `GET` | `/__admin/usage/summary` | コスト集計 (`provider` `source`) |
| `DELETE` | `/__admin/usage` | 消費記録の全削除 |
| `GET` | `/__admin/recordings` | プロキシ記録の一覧 |
| `GET` | `/__admin/recordings/{key}` | 記録 1 件の全内容 |
| `POST` | `/__admin/recordings/reload` | 記録ディレクトリの再スキャン |
| `DELETE` | `/__admin/recordings` | 記録の全削除 (**ファイルも消えます**) |
| — | `/__admin/h2` | H2 コンソール (ブラウザで開く) |

**H2 コンソール**は `jdbc:h2:mem:llmmock`、ユーザー `sa`、パスワード空で接続できます。
スタブやログを SQL で直接見たいときに便利です。

---

## 17. トラブルシューティング

### スタブが効かない

| 確認すること | 対処 |
|---|---|
| `promptPattern` が**会話全体** (`user: ...` 形式) に対する正規表現になっているか | `^` を先頭に付けていると当たりません → [8.3](#83-照合の仕組み--何にマッチするのか) |
| 他のスタブが先に当たっていないか | `GET /__admin/requests` の `matchedStub` を見る |
| `priority` が低くないか | 大きいほど優先。同点なら**古い方**が勝つ |
| `remainingUses` を使い切っていないか | `GET /__admin/stubs` で残数を確認 |
| `enabled: false` になっていないか | 同上 |
| `provider` を絞りすぎていないか | 全社に効かせるなら `ANY` (既定) |
| `endpointPattern` にパスを書いていないか | 対象は `chat.completions` などの**エンドポイント名** |

いちばん確実な切り分けは、**`GET /__admin/requests?limit=1` の `matchedStub` を見る**ことです。
`null` なら「どのスタブにも当たらず既定テンプレートで応答した」と分かります。

### 404 が返る

| 症状 | 原因 |
|---|---|
| どのエンドポイントも 404 | base URL のプレフィックスが違う。OpenAI だけ `/v1` まで必要 → [4.1](#41-base-url-一覧) |
| Bedrock だけ 404 | モデル ID の `:` を `%3A` にエンコードしていない (SDK 経由なら自動) |
| `X-Mock-Stub` 指定で 404 | その名前のスタブが無い。`{"error":{"message":"No stub named 'nope'"}}` |
| `REPLAY` で 404 | 一致する記録が無い。`fallback: MOCK` にするか記録を取り直す → [13.6](#136-どの記録が選ばれるか-照合キー) |
| `PROXY` で 404 | **上流の base URL が違う。** 上流が別の llm-mock なら `http://host:8081/openai` のように**プレフィックスまで**含める |

### `PROXY` にしたのに本物が呼ばれない / 呼ばれすぎる

- `provider-modes` で個別に上書きしていないか確認してください。
- `CACHED_PROXY` で毎回上流が呼ばれるなら、**リクエストボディが毎回違う**のが原因です
  (プロンプト内のタイムスタンプ・UUID・ランダムな ID など)。照合キーはボディのハッシュです。
- `cache.ttl` が短すぎないか確認してください。未設定なら無期限です。

### 記録ファイルを編集したのに反映されない

サーバー起動時にスキャンした内容をメモリに持っています。
`POST /__admin/recordings/reload` を呼んでください。

### 401 が返る

`llm-mock.require-auth: true` になっています。SDK 側にダミーで構わないので資格情報を
設定してください。

```json
{"error":{"message":"Missing credentials for OPENAI","type":"authentication_error",
          "param":null,"code":"invalid_api_key"}}
```

チェックしているのは**ヘッダーの有無だけ**で、値の中身は見ていません
(Bedrock は SigV4 の検証をしません)。

### テストが不安定 / 前のテストの結果が漏れる

`@BeforeEach` で `POST /__admin/reset` を呼んでいるか確認してください。
テストを並列実行しているなら、状態はサーバー全体で共有されるため、
**テストクラスごとに別インスタンス**を立てるか、`X-Mock-*` ヘッダーだけで完結させてください。

### ストリーミングが 1 チャンクで返ってくる

応答本文が短いと単語数が `words-per-chunk` に届かず 1 チャンクになります。
長い `responseText` を設定するか、`llm-mock.stream.words-per-chunk: 1` にしてください。

### Anthropic で 400 になる

`max_tokens` は Anthropic では**必須**です (本物と同じ挙動)。他の 3 社では任意です。

### ポートが使用中

```
Web server failed to start. Port 8080 was already in use.
```

`--server.port=0` でランダムな空きポートを使うか、既存のプロセスを止めてください。

---

## 18. FAQ

**Q. 本物の API キーは必要ですか?**
A. `MOCK` と `REPLAY` では不要です。`PROXY` / `CACHED_PROXY` で本物に転送するときだけ必要で、
その場合も**llm-mock の設定に置くだけ**で、アプリ側はダミーのままで構いません。

**Q. 応答は毎回同じですか?**
A. 本文・トークン数・埋め込みベクトルは決定論的です。一方、`id` (`chatcmpl-...`、`msg_...`) と
`created` タイムスタンプは**毎回変わります**。本物と同じく、これらに依存したテストを
書かせないための意図的な仕様です。

**Q. データは永続化されますか?**
A. スタブとリクエストログはインメモリ H2 なので、**再起動すると消えます**。
記録 (recordings) だけはファイルなので残ります。

**Q. `n > 1` (複数候補) は使えますか?**
A. 未対応で、常に 1 候補を返します。

**Q. 画像や音声を送れますか?**
A. リクエストとしては**受理されます**が、プロンプト文字列には寄与しないので、
スタブの `promptPattern` の照合対象になるのはテキストパートだけです。

**Q. Python / TypeScript / Go の SDK でも動きますか?**
A. 動くはずですが、**実際に検証したのは 4 社の公式 Java SDK に対してのみ**です。
同じ HTTP 仕様に従うクライアントであれば問題ありません。

**Q. 1 つのモックで複数のテストスイートを共有できますか?**
A. 技術的には可能ですが、`reset` が互いを壊すのでおすすめしません。
スイートごとにインスタンスを立ててください (`--server.port=0` が便利です)。

**Q. 記録をリポジトリにコミットしてよいですか?**
A. そのために資格情報を自動で伏せる設計になっています。ただし**プロンプト本文と応答本文は
そのまま残る**ので、機密情報を含む会話を記録した場合は中身を確認してからコミットしてください。

**Q. HTTPS で待ち受けられますか?**
A. Spring Boot の標準機能 (`server.ssl.*`) で設定できます。テスト用途では通常不要です。

---

## 19. 制限事項

| 項目 | 内容 |
|---|---|
| SigV4 の検証 | しません。Bedrock では `Authorization` の**有無**しか見ません |
| `anthropic-version` ヘッダー | 必須にしていません (本物は無いと 400) |
| 非テキストパート | 受理はしますが、スタブ照合の対象になりません |
| `n > 1` | 未対応。常に 1 候補 |
| 公式 SDK の検証範囲 | **Java SDK のみ**。Python / TypeScript / Go は未検証 |
| プロキシの検証範囲 | **本物のベンダー API に対しては未検証**です (上流役に本アプリの別インスタンスを使用) |
| コスト集計 | `max-entries` (既定 100 万件) を超えると古い明細から削除され、合計が下振れします |
| 単価表 | 同梱していません。自分で設定する必要があります |
| 記録の応答パターン | 同一リクエストに 1 応答のみ。「呼ぶたびに違う応答」は `remainingUses` 付きスタブで |

より詳しい背景は [README の 11 章](../README.md#11-既知の制限) を参照してください。
