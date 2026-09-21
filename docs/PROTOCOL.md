# 同步协议 v1

`POST /api/v1/sync`，HTTPS，`Authorization: Bearer <上传密钥>`，JSON。所有时间为 Unix 毫秒；页面按 Asia/Shanghai 展示。

```json
{
  "device_id": "installation-uuid",
  "captured_at": 1789790000000,
  "covered_until": 1789790000000,
  "battery": 68,
  "charging": false,
  "network": "wifi",
  "permission": true,
  "diagnostic": "ok",
  "events": [],
  "coverage": []
}
```

- `events` 每批最多 1000 条，元素为 `{id, at, kind}`。`kind` 为 `unlock/lock/screen_on/screen_off/reset`。
- `coverage` 每批最多 200 条，元素为 `{id, start, end}`，单区间最多 3 天。含义是已成功查询的时间范围，不保证厂商系统没有漏记。相关事件先上传，或与最后一页事件在同一事务中提交范围。
- ID 为 64 位十六进制 SHA-256。客户端事件 ID 基于安装 ID、事件时间和系统事件类型生成；覆盖 ID 基于安装 ID、起止时间生成。
- `network` 为 `wifi/mobile/other/offline`；`diagnostic` 为 `ok/permission_denied/user_locked/history_gap/clock_changed/query_failed`。
- 电量、充电、覆盖时间允许 null。客户端采集游标独立于上传确认游标；服务端展示的覆盖时间取已收到覆盖范围的最大结束时间。
- 成功返回 `{accepted_event_ids: [], accepted_coverage_ids: [], server_time: ...}`。必须精确匹配本批 ID 才确认本地记录；丢失响应可安全重传。过期于 30 天的记录会确认但不再保存。
- 401/403：修正密钥；409：设备绑定或 ID 内容冲突；422：格式或时间异常；429/5xx：退避重试。客户端保留未确认记录。
- 服务端限制请求 256 KiB，限制手机上报时间与服务器偏差不超过 10 分钟，拒绝晚于采集时间的事件。检测到手机时间回拨时客户端隔离未来记录、建立新统计边界；异常记录不计入正常上传队列。

`GET /` 和 `GET /api/v1/dashboard` 公开访问，无需密码或 Cookie。上传密钥仍只用于 `POST /api/v1/sync`。公开接口返回每日统计、最近解锁时间、最近 100 条事件以及电量／充电／权限／采集与接收时间摘要，不返回设备 ID、事件 ID、网络类型或密钥。页面和接口均禁用缓存。旧 `GET /login` 重定向至首页，旧密码配置字段不再参与查看鉴权。

`GET /healthz` 用于服务器回环自检，Nginx 公网入口屏蔽此路径。

## Mosaic 看板统计字段

每日统计新增 `screen_bins` / `coverage_bins`（各 144 个十分钟区间的毫秒数）、`hour_unlocks`（24 小时解锁次数）、`sessions`、`longest_ms`、`first_unlock`、`last_lock` 和 `elapsed_ms`。亮屏分桶与原时长共用完整闭合区间校验，跨零点拆分；跨日会话按每一天的片段计数、求最长和平均。首末次来自实际解锁与锁屏事件，熄屏不推断为锁屏。高峰为已记录亮屏最多的小时，并列时取最早时段。

`battery_history` 返回已接收的电量采样，仅含 `captured/battery/charging`，每十分钟桶保留最近收到的有效电量采样，排除未来采样。图表连接采样点，不代表连续监测；充电标记表示采样时正在充电。今日亮屏占比的分母为今日已过时间，历史日为 24 小时；其余时间不推算为锁屏。最近 100 条原始事件列表仍与完整日统计独立。
