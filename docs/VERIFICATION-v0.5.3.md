# v0.5.3 记录详情闪退与启动图标验证

验证日期：2026-09-21。

## 原因与修复

公开看板接口为避免泄露内部标识，时间线事件只返回 `at` 和 `kind`，不返回数据库 `id`。详情页此前把 `id` 作为 Compose `LazyColumn` 的事件键读取；首页点击“记录详情”后，首次渲染事件列表会触发 `JSONException` 并终止 Activity。

详情页现改用列表位置、事件时间和事件类型组成的本地键，不再依赖服务端私有字段。新增单元测试使用真实公开响应形状（无 `id`）验证键生成。

启动图标已替换为用户提供的 1254×1254 PNG，并生成 mdpi、hdpi、xhdpi、xxhdpi、xxxhdpi 五档 mipmap 资源。原始素材保存在 `assets/branding/app-icon-source.png`。

## 已验证

- `gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon`：BUILD SUCCESSFUL。
- Android 单元测试 19 项通过，0 失败。
- Lint：0 errors、24 warnings、1 hint，无阻断错误。
- 服务端 `pytest server/tests -q`：13 passed；既有测试确认公开时间线不包含 `id`。
- `aapt dump badging`：包名 `com.zerui.safesmsprobe`，versionCode 9，versionName 0.5.3，五档 launcher icon 均已打入 APK。
- `apksigner verify`：APK 通过 v2 签名校验，签名者 1 个。
- 产物：`dist/手机活动记录-v0.5.3-debug.apk`；SHA-256：`577bceb93b0f54fcc9f013e412dd8bde5e0216baecb9b9fa6d2663f6e97c9ef8`。

## 验证限制

`adb devices` 未发现连接设备，因此本次没有执行真机点击详情、桌面图标显示和覆盖安装验收。请直接覆盖安装，不要卸载或清除数据。
