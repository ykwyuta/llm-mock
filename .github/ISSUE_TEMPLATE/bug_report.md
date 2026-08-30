---
name: バグ報告
about: 期待どおりに動かない箇所を報告する
labels: bug
---

## 症状

<!-- 何が起きたか。エラーメッセージがあれば貼ってください -->

## 再現手順

<!--
可能なら curl で再現する形が最も助かります。例:

1. `./mvnw spring-boot:run` で起動
2. 次を実行:
   ```bash
   curl -X POST http://localhost:8080/openai/v1/chat/completions \
     -H 'content-type: application/json' \
     -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}'
   ```
3. `...` が返る
-->

## 期待した結果

## 環境

- llm-mock のバージョン / コミット:
- Java:
- OS:
- モード (`llm-mock.mode`): MOCK / PROXY / REPLAY / CACHED_PROXY
- 使っている SDK とバージョン (あれば):

## 補足

<!--
スタブが効かない場合は、次の出力が原因の切り分けに役立ちます。

curl 'http://localhost:8080/__admin/requests?limit=1'   # matchedStub が null か
curl http://localhost:8080/__admin/stubs                # 登録済みのスタブ
-->
