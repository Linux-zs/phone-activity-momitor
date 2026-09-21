# 分段选择器与圆角层级

日期：2026-09-21

## 改动

- 卡片使用 20dp 圆角，主要操作按钮使用 12dp 圆角，不再由同一个 24dp 胶囊形状覆盖所有控件。
- 趋势的时间范围与统计指标增加小标题，并改为完整铺满容器的两项分段选择器。
- 事件筛选改为五项等宽分段选择器，不再按文字宽度形成大小不一的独立胶囊。
- 设置页的 5／15／30 分钟改为三项等宽分段选择器。
- 分段选择器外组为 10dp 圆角，选中项为 7dp 圆角；预览与 Compose 原生界面同步。

## 验证

`gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon`：BUILD SUCCESSFUL。

- 单元测试通过。
- Lint：0 errors、19 warnings、1 hint。
- Debug APK 组装通过。
- Playwright 在 390px 宽度下完成趋势、事件筛选和采集间隔截图；三组控件均无水平溢出。
- 点击 30 分钟后选中态切换到目标项，分段组 `clientWidth` 与 `scrollWidth` 均为 318px。
- 浏览器控制台：0 errors、0 warnings。
- 效果图：`output/playwright/segmented-trend-mobile.png`、`segmented-events-mobile.png`、`segmented-interval-mobile.png`。

HTML 预览不能替代 Android 真机截图；原生字体缩放、触控和 TalkBack 仍需设备验收。

## 安装包

`dist/手机活动记录-分段选择器-debug.apk`

SHA-256：`a4947587f05a3cfe8ca1d0485f814f67ca92547f41eb358b77860d32b71ade05`

校验文件：`dist/SHA256SUMS-segmented-controls.txt`。
