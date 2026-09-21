"""Run from server directory: python -m activity.manage --help."""
import argparse
import json
import os
from pathlib import Path
import secrets
import sqlite3
from .security import digest
from .db import connect, initialize


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["init", "upload-key", "reset-device", "backup", "restore"])
    parser.add_argument("--config", default=os.environ.get("ACTIVITY_CONFIG", "/etc/phone-activity/secrets.json"))
    parser.add_argument("--db", default=os.environ.get("ACTIVITY_DB", "/var/lib/phone-activity/activity.db"))
    parser.add_argument("--output")
    parser.add_argument("--input")
    args = parser.parse_args()
    path = Path(args.config)
    if args.command in ("init", "upload-key"):
        if args.command == "init" and path.exists():
            parser.error("配置已存在；使用 upload-key 修改")
        cfg = {} if args.command == "init" else json.loads(path.read_text())
        token = None
        if args.command in ("init", "upload-key"):
            token = secrets.token_urlsafe(32)
            cfg["upload_hash"] = digest(token)
        path.parent.mkdir(parents=True, exist_ok=True)
        # Preserve service group on rotation by writing the existing file in place.
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f)
        print("配置已保存；修改已有配置后请重启服务。")
        if token:
            print("上传密钥（仅本次显示，请保存并填入手机）: " + token)
    elif args.command == "backup":
        if not args.output or Path(args.output).exists():
            parser.error("请用 --output 指定不存在的备份文件")
        with sqlite3.connect(f"file:{Path(args.db).as_posix()}?mode=ro", uri=True) as src:
            with sqlite3.connect(args.output) as dst:
                src.backup(dst)
        print("数据库一致性备份完成")
    elif args.command == "restore":
        if not args.input or not Path(args.input).is_file():
            parser.error("请用 --input 指定备份；恢复前必须停止服务")
        with sqlite3.connect(f"file:{Path(args.input).as_posix()}?mode=ro", uri=True) as src:
            if src.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                parser.error("备份损坏")
            with sqlite3.connect(args.db) as dst:
                src.backup(dst)
        print("恢复完成")
    else:
        initialize(args.db)
        if input("将清空设备绑定及活动记录，输入 RESET 确认: ") != "RESET":
            parser.error("未确认")
        with connect(args.db) as db:
            for table in ("device", "events", "coverage", "snapshots"):
                db.execute(f"DELETE FROM {table}")
        print("设备绑定已清空")


if __name__ == "__main__":
    main()
