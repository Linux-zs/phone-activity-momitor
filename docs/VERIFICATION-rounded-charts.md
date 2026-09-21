# 大圆角与紧凑图表

日期：2026-09-21

## 改动

- 原生首页、详情和设置卡片统一使用 24dp 圆角，替换原 12dp 圆角；按钮使用 24dp 圆角，替换原 6dp。预览同步更新。
- 趋势图采用紧密排列的纤细柱条：每个日期最多占 12dp，柱间距最多 3dp，柱高最多 80dp。30 天随可用宽度收缩到一屏；7 天保持紧凑排列。
- 移除每柱上下重复数字，日期范围与选中数值单独显示。点选及前后按钮切换日期；原生提供无障碍日期操作，预览支持方向键。
- 蓝色为普通柱，橙色为选中或不完整记录；选中日期另有底部圆点。不完整状态明确显示，零与缺失分别显示为灰线和灰虚线。
- 保持工作区 0.5.2 / versionCode 8，采集、上传、数据库与服务端未修改。

## 验证

`gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon`：BUILD SUCCESSFUL。

- 18 项单元测试通过，0 失败、0 错误。
- Lint：0 errors、19 warnings、1 hint。
- Playwright 预览验证：24px 圆角、7/30 天柱数与宽度、点选日期、前后切换、指标切换、不完整标记、键盘操作、深浅色、320/390px 无水平溢出及 30 柱完整显示，均通过。
- 检查脚本：`output/playwright/verify-rounded-preview.js`。
- 构建日志：`output/android-rounded-build.log`。
- 效果图：`output/playwright/rounded-home-dark.png`、`rounded-trend-30-dark.png`、`rounded-trend-320-light.png`、`rounded-settings-light.png`。

`adb devices -l` 未发现连接设备。效果图为 HTML 设计预览，不能替代原生设备截图；原生触控、字体缩放及无障碍操作尚未实机验收。

## 安装包

`dist/手机活动记录-大圆角紧凑图表-debug.apk`

SHA-256：`dc2d5b9f42d29efc97d7b8b5aecde790fa5a261d65e39ce34cc957b5b13d27c9`

校验文件：`dist/SHA256SUMS-rounded-charts.txt`。历史安装包保留。
