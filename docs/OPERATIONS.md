# 運用手順

この文書は、TcpTap のビルド、テスト、配布物生成、リリース手順の正本です。

## ビルド環境

- JDK 8 以上
- Maven 3.9 以上

配布互換性の基準は JRE 8 です。`pom.xml` の source / target は 8 とし、CI では JDK 8 でビルド、テスト、実行可能 JAR の起動確認を行います。

## 検証

```bash
mvn -B -ntp verify
```

コンパイル、テスト、依存ライブラリを含む実行可能 JAR、配布 ZIP の生成まで実行します。`-ntp` は Maven の転送進捗表示を抑止して CI のログを簡潔にする指定で、ビルドのライフサイクル自体は通常の `verify` です。

生成物は `target/` にだけ置き、Git では管理しません。

```text
target/TcpTap.jar
target/TcpTap-<version>.zip
```

`TcpTap.jar` は、picocli を Maven Shade Plugin で同梱した単一の実行可能 JAR です。配布 ZIP には `TcpTap.jar`、`README.md`、`LICENSE.txt` を含めます。

Maven のライフサイクルで利用するプラグインのバージョンは `pom.xml` で明示し、実行する Maven バージョンの暗黙の既定値に依存しない構成を維持します。Maven Enforcer Plugin で、ビルドツールの最低バージョンも検証します。

## CI

`.github/workflows/test.yml` が Pull Request と `main` への push で、Java 8 / Java 25 の組合せテストを実行します。ただし、`AGENTS.md` と `docs/**` だけを変更した場合は実行しません。

各 Java バージョンで次を実行します。

```bash
mvn -B -ntp verify
java -jar target/TcpTap.jar --help
```

Java 8 のジョブを JRE 8 互換性の基準とします。新しい Java のジョブでは、同じ成果物とテスト一式が現在の新しい実行環境でも動作することを確認します。

同じ参照先で新しい実行が開始された場合は古い実行をキャンセルし、ジョブにはタイムアウトを設定します。チェックアウト用トークンは Git の認証情報として残しません。結合テストはループバックだけを使用し、CI から外部ホストへ接続しません。

通常の CI では、配布物を GitHub Actions の成果物として重複保存しません。正式な配布は GitHub Release に限定します。

## バージョン

通常の開発ブランチでは、`pom.xml` の `${revision}` をプロジェクトのバージョンとして使用し、既定値は `0.0.0-SNAPSHOT` です。

正式リリースのバージョンは、GitHub Release のタグ `vX.Y.Z` を正本とします。リリース用ワークフローはタグから `X.Y.Z` を取り出し、チェックアウトした作業ツリー内のプロジェクトバージョンを、その値へ一時的に置き換えてビルドします。この変更はリポジトリへコミットしません。

そのため、リリースのたびに `pom.xml` のバージョンを上げる Pull Request は作成しません。通常開発用のスナップショットバージョンと、正式配布物のリリースバージョンを分離します。

## GitHub Release

`.github/workflows/release.yml` は、次の場合に既存の GitHub Release へ配布物をビルドしてアップロードします。

- GitHub Release が `published` になったとき
- `workflow_dispatch` で既存リリースのタグを指定したとき

`workflow_dispatch` は、失敗したリリース処理の再実行や、既存リリースへの成果物の再生成に使用できます。タグを push しただけではリリース用ワークフローは起動しません。

ワークフローは次を確認・実行します。

- リリースタグが `vX.Y.Z` 形式であること
- タグが指すコミットが `main` の履歴上にあること
- タグからリリースバージョン `X.Y.Z` を決定し、チェックアウトした `pom.xml` のプロジェクトバージョンへ一時的に反映すること
- Java 8 で `mvn -B -ntp verify` が成功すること
- Java 8 で `java -jar target/TcpTap.jar --help` が成功すること
- 配布 ZIP の SHA-256 チェックサムを生成すること

`main` の履歴確認には、`actions/checkout` が取得した履歴に対して `git merge-base --is-ancestor HEAD origin/main` を使用します。追加の匿名 fetch には依存しません。

検証成功後、対象の既存 GitHub Release へ次の成果物だけをアップロードします。

- `TcpTap-<version>.zip`
- `TcpTap-<version>.zip.sha256`

実行可能な `TcpTap.jar` は ZIP 内に含めますが、単体の Release asset としては公開しません。利用者向けの取得経路を ZIP に一本化し、JAR と ZIP の二重配布を避けます。

アップロード時は `github.token` を `gh` へ明示的に渡し、同名の成果物がある場合は置き換えます。GitHub Release 自体の作成やリリースノート生成は、このワークフローの責務ではありません。

通常のリリース手順は次のとおりです。

1. リリース対象のコミットが `main` に入っていることを確認する。
2. GitHub の Releases 画面で `vX.Y.Z` タグを指定し、Release を作成して公開する。
3. `Release` ワークフローの成功を確認する。
4. GitHub Release に ZIP と SHA-256 チェックサムが揃っていることを確認する。

すでにタグを CLI から作る場合の例:

```bash
git switch main
git pull
git tag v0.1.0
git push origin v0.1.0
```

このタグの push だけでは配布処理は開始されません。続けて GitHub Release を作成して公開します。

GitHub Packages は、Maven の依存ライブラリとして配布する必要が生じた場合だけ使用します。
