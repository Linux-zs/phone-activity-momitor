# Rocky Linux 8.10 部署

适用：单手机、现有 Nginx、域名 `udong.udong.top`。以下命令在服务器上执行；本项目没有自动远程部署。保留现有其他 Nginx 站点。不要开放 8765 端口。

## 1. 安装服务

以 root 执行。解压交付的 `phone-activity-server-v0.4.1.tar.gz` 到 `/opt`，包内顶层目录是 `phone-activity`。

```bash
cat /etc/rocky-release
dnf install -y python3.11 python3.11-pip policycoreutils-python-utils
python3.11 --version
id phone-activity || useradd --system --home-dir /var/lib/phone-activity --shell /sbin/nologin phone-activity
tar -xzf phone-activity-server-v0.4.1.tar.gz -C /opt
install -d -o phone-activity -g phone-activity -m 700 /var/lib/phone-activity
install -d -o root -g phone-activity -m 750 /etc/phone-activity
cd /opt/phone-activity
python3.11 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m activity.manage init
chown root:phone-activity /etc/phone-activity/secrets.json
chmod 640 /etc/phone-activity/secrets.json
install -m 640 -o root -g phone-activity deploy/service.env.example /etc/phone-activity/service.env
install -m 644 deploy/phone-activity.service /etc/systemd/system/phone-activity.service
systemctl daemon-reload
systemctl enable --now phone-activity
curl --fail http://127.0.0.1:8765/healthz
```

初始化生成一次性显示的上传密钥，不再设置查看密码。网页和看板接口公开访问。将上传密钥单独保存，稍后填到手机，不要放入 Nginx 配置或 URL。

若找不到 Python 包，检查 `dnf repolist` 的 AppStream 是否启用。不要用系统默认 Python 3.6 运行本服务，也不要替换 `/usr/bin/python`。依赖已按 Python 3.11 锁定。

## 2. Nginx 与 ACME

先用 `nginx -T` 查看已有配置，确认没有同名 `server_name`。以下新文件仅属于此项目。

```bash
install -d -m 755 /var/www/acme/.well-known/acme-challenge
restorecon -Rv /var/www/acme
install -m 644 deploy/nginx-http.conf /etc/nginx/conf.d/phone-activity.conf
nginx -t && systemctl reload nginx
```

确保云安全组允许 80、443。若使用 firewalld：

```bash
firewall-cmd --permanent --add-service=http
firewall-cmd --permanent --add-service=https
firewall-cmd --reload
```

沿用服务器已有的 acme.sh；如尚未安装，按 [acme.sh 官方说明](https://github.com/acmesh-official/acme.sh#1-how-to-install) 安装。以下假设位于 `/root/.acme.sh/acme.sh`，并已配置有效联系邮箱。证书文件使用 `--install-cert` 安装，不直接引用 acme.sh 内部目录。

```bash
/root/.acme.sh/acme.sh --issue --server letsencrypt -d udong.udong.top -w /var/www/acme --keylength ec-256
install -d -m 700 /etc/nginx/ssl/udong.udong.top
install -m 750 deploy/reload-nginx.sh /usr/local/sbin/phone-activity-reload-nginx
/root/.acme.sh/acme.sh --install-cert -d udong.udong.top --ecc \
  --key-file /etc/nginx/ssl/udong.udong.top/key.pem \
  --fullchain-file /etc/nginx/ssl/udong.udong.top/fullchain.pem \
  --reloadcmd /usr/local/sbin/phone-activity-reload-nginx
chmod 600 /etc/nginx/ssl/udong.udong.top/key.pem
restorecon -Rv /etc/nginx/ssl
setsebool -P httpd_can_network_connect 1
install -m 644 deploy/nginx-https.conf /etc/nginx/conf.d/phone-activity.conf
nginx -t && systemctl reload nginx
curl --fail https://udong.udong.top/ -o /dev/null
```

`httpd_can_network_connect` 允许 Nginx 反向代理到本机服务；保持 SELinux 启用。若存在自定义安全策略，先检查 AVC 日志后调整，不执行 `setenforce 0`。核实 acme.sh 的 cron 或 systemd 续期任务已安装；续期成功后上述 reload hook 会先检查 Nginx 配置再重载。

证书未启用前，HTTP 只开放 ACME 验证目录，应用页面返回 503。手机只接受 HTTPS，不忽略无效证书。

## 3. 手机绑定

安装 APK → 保存 `https://udong.udong.top` 和上传密钥 → 打开使用情况授权 → 开启采集 → 立即同步。任何人均可直接访问网页查看解锁和亮屏记录。

首次成功上传绑定设备 ID。重新安装 App 会产生新 ID，服务器返回 409 时，需管理员先备份，再运行 `activity.manage reset-device` 清空旧绑定和记录。覆盖升级通常保留 ID。

## 4. 运维、升级与恢复

```bash
systemctl status phone-activity
journalctl -u phone-activity -n 100 --no-pager
cd /opt/phone-activity
.venv/bin/python -m activity.manage backup --output /root/activity-backup.db
```

备份通过 SQLite backup API 生成一致性副本，可在服务运行时执行。另行安全备份 `/etc/phone-activity`；备份含私人记录，应限制访问。SQLite DB 默认位于 `/var/lib/phone-activity`，不是代码目录。

升级：备份 → `systemctl stop phone-activity` → 将新包解压到 `/opt` → 在现有虚拟环境安装锁定依赖 → `systemctl start phone-activity` → 检查 healthz、公开看板和手机同步。不要删除 `/var/lib/phone-activity`、`/etc/phone-activity`。已有配置中的旧 `password_hash` 会被忽略；上传密钥及设备绑定保持兼容。

恢复：

```bash
systemctl stop phone-activity
cd /opt/phone-activity
.venv/bin/python -m activity.manage restore --input /root/activity-backup.db
chown phone-activity:phone-activity /var/lib/phone-activity/activity.db*
systemctl start phone-activity
```

上传密钥轮换：运行 `.venv/bin/python -m activity.manage upload-key` 后重启服务，并在手机保存新密钥。配置文件保持 `root:phone-activity 640`。此版本无查看密码及密码轮换命令。

反向代理仅信任回环地址转发头，勿把应用改为公网监听。活动数据保留 30 天，服务每小时以及 API 访问时清理。网页无需会话，旧 `/login` 链接会跳转至首页。

## 5. 本地测试

```bash
python3.11 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

测试使用独立临时数据库和模拟事件，不上传任何真实手机数据。模拟测试通过不等同于 Redmi 后台和 Rocky 实机验收。
