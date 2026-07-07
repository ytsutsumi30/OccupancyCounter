# ローカルPC セルフスキャン手順書（WSL版）

クラウド版 Claude Code（Web セッション）からはローカルPCに触れないため、
PC側のフォルダ整理とグローバル Claude Code 設定の診断は、WSL 上の Claude Code で行います。
この手順書のコマンドはすべて **読み取り専用** です。

## 0. WSL 特有の前提

- WSL の Claude Code が読むグローバル設定は **WSL 側の `~/.claude/`**（Linux ホーム）。
  Windows ネイティブ版 Claude Code を併用している場合、`C:\Users\<名前>\.claude` は
  **別物**として存在し、設定は共有されない。診断時は両方確認する
  （WSL からは `/mnt/c/Users/<名前>/.claude` で読める）。
- `/mnt/c` 越しの Gradle ビルドは I/O が遅い。WSL で開発を続けるなら、
  リポジトリは WSL ホーム側（例: `~/projects/OccupancyCounter`）に clone し直すのを推奨。
- PowerShell スクリプト（`scripts/package-release.ps1`）は WSL からは
  `powershell.exe -File ...` 経由になる。リリース作業は Windows 側での実行が無難。

## 1. フォルダ棚卸し（読み取り専用）

```bash
# プロジェクト群の全体像（C:\PRJ2 配下を2階層まで）
find /mnt/c/PRJ2 -maxdepth 2 -type d | sort

# 各プロジェクトの最終更新日（放置フォルダの発見）
for d in /mnt/c/PRJ2/dev2/*/; do
  echo "$(stat -c %y "$d" | cut -d' ' -f1)  $d"
done | sort

# WSL ホーム側のプロジェクト配置
find ~ -maxdepth 2 -type d -not -path '*/.*' | sort
```

チェック観点:

- 命名規則が混在していないか（日付フォルダ / コピー / `dev2` のような世代番号）
- Git 管理されていないプロジェクトはないか（`ls <dir>/.git` で確認）
- 理想形の例: `~/projects/<リポジトリ名>`（WSL側・フラット1階層）に集約し、
  Windows 側は Android Studio 等 GUI ツールが必要なものだけ残す

## 2. グローバル Claude Code 設定の点検

```bash
# WSL 側
ls -la ~/.claude/
cat ~/.claude/CLAUDE.md 2>/dev/null
cat ~/.claude/settings.json 2>/dev/null
ls ~/.claude/skills/ ~/.claude/agents/ ~/.claude/rules/ 2>/dev/null
cat ~/.claude.json 2>/dev/null | head -50   # MCP サーバー定義を含む

# Windows 側（併用している場合）
ls -la /mnt/c/Users/<名前>/.claude/ 2>/dev/null
```

チェック観点:

- グローバル CLAUDE.md にプロジェクト固有の内容が混ざっていないか
  （→ 各プロジェクトの CLAUDE.md へ移す）
- settings.json の permissions に危険な allow（`Bash(*)` 等）がないか
- 使っていない MCP サーバーが `~/.claude.json` に残っていないか
- 旧 `~/.claude/commands/` が残っていれば skills への移行を検討

## 3. 振り分けの原則（何をどこに置くか）

| 種類 | 置き場所 | 例 |
|------|---------|-----|
| 常時参照する事実 | CLAUDE.md（プロジェクト or グローバル） | ビルドコマンド、構成、規約 |
| 手順・ワークフロー | `.claude/skills/<name>/SKILL.md` | リリース手順、デプロイ手順 |
| 強制したいルール | Hooks / permissions（settings.json） | コミット前テスト、危険コマンド禁止 |
| 分離したい処理 | Subagent（`.claude/agents/`） | 大規模調査、レビュー専任 |

判断基準: 「毎回同じ指示をチャットで打っている」→ CLAUDE.md へ。
「特定の作業のときだけ必要な長い手順」→ Skill へ（常時読み込ませない＝トークン節約）。

## 4. ローカル Claude Code に貼る診断プロンプト

WSL の Claude Code で以下を貼れば、この手順書の内容を自動で実施できます:

> docs/LOCAL_SETUP_SCAN.md を読み、手順1〜2のコマンドを読み取り専用で実行して、
> フォルダ構造とグローバル Claude Code 設定を診断してください。
> 問題点を「影響度×工数」で整理し、手順3の原則に従った改善プランを提示してください。
> 私が GO を出すまで変更は禁止です。
