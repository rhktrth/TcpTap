# TcpTap 向け AI・開発ルール

このリポジトリは、TCP 通信を中継しながら session と stream を観測・記録できる軽量ツールを、**JRE 8で実行可能な互換性を維持した小さな構成**で保守します。

正本は役割ごとに分けます。

- 文書体系、仕様ID、acceptance、traceability の運用: [`docs/README.md`](docs/README.md)
- 利用者向けの使い方: [`README.md`](README.md)
- 現在の外部仕様: [`docs/EXTERNAL_DESIGN.md`](docs/EXTERNAL_DESIGN.md)
- 現在の内部設計: [`docs/INTERNAL_DESIGN.md`](docs/INTERNAL_DESIGN.md)
- build / test / release: [`docs/OPERATIONS.md`](docs/OPERATIONS.md)
- 現在有効な重要な設計判断と理由: [`docs/adr/`](docs/adr/)

## 仕様駆動開発

TcpTap の機能・挙動・内部構造を変更する場合は、production code を正本として仕様を後付けせず、**現在有効な仕様書を先に改訂してから test / production code を変更する**。

### 作業開始時に読むもの

機能・挙動・構造に関係する変更では、実装を編集する前に少なくとも次を確認する。

1. `docs/README.md`
2. `docs/EXTERNAL_DESIGN.md`
3. `docs/INTERNAL_DESIGN.md`
4. 関係する `docs/adr/*.md`
5. 対象の test
6. 対象の production code

README は利用者向け説明であり、詳細な仕様判断の正本にはしない。

### 変更順序

原則として次の順序で変更する。

1. 要求が外部から観測可能な挙動へ与える影響を確認する。
2. 外部仕様が変わる場合は `docs/EXTERNAL_DESIGN.md` を先に変更し、重要な契約を `EXT-*` として識別する。
3. regression risk がある外部仕様には implementation detail に依存しない `AC-*` を追加・更新する。
4. 更新後の外部仕様を実現する内部構造を検討し、必要なら `docs/INTERNAL_DESIGN.md` の `INT-*` を変更する。
5. 他にも合理的な選択肢があり、後から判断理由を維持する価値がある重要な設計判断なら ADR を追加・変更・統合する。
6. `EXT -> AC -> INT -> test` の対応を確認し、更新した仕様を検証する test を変更・追加する。
7. 最後に production code を変更する。
8. README / OPERATIONS 等の利用者・運用情報へ影響がある場合は対応する正本を更新する。
9. 仕様、acceptance、内部設計、test、implementation が一致していることを確認する。

内部設計の検討によって、当初の外部仕様が実現不能・不適切・過剰であることが判明する場合は、内部実装に合わせて黙って挙動を変えず、`EXTERNAL_DESIGN.md` に戻って外部仕様を明示的に改訂してから先へ進む。

### 正本と矛盾の扱い

- `docs/README.md` は文書の責務境界、stable ID、TBD、acceptance、traceability の運用を定義する。
- `EXTERNAL_DESIGN.md` は外部から観測可能で維持すべき現在の契約を定義する。
- `INTERNAL_DESIGN.md` はその外部契約を実現するために維持すべき現在の component responsibility、lifecycle、concurrency、resource ownership、不変条件を定義する。
- ADR は仕様そのものを重複記載する場所ではなく、「なぜその重要な設計判断を選ぶか」「どの前提が変われば見直すか」を記録する。
- test と source code は仕様を検証・実現する成果物であり、仕様書と矛盾した場合に自動的な正本にはしない。
- 文書間に矛盾がある場合は推測で implementation を選ばず、正本同士の矛盾を先に解消する。
- 現在の要求によって仕様を変更すること自体は許可される。その場合は変更後の仕様書が新しい正本になる。

### 仕様へ昇格させるもの

source code に存在するという理由だけで全実装詳細を仕様へ固定しない。

外部仕様へ記載するのは、実装方式を全面的に変更しても利用者・peer・生成物から観測できる性質として維持すべきものとする。

内部設計へ記載するのは、外部仕様を安定して実現するために実装が維持すべき責務分離、lifecycle、concurrency、resource ownership、error propagation、不変条件とする。

private method 名、local variable、buffer size、単純な helper 分割等は、それ自体が設計要件でない限り仕様へ固定しない。

### Stable ID と acceptance

- 重要な外部契約は `EXT-<AREA>-NNN`、内部設計契約は `INT-<AREA>-NNN`、acceptance は `AC-<AREA>-NNN` で識別する。
- 既存文章の全行へ機械的に ID を付けない。変更・review・test で独立して参照する価値のある契約単位へ付ける。
- `AC-*` は public method や private call order ではなく、CLI、socket、file、stdout / stderr 等から観測可能な Given / When / Then として定義する。
- ID を rename して履歴をきれいに見せるより、意味が同じ契約は同じ ID を維持する。契約を分割・廃止する必要がある場合は PR で理由を明示する。
- test を rename / split / merge した場合、同じ acceptance をどの test が検証するか追跡できる状態を維持する。

### TBD を推測しない

- 未確定事項が implementation を止める場合は `TBD-*` として明示する。
- `TBD-*` または複数解釈できる仕様を、source code の現在動作、一般的 best practice、AI の推測だけで確定しない。
- TBD に依存する production behavior を実装する前に、外部仕様または内部設計で判断を確定する。

### 勝手な要件追加をしない

- 仕様に記載されていない公開挙動を、一般的な best practice、将来拡張、他製品の慣例だけを理由に追加しない。
- 仕様を満たす複数の実装がある場合は、この文書、外部仕様、内部設計、ADR の制約内で最小の実装を選ぶ。
- acceptance に不要な公開 API、CLI option、configuration、abstraction、framework、互換層を追加しない。
- 既存 source から仕様を読み取る場合も、偶然の実装詳細と維持すべき契約を区別する。

## 原則

- **JRE 8互換は明示要件とし、今後も維持する。** CLIや過去option等、それ以外の過去バージョン互換はユーザーが明示的に必要とした場合だけ要件にする。
- 過去の構成、移行経緯、廃止済み仕様をコードや文書へ残さない。履歴は Git history / Issue / PR に任せる。
- ファイル、class、method、設定、dependency を必要最小限にする。使われない内部機能は原則削除する。
- 将来拡張だけを目的とする abstraction、framework、互換層、設定項目を追加しない。
- relay / capture の中心処理は Java SE 標準ライブラリを基本とする。一般化された問題を独自実装し続けるより小さなdependencyを使う方が明確に有利な場合は、JRE 8対応、保守性、配布形態への影響をADRで判断する。
- 現在のCLI parsing / usage生成には picocli を使い、独自parserへ戻さない。
- Maven を build の正本とし、IDE 固有設定を正本にしない。buildで利用するplugin versionと最低build tool versionは明示する。
- TcpTap は packet sniffer ではなく TCP relay を基本とする。OS の TCP stack が再構成した byte stream と、relay 自身が確実に観測できる session event を扱う。
- TcpTap 自体に認証・認可・アクセス制御を持たせない。外部bind時の到達制御はOS/firewall等の責務とし、既定はloopbackに閉じる。
- captureにはapplication payloadが含まれ得るため、保存データを通信内容と同等に機密情報として扱う。

## Java

- JRE 8 を最低動作環境とし、Java 8 bytecode / Java SE 8 API 互換を維持する。
- production code は Java 8 で compile できる構文と Java SE 8 API だけを使う。
- Java 8以上のruntimeで動作できる構成を維持し、特定の新しいJava versionに依存しない。
- Java 8までで利用できる try-with-resources、lambda、method reference、diamond operator、String switch、Stream APIなどは、コードが短く明確になる場合に使う。
- virtual thread、record、pattern matching、`Path.of`、`InputStream.readNBytes`等、Java 8より新しい言語機能/APIをproduction codeやJava 8でcompileするtestへ導入しない。
- 処理の追跡が難しくなる過度な functional style や abstraction は避ける。
- production class は責務が明確に分かれる場合だけ分割する。小さな内部 model / converter は独立 file にせず、所有 class の nested class を優先する。
- Java はスペース4文字、XML / YAML はスペース2文字、UTF-8 / LF とする。共有書式は `.editorconfig` を正本とする。
- コメントはコードから分からない理由・制約だけを書く。過去実装の説明は書かない。

## 文書

- `docs/README.md` は文書体系、外部/内部設計の境界、stable ID、TBD、acceptance、traceability の運用だけを書く。個別 product specification を重複させない。
- README は利用者向けの概要、導入、使い方だけを書く。詳細仕様やbuild / 開発手順の正本にしない。
- EXTERNAL_DESIGN は現在の外部仕様、CLI contract、relay behavior、observable output、capture file contract、trust boundary、外部不変条件、主要 `EXT-*` と `AC-*` を書く。
- INTERNAL_DESIGN は現在の内部構造、responsibility、lifecycle、concurrency、resource ownership、error handling、capture architecture、内部不変条件、主要 `INT-*` と traceability を書く。
- OPERATIONS は build / test / release 方法だけを書く。
- 同じ仕様を複数文書へ詳細に重複記載しない。役割外の詳細は正本への参照で済ませる。
- ADR は現在有効な重要な判断だけを置く。判断が変わったら既存 ADR を編集・統合・削除し、superseded ADR を保存しない。
- ADR は EXTERNAL_DESIGN / INTERNAL_DESIGN の仕様を重複説明せず、「なぜその設計を選ぶか」「どの前提が変われば見直すか」を記録する。
- 変更履歴や「以前はこうだった」という説明は README / docs / comment に蓄積しない。

## テスト

- coverage 率ではなく、TCP relay の双方向性、session lifecycle、half-close、接続失敗、byte count、capture format、CLI contract を壊す不具合の検出を優先する。
- 外部仕様を変更する場合は、その仕様を直接または統合的に検証する `AC-*` と test を先に変更・追加する。
- 内部設計を変更する場合は、private 実装順序ではなく設計上の不変条件が壊れたときに検出できる test を優先する。
- traceability に test gap が明示されている仕様を変更する場合は、その gap を検出する test を変更作業の一部として追加する。
- integration test は loopback のみを使い、通常 CI から外部 host へ接続しない。
- capture format は production code の round-trip だけに依存せず、既知 byte 列または独立した decoder でも検証する。
- CLI library 自体の挙動を重複再実装してtestせず、TcpTapのoption契約と固有validationを検証する。
- 不具合修正では可能なら再現テストを追加する。
- private 実装の呼出順や、廃止した互換挙動だけを固定するテストは残さない。
- 重複するテストは統合し、意味のない fixture / sample は置かない。

## GitHub 上の変更

- 挙動変更・構造変更は Issue と branch / PR を基本とする。
- Issue、PR、コミット説明は日本語で書く。識別子・製品名・技術用語は不自然に日本語化しない。
- unrelated refactoring を同じ PR に混ぜない。ただし最小構成へ整理するため不可分な削除・統合はまとめてよい。
- ユーザーから継続的なmerge指示がある作業では、CI成功と差分確認後にPRをmergeしてよい。

## Definition of Done

1. 外部挙動を変更した場合、`docs/EXTERNAL_DESIGN.md` が変更後の現在仕様を表し、必要な `EXT-*` / `AC-*` が更新されている。
2. 内部構造・設計上の不変条件を変更した場合、`docs/INTERNAL_DESIGN.md` が変更後の現在設計を表し、必要な `INT-*` と traceability が更新されている。
3. 重要な設計判断が変わった場合、現行 ADR が変更後の理由を表している。
4. 変更対象について `EXT -> AC -> INT -> test -> implementation` の対応を説明でき、TBD を推測で解消していない。
5. Java 8 上で `mvn -B -ntp verify` が成功する。
6. Java 8 上で `java -jar target/TcpTap.jar --help` が成功する。
7. 新しいJava runtimeでも同じtest suiteとexecutable JARのsmoke testが成功する。
8. `target/TcpTap.jar` と `target/TcpTap-<version>.zip` が生成される。
9. production code が Java 8 より新しい言語機能や Java SE API を要求しない。
10. 変更後に不要になった class / file / method / document / test を残さない。
11. README / docs/README / EXTERNAL_DESIGN / INTERNAL_DESIGN / OPERATIONS / 現行 ADR / test / implementation が矛盾・不必要に重複しない。
12. PR の差分が Issue または依頼の目的に閉じている。

Pull Request CI の正本は `.github/workflows/test.yml` とする。
