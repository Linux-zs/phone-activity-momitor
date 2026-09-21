# v0.5.1 闪退修复验证

验证日期：2026-09-20。

## 原因与修复

线上公开看板接口实际返回 snapshot.charging=0、snapshot.permission=1。
v0.5.0 Compose 看板在渲染电量卡时调用 JSONObject.getBoolean("charging")，整数值会触发 JSONException；该调用位于渲染阶段，不受网络请求的异常处理保护。optBoolean("permission") 同样不能识别整数 1，会误报权限未开启。

客户端新增 flagOrNull，兼容整数 0/1 和 JSON 布尔值；缺失及无法识别的值保留为未知。电量卡、权限摘要和权限状态判断均已更换读取方式。无需更新服务器。

## 已验证

- testDebugUnitTest、lintDebug、assembleDebug：BUILD SUCCESSFUL。
- 12 项单元测试通过，0 失败；其中 3 项新增测试覆盖真实接口的数字标志、布尔标志、缺失及非法标志。回归测试确认旧 getBoolean 调用会抛出 JSONException。
- apksigner 验证新旧 APK 均有效，v0.5.0 与 v0.5.1 签名证书 SHA-256 一致。
- versionCode 7 / versionName 0.5.1，包名、偏好设置、Keystore 与本地数据库保持兼容。
- 产物：dist/手机活动记录-v0.5.1-debug.apk；校验文件：dist/SHA256SUMS-v0.5.1.txt。

## 验证限制

adb devices 未发现连接设备，环境无现成模拟器。已复现并修复接口解析异常，但未取得用户手机的崩溃堆栈，尚未完成实机启动与覆盖升级验收。请直接覆盖安装，不要卸载或清除数据。
