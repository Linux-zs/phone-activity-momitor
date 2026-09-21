# 手机活动记录 · v0.5.1

当前界面构建（0.5.2）：卡片、操作按钮和选择控件按用途区分圆角；趋势范围、统计指标、事件类型及采集间隔改为等宽分段选择器，趋势图继续以紧凑纤细柱条展示 30 天。安装 `dist/手机活动记录-分段选择器-debug.apk`，预览见 `app-preview.html`，验证记录见 `docs/VERIFICATION-segmented-controls.md`。

v0.5.1 手机端修复：兼容看板接口以整数 0/1 返回的充电和权限状态，修复读取充电状态时的崩溃及权限误判。安装 `dist/手机活动记录-v0.5.1-debug.apk`，可同签名覆盖升级，无需卸载或清除数据；服务端继续使用 v0.5.0 部署包。验证记录见 `docs/VERIFICATION-v0.5.1.md`。

Android 客户端补采系统解锁／亮屏事件，批量上传到自己的 Linux 服务端。网页「亮屏之间」公开展示最近 30 天记录，任何人无需密码即可查看。手机上传仍需密钥。没有短信、企业微信发送，也不提供异常报警。

## 使用顺序

1. 按 [Rocky Linux 部署说明](server/DEPLOY.md) 部署服务端并用 ACME 启用 HTTPS。
2. 将 `dist/手机活动记录-v0.5.0-debug.apk` 传到 Redmi K80 Pro 安装，不需要 USB 调试。包名保留 `com.zerui.safesmsprobe`，同签名旧版可覆盖升级；旧任务会取消，旧发送 Worker 已变为不执行发送的兼容占位。
3. 保存服务器地址 `https://udong.udong.top` 和初始化时生成的上传密钥，授权“使用情况访问”，启用采集并立即同步。
4. 直接访问网页查看，支持近 7／30 天切换、选择日期及事件筛选。目前不采集或展示具体 App 使用明细。

## 数据与同步

- 采集最近解锁、解锁次数、交互亮屏及解锁后亮屏时长；上传时读取电量、充电、网络及权限状态。不保存具体应用使用明细、通讯录或消息。
- 设置页支持 5／15／30 分钟，旧配置默认保留 30 分钟；Android WorkManager 最低周期为 15 分钟，因此选择 5 分钟时后台按 15 分钟调度。打开 App 也触发同步；采用 WorkManager，不保活、不提供实时保证。采集不要求联网，上传要求有网络。系统自带的短期任务管理可能使用内部唤醒机制，但应用不持有永久唤醒锁。
- SQLite 保存采集游标及待上传队列；只在服务端完整确认后标记已传。分页、响应丢失和重试不会重复统计。
- 使用情况历史仅由系统短期保留；客户端最多回查两天。首次从当天零点补采，长期未运行、权限撤销、重启和厂商漏记可能造成缺失。覆盖范围表示查询成功，不保证系统无漏记。
- 按上海时间拆分每日时长，只累计有起止和采集范围的闭合区间。未结束／缺失区间不推算为持续使用；“解锁”指锁屏消失，不证明本人身份。
- 网页区分原始事件时间与接收时间。超过两小时无上报，显示“数据已过期，当前状态未知”。不是安全报警或实时在线判断。
- 暂停阻止新任务；已提交的网络请求可能完成。恢复时补采可用历史。强制停止后需重新打开。
- 服务端保留 30 天；客户端已确认记录也清理到 30 天。未确认记录保持队列，过期数据送达服务端后确认并清理。回拨产生的未来异常记录在本机隔离，不参与统计。

## 目录与交付

- `app/`：Android 28+、targetSdk 36、Kotlin＋Compose。
- `server/`：Python 3.11、FastAPI＋SQLite、HTML/CSS/JS、systemd 和 Nginx 配置。
- `docs/PROTOCOL.md`：同步协议、鉴权、时间和重试约定。
- `docs/PHONE-TEST.md`：Redmi 实机与耗电对照清单。
- `dist/`：APK 和 Linux 服务端部署包；旧文件保留，使用 v0.5.0 文件。此版本精简网页顶部，并提供 Compose 原生看板和独立设置页。

## 构建与验证

本地界面预览（模拟数据，监听本机）：

```powershell
.\server\python311\python.exe scripts\preview_server.py
```

打开 `http://127.0.0.1:8766`。预览会重建 `output/playwright/preview.db`，不读取生产数据库。

当前网页直接沿用参考 Mosaic 的桌面七格布局、字体、表面颜色及缩放入场、数字滚动、柱条生长、曲线绘制、圆环和滚动视差。主区展示亮屏、电量采样、高峰、全天时间轴、最近解锁、亮屏占比及首末次记录，下方补充同步、历史日期及事件筛选。手机端重排为通栏主卡与双列短卡，时间轴和 30 天图表局部横向滑动；尊重系统减少动态效果设置。也可直接打开 `workspace-preview.html` 查看内嵌模拟数据的独立预览；启动预览服务后运行 `server/python311/python.exe scripts/export_design_preview.py` 可重新生成该文件。

Android：JDK 17、SDK 36、Build Tools 36.0.0。

```powershell
$env:JAVA_HOME='D:\AppBall\jdk-17_windows-x64_bin\jdk-17.0.11'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Python：见 `server/DEPLOY.md` 的本地测试步骤。具体运行结果见 `docs/VERIFICATION.md`。目前无已连接手机，不能将自动化测试当作 HyperOS 后台、耗电或 Rocky Linux 部署已验收。

## 参考

- [Android UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [Android UsageEventsQuery](https://developer.android.com/reference/android/app/usage/UsageEventsQuery.Builder)
- [Android Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [FastAPI 部署](https://fastapi.tiangolo.com/deployment/)
- [acme.sh](https://github.com/acmesh-official/acme.sh)

## v0.5.0 原生看板

首页以深色背景、蓝色通栏主卡和双列辅助卡展示 `/api/v1/dashboard` 返回的统计。齿轮进入设置，系统返回键回到看板。支持日期选择、7／30 天历史、事件筛选和横滑时间轴。进入前台、下拉及前台每分钟刷新；离开前台停止轮询。读取失败保留本次页面已有数据，首次失败可重试；空值与缺失显示为破折号。

看板 GET 不携带上传密钥。上传仍使用原协议和 Android Keystore 加密密钥。保存连接时空白密钥保留旧值，更换服务器重新标记本地记录待上传。采集间隔保存后用原唯一任务名 UPDATE 更新，暂停时仅存配置；没有新增第二个周期任务。保留原 SharedPreferences、SQLite 数据库和包名，versionCode 升至 6；同签名覆盖安装，无需清除数据或重新初始化。

后台周期并非精确定时，5 分钟目标受平台限制按 15 分钟调度。真机调度、权限跳转、覆盖升级和触控验收尚待连接设备执行。具体自动化结果及未验证项目见 `docs/VERIFICATION-v0.5.0.md`。

## 安卓采集与同步首页（当前工作区）

打开 App 先读取本机配置、SQLite 和 WorkManager，展示采集启用／暂停、使用情况权限、采集截至时间、最近成功同步时间及待上传记录数。任务提交、等待网络、同步中和服务端确认成功分别展示；暂停可恢复、缺权可授权，连接错误提供相应操作。未配置时按「连接服务器 → 授权 → 启用并首次同步」引导，已有有效配置直接进入首页。

今日摘要只保留服务端亮屏时长和解锁次数；点击摘要进入记录详情，支持日期切换、7／30 天趋势、时间轴和事件筛选。设置按连接配置、采集与权限、诊断信息分组，无底部导航。默认跟随系统浅深色。读取失败保留已有服务端数据，配置服务器改变时清除旧服务器的展示缓存。

保持现有包名、Keystore 密钥、采集配置、SQLite 表结构、上传协议和周期调度。手动失败重试在原唯一上传任务上重新入队；普通采集仍使用 KEEP，周期任务仍使用 UPDATE。构建保留工作区版本 0.5.2（versionCode 8），不替换历史发布包。安装本次构建请使用 dist/手机活动记录-采集同步首页-debug.apk；自动化及待实机验证项目见 docs/VERIFICATION-collector-home.md。
