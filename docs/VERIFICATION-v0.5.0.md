# v0.5.0 验证记录

验证时间：2026-09-20。环境：Windows，JDK 17、Android SDK 36、Python 3.11。此文件描述本次版本；VERIFICATION.md 为此前版本记录。

## 已执行

- `gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon`：BUILD SUCCESSFUL。
- Android 单元测试 9 项全部通过（原核心 5 项，新增看板格式与缺失判断 4 项）：空值不冒充零、北京时间跨日、完整零与缺失零区分、5/15/30 周期限制。
- Lint：0 errors、19 warnings、1 hint。警告包括已有依赖更新、同步 SharedPreferences 提交、KTX 建议，以及新 Tile 可选 Modifier 参数排序建议；没有构建阻断项。
- `server/python311/python.exe -m pytest server/tests -q`：13 passed，2 项第三方弃用提示。
- Playwright 实际浏览器：1440×1000 桌面、390×844 手机检查并保存截图。顶部栏与大标题不存在；手机 document scrollWidth 与 innerWidth 均为 390。统计摘要、日期与状态保留。
- 日期切换、30 天的 30 根柱条、解锁事件筛选、144 格时间轴正常；手机时间轴独立横向滚动。
- 网络失败注入：原统计保留并显示提示；恢复后错误提示消失。
- 合成数据验证：无上报/缺失日显示破折号，超过两小时显示数据已过期，未授权显示采集权限未开启，连续切换 20 次日期后选中值正确。
- 前台分钟自动刷新：浏览器时钟推进，等待并捕获 `/api/v1/dashboard` 响应；保留所选日期。故障测试期间产生预期网络控制台错误；正常路径未发现脚本异常。
- 独立预览 workspace-preview.html 已重新导出，内嵌合成数据、CSS、JS；使用不可见 synthetic-preview 元数据校验来源。

## 实现与静态核查

- Compose 使用原公开 dashboard 接口；直接读取每日 screen_ms、unlocked_ms、bins 等数据，格式与缺失判断沿用网页规则，时间统一 Asia/Shanghai。
- GET 客户端没有 Authorization 或上传密钥；上传代码和服务端接口、统计算法未改变。
- 生命周期 STARTED 内每分钟刷新；结束生命周期停止轮询。进行中的网络读取最多受连接/读取超时约束，失败保留已有页面数据。
- 日期切换不另发网络请求，从同一份响应读取，刷新完成时保留仍有效的选择。
- 设置保留原 activity_settings、Keystore activity_upload、数据库和唯一任务名。间隔新增偏好默认 30；周期 UPDATE 避免新增重复任务，暂停时不安排任务。空白密钥沿用旧值；服务器变化仍把 events/coverage 重置为待上传。
- Android WorkManager 最低周期 15 分钟；5 分钟选项保存为目标值，实际后台调度 15 分钟。此限制在设置和 README 明示，并非实现了 5 分钟后台轮询。
- versionCode 6 / versionName 0.5.0，包名不变。APK 使用本环境 debug 签名，覆盖安装仍要求旧版签名一致。

## 尚未验证（无连接设备，也没有现成 AVD）

- Compose 真机视觉、下拉手势、系统返回、权限授权跳转及前后台转换。
- App 与网页在同一真实日期逐项端到端对照；目前为共用接口和统计字段核查，不能视为实机一致性验收。
- 设置保存、空白密钥保留、更换服务器补传、暂停恢复、切换间隔后 WorkManager 数据库唯一任务状态的设备验证。
- 同签名旧 APK 覆盖升级后配置、采集状态与本地队列持久保留的真实安装验证。
- HyperOS/Redmi 的后台时序、Doze、耗电、断网、强杀与恢复。系统限制可能导致延迟。
- Linux 生产部署未执行；交付部署包供按 DEPLOY.md 部署。

## 产物

- dist/手机活动记录-v0.5.0-debug.apk
- dist/phone-activity-server-v0.5.0.tar.gz
- dist/SHA256SUMS-v0.5.0.txt
- output/playwright/dashboard-desktop-v050.png
- output/playwright/dashboard-mobile-v050.png

平台参考：https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
