# BUILD_STATUS

> 本文件是构建/交付状态的真相源，实时更新。

## 当前状态：✅ APK 构建成功（v1.0）

| 项 | 状态 |
|----|------|
| 工程代码 | ✅ 完成（v1.0） |
| debug APK | ✅ BUILD SUCCESSFUL（2026-07-27，5.4MB） |
| git 提交 | ✅ 已提交 |
| GitHub 推送 | ✅ 已推送（2026-07-27 15:27）https://github.com/FightingChu/AutoSwiper |

## 产物路径

- APK（仓库根目录）：`app-debug.apk`
- APK（构建输出）：`app/build/outputs/apk/debug/app-debug.apk`

## 环境

- JDK: D:/jdk_17/jdk-17.0.11
- Gradle: D:/gradle/gradle-8.8
- SDK: E:/AndroidSDK (platform 34, build-tools 34.0.0)
- 代理: 127.0.0.1:7890
- 注意：路径含中文，gradle.properties 已加 `android.overridePathCheck=true`

## Git 推送说明（2026-07-27）

- 远程仓库：https://github.com/FightingChu/AutoSwiper （public）
- 推送方式：建仓用 classic PAT（仅一次 API 调用，用完已弃用未存盘）；代码推送走 SSH `git@github.com:FightingChu/AutoSwiper.git`（账号 SSH key 已认证）
- 分支：master（默认）
- token 已在本次会话使用后立即销毁，未写入任何文件 / git 历史 / remote URL

## v1.1 — 到底反弹（2026-07-27 15:45）

- 需求：检测到页面到底无法继续时，改为向上滑（可设置次数）→ 用户选「循环继续」（反弹完回主方向来回刷）
- 改动：
  - `SwipeService`：新增到底检测（页面文本指纹，连续 2 次无变化判定到底）+ 反向滑动 `performRandomSwipeDown()` + 反弹状态机（reverseRemaining/reverseMode），循环回主方向
  - `Prefs`：新增 `getReverseCount/setReverseCount`（KEY_REVERSE，默认 10，0=不反弹）
  - `MainActivity` + `activity_main.xml`：新增「到底反弹设置」输入框，保存时一并持久化
  - 悬浮窗反弹时显示「反弹中（向上滑）剩余 N 次」
- 构建目标（v1.1）：仓库根 `AutoSwiper.apk`（5.4MB，已改名）

## 产物命名（2026-07-27 15:59）

- 用户要求：构建产物不要叫 app-debug，要带项目名 AutoSwiper
- 实现：`app/build.gradle` 用 `applicationVariants.all` 设 `outputFileName`：debug → `AutoSwiper.apk`，release → `AutoSwiper-release.apk`
  （注意：AGP 新版 `archivesBaseName` 已不在 `defaultConfig`，必须走 `applicationVariants`，否则报 `Could not find method archivesBaseName()`）
- 仓库根已用 `AutoSwiper.apk` 替换旧 `app-debug.apk`（git rename），已 push `28c3910`
- `versionName` 升至 1.1、`versionCode` 2
