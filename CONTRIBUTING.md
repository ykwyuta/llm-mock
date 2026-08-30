# コントリビューションガイド

llm-mock への貢献を歓迎します。バグ報告・機能提案・プルリクエストのいずれも、
まずは [Issue](https://github.com/ykwyuta/llm-mock/issues) を立ててもらえると話が早いです。

参加にあたっては [行動規範](CODE_OF_CONDUCT.md) に同意したものとみなします。

## 開発環境

| 必要なもの | バージョン |
|---|---|
| JDK | 21 以上 |
| Maven | 3.9 以上 (`./mvnw` を使えば個別のインストールは不要) |

```bash
git clone https://github.com/ykwyuta/llm-mock.git
cd llm-mock
./mvnw test            # テストを全部走らせる
./mvnw spring-boot:run # http://localhost:8080 で起動
```

**ネットワークが必要なのは依存の取得だけです。** テストは 4 社の本物の API を一切呼びません
(プロキシ機能のテストは、このアプリのもう 1 インスタンスを上流役として使っています)。
API キーも AWS の資格情報も不要です。

## プルリクエストの前に

1. **`./mvnw test` が緑であること。** これが唯一の必須条件です。
2. **変更に対応するテストがあること。** 下の「どこにテストを書くか」を参照してください。
3. **README / `docs/USAGE.md` の記述と実装がずれていないこと。**
   設定項目・エンドポイント・ヘッダーを増やしたら、ドキュメントも同じ PR で更新してください。
4. コミットメッセージは**何をなぜ変えたか**が分かる粒度で。

## コードの歩き方

```
src/main/java/com/github/llmmock/
├── core/          正規化モデル (MockRequest / MockCompletion) と応答決定エンジン
├── provider/      4 社ごとの HTTP アダプター。各社の DTO ⇄ 正規化モデル
│   ├── openai/    anthropic/  gemini/  bedrock/
│   └── common/    SSE 書き出し、認証チェックなど共有部品
├── proxy/         PROXY / REPLAY / CACHED_PROXY を実現するサーブレットフィルタ
├── usage/         トークン消費の抽出とコスト集計
├── store/         スタブルールとリクエストログ (JPA + H2)
├── admin/         /__admin の管理 API
└── config/        設定プロパティとフィルタ登録
```

設計の背景は [README.md](README.md) の「2. 設計」を読んでください。要点は、
**4 つのアダプターが正規化モデルに変換し、中核ロジックはプロバイダーを知らない**ことです。
プロバイダー固有の分岐を `core/` に持ち込まないでください。

### よくある変更の手順

**エンドポイントを追加する**

1. `provider/{vendor}/{Vendor}Dtos.java` にリクエスト／レスポンスの型を足す
2. `{Vendor}Controller.java` にハンドラを足し、`MockRequest` に変換して `MockEngine` に渡す
3. `provider/{vendor}/{Vendor}ApiTest.java` に HTTP レベルのテストを足す
4. その SDK がそのエンドポイントを使うなら `sdk/{Vendor}SdkTest.java` にも足す

プロキシ／再生への対応は**不要**です。サーブレットフィルタでバイト列をそのまま扱うため、
新しいエンドポイントは自動的に対象になります。

**設定項目を追加する**

1. `config/LlmMockProperties.java` にフィールドと Javadoc を足す
2. `src/main/resources/application.yml` に既定値とコメントを書く
3. `docs/USAGE.md` の「15. 設定リファレンス」に追記する

**スタブの機能を追加する**

`store/StubRule.java`、`admin/StubRuleDto.java`、`core/MockEngine.java` の 3 箇所と、
1 回限りの上書きも要るなら `core/MockOverrides.java` を触ります。
`core/MockEngineTest.java` に決定順序のテストを足してください。

## どこにテストを書くか

| 種類 | 置き場所 | 何を守るためのものか |
|---|---|---|
| 単体 | `core/*Test.java` | トークン計算・チャンク分割・埋め込みなどのロジック |
| HTTP レベル | `provider/{vendor}/{Vendor}ApiTest.java` | 各社の仕様どおりの受理／応答形状／エラー封筒 |
| SDK レベル | `sdk/{Vendor}SdkTest.java` | **本物の公式 SDK が実際に動くこと** |
| 横断 | `CrossProviderTest.java` | 1 つのスタブが 4 プロトコルすべてに効くこと |
| プロキシ／再生 | `proxy/*Test.java` | 記録・再生・キャッシュ・SigV4 |

**HTTP レベルのテストだけでは足りません。** 実際、Gemini SDK が内部的に呼ぶ
`:batchEmbedContents` の未実装は、SDK レベルのテストを足すまで見つかりませんでした
(詳細は README の 10.2)。新しいエンドポイントは、可能なら SDK からも呼ばせてください。

## コードスタイル

特別なフォーマッタは使っていません。**周りのコードに合わせてください。**

- インデント 4 スペース、1 行はおおむね 100 文字まで
- コメントは「何をしているか」ではなく**「なぜそうしているか」**を書く
  (既存のコメントがその調子で書かれています)
- 例外は `MockApiException` に寄せる。各社のエラー封筒への変換は
  `{Vendor}ExceptionHandler` の仕事です
- 公開クラス・非自明なメソッドには Javadoc を付ける

## ドキュメントの言語

README・利用ガイドは日本語、コード内のコメントと Javadoc は英語で書かれています。
既存の使い分けに合わせてください。

## ライセンス

このプロジェクトは [MIT License](LICENSE) です。
プルリクエストを送った時点で、その貢献が同じ MIT License の下で公開されることに
同意したものとみなします。
