# 批量生图驱动：读 _manifest.json，3 线程跑 gen.py，失败退避重试
import json
import subprocess
import sys
import threading
import time
import os

sys.stdout.reconfigure(errors="replace")
sys.stderr.reconfigure(errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = r"D:/videogame/imagegen/gen.py"
WORKERS = 3
MAX_RETRY = 3

with open(os.path.join(HERE, "_manifest.json"), encoding="utf-8") as f:
    items = json.load(f)

lock = threading.Lock()
todo = [it for it in items if not os.path.exists(os.path.join(HERE, "..", it["output"]).replace("/", os.sep))]
failed = []

def worker():
    while True:
        with lock:
            if not todo:
                return
            it = todo.pop(0)
        out = it["output"]
        ok = False
        for attempt in range(1, MAX_RETRY + 1):
            r = subprocess.run(
                ["py", GEN, it["prompt"], "-o", out, "--size", "1536x1024"],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                cwd=os.path.join(HERE, ".."),
            )
            if r.returncode == 0:
                ok = True
                print(f"[OK] {out}", flush=True)
                break
            err = (r.stderr or r.stdout or "").strip().splitlines()
            print(f"[FAIL {attempt}] {out}: {err[-1][:150] if err else '?'}", flush=True)
            time.sleep(15 * attempt)
        if not ok:
            with lock:
                failed.append(out)

threads = [threading.Thread(target=worker) for _ in range(WORKERS)]
for t in threads:
    t.start()
for t in threads:
    t.join()

print(f"\n完成 {len(items) - len(todo) - len(failed)}/{len(items)}", flush=True)
if failed:
    print("失败清单:", *failed, sep="\n  ", flush=True)
    sys.exit(1)
