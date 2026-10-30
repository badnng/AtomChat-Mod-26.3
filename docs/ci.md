# CI 与发版

CI 与 Release/store 矩阵都从 `versions/targets.json` 动态生成：CI 只选 `ci_build: true` 的目标，Release/store 只选 `buildable: true` 的目标。当前两者都只有 `26.3-neoforge`（JDK 25），分别构建 Windows x64 与 Linux x64 的 bundled JAR，并随产物提供 SHA-256 与 buildinfo；Actions 产物保留 14 天。

`1.21.1-fabric` 与 `1.20.1-forge` 的源码仍保留在仓库，也可在各自目录手动构建。`ci_build: false` 只表示不纳入 CI；`buildable: false` 表示不参与 Release 与商店发布。未声明 `ci_build` 时默认为 `true`，且 `buildable: true` 的目标必须同时启用 CI 构建。

## 构建与通知

`build.yml` 在除 Dependabot 分支外的所有分支 push、PR 和手动 `workflow_dispatch` 时运行。PR 的新运行会取消同一 PR 的旧运行。构建和检查从动态矩阵读取目标，不在 workflow 中维护第二份目标清单。

Telegram 通知只对受信任仓库 `badnng/AtomChat-Mod-26.3` 的默认分支和 `dev` 分支开放，并且仓库变量 `TELEGRAM_ENABLED` 必须为 `true`。需要配置的基础 Secrets 是 `TELEGRAM_BOT_TOKEN` 与 `TELEGRAM_CHAT_ID`。可选 Secrets `TELEGRAM_API_ID`、`TELEGRAM_API_HASH` 需要同时非空：缺任一项时走云端 Bot API，50 MB 以内发送附件；超限时发送 Actions 产物下载链接，下载需要登录 GitHub。两项都有时使用本地 Telegram 服务，附件上限为 2 GB；二者都非空但格式无效时校验失败，不会回退到云端。发送失败不切换后端，也不重试。相同机器人使用仓库级队列串行发送；不同仓库之间不共享队列。

通知正文包含项目名与 CI 构建编号、版本、分支、短 commit SHA、commit message，并附有“下载 Actions 产物”和“构建详情”两个按钮。

Action 依赖固定到完整 commit SHA；Dependabot 每周按组更新 GitHub Actions 依赖。

## Release

`release.yml` 可由 `v*` 标签触发；手动运行必须填写已有且指向当前版本提交的 `v*` 标签。`dry_run` 默认是 `true`：仍会构建、测试完整 Release 动态矩阵（当前只有 `26.3-neoforge`）、校验并打包附件、上传 Actions 产物，但不会创建或发布 GitHub Release，也不会发布商店。正式运行复用构建阶段上传的同一批 JAR，不重复构建；先创建草稿 Release 并检查附件齐全，再发布。已发布的 tag 不可覆盖。

手动运行的 `target` 是字符串：`all` 表示全部当前可发布目标，也可填写一个矩阵目标名。它只筛选商店发布目标；Release 始终覆盖完整的当前 Release 矩阵。正式版会发布到商店，预发布标签只建 Release，不发商店。需要重试商店发布时，使用已正式发布的 tag，并同时设置 `publish_stores_only=true`、`dry_run=false`。同一目标的重试会再次处理 Windows 与 Linux 两个附件，并分别运行两家商店的发布步骤；商店端不保证自动去重。
