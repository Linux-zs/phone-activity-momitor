"""Package only explicitly allowed sources: never runtime data, secrets or environments."""
from pathlib import Path
import hashlib
import shutil
import tarfile

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = dist / "手机活动记录-v0.5.0-debug.apk"
shutil.copyfile(root / "app/build/outputs/apk/debug/app-debug.apk", apk)
archive = dist / "phone-activity-server-v0.5.0.tar.gz"
source = root / "server"
files = [source / n for n in ("requirements.txt", "requirements-dev.txt", "requirements.in", "requirements-dev.in", "pytest.ini", "DEPLOY.md")]
for directory in ("activity", "deploy", "tests"):
    files += [p for p in (source / directory).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
with tarfile.open(archive, "w:gz") as tar:
    def permissions(info):
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        info.mode = 0o644
        return info
    for path in sorted(files):
        tar.add(path, arcname="phone-activity/" + path.relative_to(source).as_posix(), recursive=False, filter=permissions)
    for name in ("PROTOCOL.md", "PHONE-TEST.md", "VERIFICATION.md", "VERIFICATION-v0.5.0.md"):
        path = root / "docs" / name
        if path.exists():
            tar.add(path, arcname="phone-activity/docs/" + name, recursive=False, filter=permissions)
with tarfile.open(archive) as tar:
    names = tar.getnames()
    assert not any(".venv" in n or "secrets.json" in n or n.endswith(".db") for n in names)
    assert "phone-activity/activity/main.py" in names
lines = []
for path in (apk, archive):
    lines.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name)
    print(path.name, path.stat().st_size)
(dist / "SHA256SUMS-v0.5.0.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
