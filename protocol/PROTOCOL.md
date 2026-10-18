# JARVIS Link プロトコル v1

PC版・Android版のどちらも同じ仕様の **WebSocketサーバー** を持ち、
相手に用事があるときだけ **クライアントとして接続 → 認証 → 1リクエスト → 切断** します。
常時接続を張らないので、片方が落ちていても、もう片方は単独で動き続けます。

## 接続と発見

| 方法 | 用途 | 内容 |
|---|---|---|
| mDNS | 同一Wi-Fi | サービス種別 `_jarvislink._tcp`。TXTレコード `name` / `platform` |
| 手動ピア | 外出先(Tailscale) | `100.x.y.z:8765` や `my-pc.tailnet-xxxx.ts.net:8765` を設定に列挙 |

既定ポートは `8765`。外出先からはTailscale経由のアドレスを手動ピアに入れてください
(mDNSはTailscale上では届きません)。

## 認証(共有トークンによるHMAC相互認証)

トークン本体はネットワークに流れません。双方がトークンを知っていることを確認し合います。

```
server → {"type":"challenge","v":1,"nonce":"<hex32>","device":{"name":"pc","platform":"windows"}}
client → {"type":"auth","mac":HMAC(token, nonce),"cnonce":"<hex32>","device":{"name":"phone","platform":"android"}}
server → {"type":"auth_ok","mac":HMAC(token, cnonce)}      // 失敗時は {"type":"auth_fail"} を送って切断
```

`HMAC(token, x)` = HMAC-SHA256(key=token(UTF-8), msg=x(UTF-8)) の小文字16進。
クライアントは `auth_ok.mac` を検証し、一致しなければ何も送らずに切断します(なりすましサーバー対策)。

## リクエスト(認証後)

すべて `id`(任意の文字列)を付け、同じ `id` の `result` が返ります。

| type | フィールド | 動作 |
|---|---|---|
| `command` | `text` | 受信側のエージェントが自然文の指示として実行(例: 「ライトをつけて」) |
| `tool` | `name`, `args` | 受信側のツールを直接実行 |
| `speak` | `text` | 受信側で読み上げ |
| `clipboard` | `text` | 受信側のクリップボードに設定 |
| `info` | — | 端末名・プラットフォーム・ツール一覧 |
| `ping` | — | 疎通確認 |

```
→ {"type":"command","id":"a1","text":"ライトをつけて"}
← {"type":"result","id":"a1","ok":true,"text":"ライトをつけました"}
```

## 安全ルール

ツールにはリスク区分があります。

* `safe` — どこからでも実行可
* `remote_blocked` — ローカルの声なら実行、**他端末からの依頼は拒否**(画面操作・画面読み取りなど)
* `confirm` — ローカルでは音声で「はい」の確認後に実行、**他端末からは拒否**(シェル実行・削除・発信など)

設定 `allow_remote_dangerous: true` にすると他端末からの `remote_blocked` / `confirm` も許可されます
(その場合 `confirm` は確認なしで実行されるので、Tailscale等の閉じたネットワークでのみ推奨)。
