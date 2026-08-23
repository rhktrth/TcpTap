# TcpTap

TcpTap は、TCP 接続を別のホスト／ポートへ中継しながら、セッションの状態を観測できる軽量な TCP 中継ツールです。

アプリケーションプロトコルを変更せず、バイトストリームを双方向に転送します。必要に応じて、TcpTap が観測したストリームを再構成した擬似 TCP/IP パケットと、TcpTap 自身が観測した診断イベントを、Wireshark 等で開ける pcapng ファイルとして保存できます。

現在の詳細な外部仕様は [`docs/EXTERNAL_DESIGN.md`](https://github.com/rhktrth/TcpTap/blob/main/docs/EXTERNAL_DESIGN.md) を参照してください。

## 特徴

- 特定のTCP ポートで接続を待ち受け、当該ポートに接続があると、別途、中継先ホスト／ポートに TCP 接続し、2つの TCP 接続間で双方向にデータを中継します。
- セッションごとに、接続元、中継先への接続時間、継続時間、方向別のバイト数、終了状態を表示します。
- 中継先への接続失敗はセッション単位で処理し、待受処理は継続します。
- TCP のハーフクローズ（片方向の終了）を反対側へ伝播します。
- `--capture` を指定すると、TcpTap が観測したバイトストリームを再構成した擬似表現と、中継先への接続失敗などの診断情報を pcapng に保存できます。
- Npcap / libpcap、パケットキャプチャ権限、ネイティブライブラリは不要です。
- **JRE 8 以上**で実行できます。

## 動作環境

- **JRE 8 以上**
- TCP/IP を利用できる環境

配布 ZIP 内の JAR は Java 8 のバイトコード／API 互換性を維持し、新しい Java 実行環境でも動作確認します。

## インストール

[GitHub Releases](https://github.com/rhktrth/TcpTap/releases) から `TcpTap-<version>.zip` を取得して展開し、ZIP 内の `TcpTap.jar` を任意のディレクトリに配置します。インストーラーはありません。

```console
java -jar TcpTap.jar --help
```

アンインストールは、配置したファイルを削除するだけです。

## 使い方

次の例では、待受アドレスを省略して既定の `127.0.0.1` を使用し、`127.0.0.1:1234` で待ち受けて `10.1.1.1:22` へ中継します。

```console
java -jar TcpTap.jar --listen-port 1234 --dest-host 10.1.1.1 --dest-port 22
```

ほかのホストから接続させる場合は、待受アドレスを明示します。たとえば、すべての IPv4 インターフェースで待ち受ける場合は次のようにします。

```console
java -jar TcpTap.jar --listen-host 0.0.0.0 --listen-port 1234 --dest-host 10.1.1.1 --dest-port 22
```

TcpTap が観測したストリームと診断情報を `session.pcapng` に保存する場合は、次のようにします。

```console
java -jar TcpTap.jar --listen-port 8443 --dest-host 10.1.1.1 --dest-port 443 --capture session.pcapng
```

キャプチャファイルは新規作成します。指定したファイルがすでに存在する場合は上書きせず、起動エラーにします。

オプションを省略した場合は `127.0.0.1:8080 -> localhost:80` を使用し、中継先への接続タイムアウトは10000msです。

```text
--listen-host <HOST>       待受アドレス。既定は127.0.0.1
--listen-port <PORT>       待受TCPポート。1..65535、既定は8080
--dest-host <HOST>         中継先ホスト。既定はlocalhost
--dest-port <PORT>         中継先TCPポート。1..65535、既定は80
--connect-timeout <MILLIS> 中継先への接続タイムアウト。1以上、既定は10000
--capture <FILE>           TcpTapが観測したストリームと診断情報をpcapngへ保存。既存ファイルは上書きしない
-h, --help                 ヘルプを表示
```

未知のオプション、同じオプションの重複、値の欠落、不正な値はコマンド指定エラーです。ヘルプ表示と入力検証には picocli を使用します。

### 終了コード

| コード | 意味 |
| ---: | --- |
| `0` | ヘルプ表示などの正常終了 |
| `1` | キャプチャ初期化、待受処理などの起動・実行エラー |
| `2` | CLI の指定エラー |

CLI の指定エラーでは Java のスタックトレースを表示しません。

## セキュリティ上の注意

TcpTap 自体はクライアントの認証、認可、アクセス制御を提供しません。`--listen-host 0.0.0.0` や外部インターフェースのアドレスへバインドする場合は、OS のファイアウォールやネットワーク側の制御で、TcpTap へ到達できる範囲を利用者が管理してください。

`--capture` で生成する pcapng には、TcpTap が入力ストリームから観測したアプリケーションデータが保存されます。通信内容に認証情報、トークン、個人情報、機密データなどが含まれる場合、キャプチャファイルにも含まれ得ます。保存先、アクセス権、保管期間、廃棄方法を通信内容に応じて管理してください。

## 実行時出力

セッションごとに、概ね次の情報を出力します。

```text
2026-08-22T09:00:00Z CAPTURE file=session.pcapng mode=reconstructed-stream
2026-08-22T09:00:00Z LISTEN 127.0.0.1:8443 -> 10.1.1.1:443 connect_timeout=10000ms
2026-08-22T09:00:03Z #000001 ACCEPT client=127.0.0.1:54321
2026-08-22T09:00:03Z #000001 CONNECT destination=10.1.1.1:443 2.341ms
#000001 CLOSE duration=5.218s c2d=812B d2c=2431B c2d_end=EOF d2c_end=EOF
```

## pcapng の注意

TcpTap が生成する pcapng は**実際のパケットキャプチャではありません**。

pcapng には2種類のインターフェースを持たせます。

- `tcptap-reconstructed`: TcpTap が入力ストリームから観測したアプリケーションのバイトストリームを、擬似 TCP/IP パケットとして再構成したものです。プロトコル解析や Wireshark の Follow TCP Stream による内容確認を目的とします。
- `tcptap-diagnostics`: TcpTap 自身が直接観測した診断イベントです。中継先への接続に失敗した場合は `CONNECT_ERROR` として、セッション ID、中継先、Java 例外、メッセージを記録します。

`tcptap-reconstructed` のペイロードは、TcpTap が読み取った時点で観測したデータです。その後の反対側への書込みが失敗した場合でもキャプチャに残るため、キャプチャに存在すること自体は反対側アプリケーションへの配送成功を意味しません。

`CONNECT_ERROR` は、ネットワーク上で実際に観測したパケットを表すものではありません。たとえば `Connection refused` が記録されても、TcpTap はネットワーク上の RST、ICMP、ファイアウォールの動作などを推測してパケットを生成しません。診断イベントは UTF-8 の JSON データとパケットコメントで保存するため、Wireshark 用の追加プラグインがなくても概要を確認できます。

実パケットのシーケンス番号、再送、パケット損失、ウィンドウ、パケット境界、通信上の実時刻、TTL など、トランスポート層／ネットワーク層の解析には使用できません。pcapng 自体にも、実際の通信をキャプチャしたものではないことを記録します。

外部から見えるキャプチャの仕様は [`docs/EXTERNAL_DESIGN.md`](https://github.com/rhktrth/TcpTap/blob/main/docs/EXTERNAL_DESIGN.md)、内部のキャプチャ設計は [`docs/INTERNAL_DESIGN.md`](https://github.com/rhktrth/TcpTap/blob/main/docs/INTERNAL_DESIGN.md) を参照してください。

## ライセンス

このソフトウェアは MIT License の下で公開されています。詳細は `LICENSE.txt` を参照してください。
