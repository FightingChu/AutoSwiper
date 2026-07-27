# AutoSwiper — 安卓通用自动上滑小工具

基于 Android 无障碍服务（AccessibilityService）的通用自动上滑工具：在**任意 App 页面**每 1 秒模拟一次上滑手势，触点随机，带常驻悬浮窗一键开关。

> ⚠️ 合规提醒：部分 App 的用户协议禁止自动化操作，请仅用于技术研究与个人自动化，风险自负。

## 功能

| 功能 | 说明 |
|------|------|
| 自动上滑 | `dispatchGesture()` 模拟真实上滑手势，任意 App 通用 |
| 固定节拍 | 1 秒滑动 1 次 |
| 触点随机 | 每次起点 X/Y、终点 X/Y、轨迹弧度、手势时长均随机，不固定一个点 |
| 次数/时长设置 | App 内可设按次数或按时长（秒）；1 秒 1 次，两者等价；0 = 无限 |
| 悬浮窗 | 常驻可拖动悬浮窗，一键开始/停止，实时显示剩余次数 |
| 自动停止 | 设定次数滑完自动停止，**悬浮窗保留**，可随时再次开始 |

## 使用步骤

1. 安装 `app-debug.apk`
2. 打开 App，设置滑动次数或时长，点「保存设置」
3. 点「去开启无障碍服务」，找到 **AutoSwiper** 并开启
4. 悬浮窗自动出现 → 切到任意 App → 点悬浮窗「开始」
5. 次数到自动停止（悬浮窗不消失）；也可随时点「停止」

## 技术要点

- 悬浮窗使用 `TYPE_ACCESSIBILITY_OVERLAY`（API 27+），**无需** `SYSTEM_ALERT_WINDOW` 权限，绕开国产 ROM 悬浮窗限制
- 手势随机化范围：起点 X ∈ [30%, 70%] 屏宽，起点 Y ∈ [65%, 82%] 屏高，终点 Y ∈ [18%, 35%]，时长 250~450ms，轨迹带随机弧度
- minSdk 24（`dispatchGesture` 要求 API 24+），targetSdk 33，compileSdk 34

## 构建

```bash
# 需要 JDK 17 + Android SDK (platform 34)
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```
