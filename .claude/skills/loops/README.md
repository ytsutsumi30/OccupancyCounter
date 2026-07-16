# loops スキル — 他プロジェクトでの利用方法

このディレクトリ（`loops/`）はプロジェクト非依存の汎用スキル。以下のいずれかの方法で他プロジェクトでも使える。

参考: https://github.com/chaaaaarin/claudecode-channel-20260703 （Loop エンジニアリング入門）

## 方法 1: 個人スキルとして配置（推奨・最も簡単）

`~/.claude/skills/` に置くと、そのマシン上の**すべてのプロジェクト**で使える。

```bash
mkdir -p ~/.claude/skills
cp -r .claude/skills/loops ~/.claude/skills/
```

Windows (PowerShell):

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.claude\skills" | Out-Null
Copy-Item -Recurse .claude\skills\loops "$env:USERPROFILE\.claude\skills\"
```

## 方法 2: プロジェクトごとにコピー

チームで共有したいプロジェクトでは、リポジトリの `.claude/skills/loops/` にコピーしてコミットする。
クローンした全員が `/loops` を使えるようになる。

```bash
cp -r <このリポジトリ>/.claude/skills/loops <対象プロジェクト>/.claude/skills/
```

## 方法 3: プラグインとして配布（複数マシン・チーム横断）

スキル専用のリポジトリを作り、Claude Code のプラグイン機構で配布する。

1. リポジトリ（例: `ytsutsumi30/claude-skills`）を作り、以下の構成にする:

   ```
   .claude-plugin/marketplace.json
   plugins/loops/
     ├── .claude-plugin/plugin.json
     └── skills/loops/SKILL.md（+ references/）
   ```

   `marketplace.json` の例:

   ```json
   {
     "name": "my-skills",
     "owner": { "name": "ytsutsumi30" },
     "plugins": [
       { "name": "loops", "source": "./plugins/loops", "description": "自己検証ループの設計・実行" }
     ]
   }
   ```

   `plugin.json` の例:

   ```json
   { "name": "loops", "version": "1.0.0", "description": "自己検証ループの設計・実行" }
   ```

2. 利用側の Claude Code で:

   ```
   /plugin marketplace add ytsutsumi30/claude-skills
   /plugin install loops@my-skills
   ```

更新はリポジトリに push → 利用側で plugin update するだけで全プロジェクトに反映される。
