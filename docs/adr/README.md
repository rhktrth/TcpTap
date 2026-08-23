# アーキテクチャ判断記録（ADR）

`docs/adr/` は、TcpTap の**現在有効な重要な設計判断と、その理由**だけを置く場所です。変更履歴の台帳にはしません。

判断が変わった場合は、現在の設計を最も簡潔に表すよう、既存 ADR を編集・統合・削除します。置き換え済み／廃止済みの ADR は保存しません。過去の経緯は Git の履歴、Issue、PR を参照します。

現在の外部仕様そのものは [`../EXTERNAL_DESIGN.md`](../EXTERNAL_DESIGN.md)、現在の内部設計そのものは [`../INTERNAL_DESIGN.md`](../INTERNAL_DESIGN.md) を正本とします。ADR はそれらを重複して説明する文書ではなく、「なぜその重要な設計を選ぶのか」「どの前提が変われば見直すのか」を扱います。

## 現在有効な ADR

- [ADR-0001: JRE 8互換の小規模CLIとして実装を閉じる](0001-minimal-java8-cli.md)
- [ADR-0002: パケットではなく OS が再構成した TCP ストリームを観測境界とする](0002-observe-reconstructed-tcp-stream.md)
- [ADR-0003: 観測したストリームと診断イベントを pcapng として出力する](0003-synthetic-pcapng-from-observed-stream.md)
