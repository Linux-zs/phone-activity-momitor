"""Export the local synthetic dashboard as a self-contained file preview."""
import json
import os
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parents[1]
port = int(os.environ.get("PHONE_ACTIVITY_PREVIEW_PORT", "8766"))
if not 1 <= port <= 65535:
    raise ValueError("PHONE_ACTIVITY_PREVIEW_PORT must be between 1 and 65535")
base = f"http://127.0.0.1:{port}"
with urlopen(base + "/", timeout=10) as response:
    html = response.read().decode("utf-8-sig")
if '<meta name="synthetic-preview" content="true">' not in html:
    raise RuntimeError("Only export the synthetic local preview server")
with urlopen(base + "/api/v1/dashboard", timeout=10) as response:
    data = json.load(response)
css = (root / "server/activity/static/dashboard.css").read_text(encoding="utf-8-sig")
js = (root / "server/activity/static/dashboard.js").read_text(encoding="utf-8-sig")
js = js.replace(
    "await fetch('/api/v1/dashboard',{cache:'no-store',signal:AbortSignal.timeout(15000)})",
    "{ok:true,json:async()=>structuredClone(previewData)}",
)
js = "const previewData=" + json.dumps(data, ensure_ascii=False).replace("<", "\\u003c") + ";\n" + js
html = html.replace('<link rel="stylesheet" href="/static/dashboard.css">', "<style>" + css + "</style>")
html = html.replace('<script src="/static/dashboard.js" defer></script>', "")
html = html.replace('<link rel="icon" href="/static/favicon.svg" type="image/svg+xml">', '<link rel="icon" href="data:,">')
html = html.replace('href="/"', 'href="#main"')
html = html.replace("</body>", "<script>" + js + "</script></body>")
(root / "workspace-preview.html").write_text(html, encoding="utf-8")
print("workspace-preview.html updated with synthetic data and embedded assets")
