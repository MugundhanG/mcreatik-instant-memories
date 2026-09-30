#!/usr/bin/env python3
"""
End-to-end McreatiK Live Gallery event simulation (V1 success criteria).

  Setup   : admin creates "Arun & Priya Wedding", three uploaders, QR/gallery URL.
  Event   : three "cameras" write photos into three watched folders; three real uploader
            processes upload simultaneously; a guest browser listens on the live stream.
  Failure : camera 3's laptop loses internet mid-event and keeps shooting; camera 2's uploader
            crashes (kill -9) and is restarted. Duplicates are copied into folders.
  Verify  : every photo reaches the gallery exactly once, live, with per-camera counts.

Requires: backend running (scripts/run-backend-local.sh), uploader jar built (uploader/target).
Only uses the Python standard library.
"""
import json
import os
import random
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import date
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = os.environ.get("API_BASE_URL", "http://localhost:8080").rstrip("/")
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "admin@mcreatik.local")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "change-me-please")
PER_CAMERA = int(os.environ.get("PHOTOS_PER_CAMERA", "20"))
JAR = os.path.join(ROOT, "uploader", "target", "mcreatik-uploader.jar")
PROXY_PORT = int(os.environ.get("SIM_PROXY_PORT", "18888"))
TIMEOUT = int(os.environ.get("SIM_TIMEOUT", "240"))
OUTAGE_SECONDS = int(os.environ.get("SIM_OUTAGE_SECONDS", "25"))

t0 = time.time()


def log(msg):
    print(f"[{time.time() - t0:6.1f}s] {msg}", flush=True)


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


# ---------------------------------------------------------------- network switch for one laptop

class Switch:
    """TCP relay in front of the backend. Turning it off = that laptop's internet is down."""

    def __init__(self, port, upstream_host, upstream_port):
        self.online = True
        self.conns = set()
        self.lock = threading.Lock()
        self.upstream = (upstream_host, upstream_port)
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", port))
        self.server.listen(64)
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while True:
            client, _ = self.server.accept()
            if not self.online:
                client.close()
                continue
            try:
                upstream = socket.create_connection(self.upstream, timeout=10)
            except OSError:
                client.close()
                continue
            with self.lock:
                self.conns.update([client, upstream])
            for a, b in ((client, upstream), (upstream, client)):
                threading.Thread(target=self._pipe, args=(a, b), daemon=True).start()

    def _pipe(self, src, dst):
        try:
            while True:
                chunk = src.recv(65536)
                if not chunk:
                    break
                dst.sendall(chunk)
        except OSError:
            pass
        finally:
            for s in (src, dst):
                try:
                    s.close()
                except OSError:
                    pass

    def set_online(self, online):
        self.online = online
        if not online:
            with self.lock:
                for s in self.conns:
                    try:
                        s.shutdown(socket.SHUT_RDWR)
                        s.close()
                    except OSError:
                        pass
                self.conns.clear()


# ---------------------------------------------------------------- guest browser (SSE)

class Guest(threading.Thread):
    def __init__(self, slug):
        super().__init__(daemon=True)
        self.slug = slug
        self.ready = {}  # photoId -> received time
        self.connected = threading.Event()

    def run(self):
        req = urllib.request.Request(f"{API}/api/public/events/{self.slug}/stream",
                                     headers={"Accept": "text/event-stream"})
        with urllib.request.urlopen(req, timeout=600) as stream:
            event = None
            for raw in stream:
                line = raw.decode().rstrip("\n")
                if line.startswith("event:"):
                    event = line[6:]
                    if event == "CONNECTED":
                        self.connected.set()
                elif line.startswith("data:") and event == "PHOTO_READY":
                    self.ready[json.loads(line[5:])["photoId"]] = time.time()


# ---------------------------------------------------------------- cameras

def write_like_a_camera(src, dest):
    """Writes in chunks with pauses so the uploader sees partially written files (stability check)."""
    data = open(src, "rb").read()
    tmp_size = len(data) // 4 + 1
    with open(dest, "wb") as f:
        for i in range(0, len(data), tmp_size):
            f.write(data[i:i + tmp_size])
            f.flush()
            time.sleep(0.05)


class Camera(threading.Thread):
    def __init__(self, n, photos, folder, written, hooks):
        super().__init__(daemon=True)
        self.n, self.photos, self.folder, self.written, self.hooks = n, photos, folder, written, hooks

    def run(self):
        for i, src in enumerate(self.photos, start=1):
            name = f"CAM{self.n}_{i:04d}.JPG"
            write_like_a_camera(src, os.path.join(self.folder, name))
            self.written[name] = time.time()
            hook = self.hooks.get(i)
            if hook:
                hook()
            time.sleep(random.uniform(0.2, 0.6))


def start_uploader(n, data_dir, folder=None, code=None, proxy=False):
    cmd = ["java"]
    if proxy:
        cmd += [f"-Dhttp.proxyHost=127.0.0.1", f"-Dhttp.proxyPort={PROXY_PORT}", "-Dhttp.nonProxyHosts="]
    cmd += ["-jar", JAR, "--headless", "--data-dir", data_dir]
    if code:
        cmd += ["--connect", code, "--folder", folder]
    logf = open(os.path.join(data_dir + ".log"), "a")
    return subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT)


def main():
    if not os.path.exists(JAR):
        sys.exit(f"Uploader jar missing: {JAR} (cd uploader && mvn package -DskipTests)")
    work = tempfile.mkdtemp(prefix="mcreatik-sim-")
    log(f"Work dir {work}")

    # ---- Setup (admin)
    token = call("POST", "/api/auth/login", {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD})["token"]
    event = call("POST", "/api/admin/events",
                 {"name": "Arun & Priya Wedding", "eventDate": date.today().isoformat(), "status": "UPCOMING"}, token)
    log(f"Event created: {event['name']} → {event['galleryUrl']}")
    codes = [call("POST", f"/api/admin/events/{event['id']}/uploaders", {"name": f"Camera {n}"}, token)["connectionCode"]
             for n in (1, 2, 3)]
    log("Three uploaders registered")

    pool = os.path.join(work, "pool")
    subprocess.run(["java", os.path.join(ROOT, "scripts", "GenPhotos.java"), pool, str(3 * PER_CAMERA), "3000", "2000"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    files = sorted(os.path.join(pool, f) for f in os.listdir(pool))

    api = urlparse(API)
    switch = Switch(PROXY_PORT, api.hostname, api.port or 80)
    guest = Guest(event["slug"])
    guest.start()
    guest.connected.wait(10)
    log("Guest scanned the QR code: gallery open and listening for live photos")

    folders, dirs, procs = {}, {}, {}
    for n in (1, 2, 3):
        folders[n] = os.path.join(work, f"camera{n}")
        dirs[n] = os.path.join(work, f"uploader{n}")
        os.makedirs(folders[n])
        os.makedirs(dirs[n])
        procs[n] = start_uploader(n, dirs[n], folders[n], codes[n - 1], proxy=(n == 3))
    log("Three uploader apps started (camera 3 goes through a switchable network)")
    time.sleep(3)

    written = {}
    outage = {}

    during = {}

    def internet_down():
        switch.set_online(False)
        outage["down"] = time.time()
        log(f"⚡ Camera 3 laptop: INTERNET DOWN for {OUTAGE_SECONDS}s (photographer keeps shooting)")

        def observe_then_restore():
            time.sleep(OUTAGE_SECONDS - 2)
            stats = call("GET", f"/api/admin/events/{event['id']}/stats", token=token)
            cam3 = next(u for u in stats["uploaders"] if u["name"] == "Camera 3")
            names = {p["originalFileName"] for p in
                     call("GET", f"/api/admin/events/{event['id']}/photos?limit=200", token=token)["photos"]}
            shot_offline = [n for n, t in written.items() if n.startswith("CAM3") and t > outage["down"]]
            during["status"] = cam3["status"]
            during["shot"] = len(shot_offline)
            during["leaked"] = len([n for n in shot_offline if n in names])
            log(f"During outage: dashboard shows Camera 3 {cam3['status']}; {len(shot_offline)} photos shot "
                f"offline are safely queued on the laptop, {during['leaked']} reached the server")
            switch.set_online(True)
            outage["up"] = time.time()
            log("✓ Camera 3 laptop: internet restored")
        threading.Thread(target=observe_then_restore, daemon=True).start()

    def crash_uploader_2():
        procs[2].send_signal(signal.SIGKILL)
        procs[2].wait()
        log("💥 Camera 2 uploader crashed (kill -9); camera keeps shooting")

        def restart():
            time.sleep(4)
            procs[2] = start_uploader(2, dirs[2])
            log("↻ Camera 2 uploader restarted")
        threading.Thread(target=restart, daemon=True).start()

    half = PER_CAMERA // 2
    cams = [
        Camera(1, files[0:PER_CAMERA], folders[1], written, {}),
        Camera(2, files[PER_CAMERA:2 * PER_CAMERA], folders[2], written, {half: crash_uploader_2}),
        Camera(3, files[2 * PER_CAMERA:], folders[3], written, {max(1, half // 2): internet_down}),
    ]
    for c in cams:
        c.start()

    for c in cams:
        c.join()
    log(f"All cameras finished: {len(written)} photos shot")
    while "up" not in outage:
        time.sleep(0.2)

    # Duplicates: same photo re-imported on the same laptop, and on a different camera's laptop.
    first = os.path.join(folders[1], "CAM1_0001.JPG")
    shutil.copy(first, os.path.join(folders[1], "CAM1_0001_copy.JPG"))
    shutil.copy(first, os.path.join(folders[2], "FROM_CAM1.JPG"))
    log("Copied an already-uploaded photo into two folders (duplicate test)")

    total = len(written)
    deadline = time.time() + TIMEOUT
    public = []
    while time.time() < deadline:
        public = call("GET", f"/api/public/events/{event['slug']}/photos?limit=100")["photos"]
        if len(public) >= total and len(guest.ready) >= total:
            break
        time.sleep(1)
    time.sleep(6)  # give duplicates time to (not) show up
    public = call("GET", f"/api/public/events/{event['slug']}/photos?limit=100")["photos"]

    # ---- Verify
    stats = call("GET", f"/api/admin/events/{event['id']}/stats", token=token)
    admin_photos = call("GET", f"/api/admin/events/{event['id']}/photos?limit=200", token=token)["photos"]
    by_name = {p["originalFileName"]: p for p in admin_photos}
    latencies = [guest.ready[by_name[n]["id"]] - t for n, t in written.items()
                 if n in by_name and by_name[n]["id"] in guest.ready]
    thumb_ok = urllib.request.urlopen(public[0]["thumbnailUrl"], timeout=10).status == 200 if public else False

    checks = [
        ("Every photo shot is in the gallery", len(public) == total, f"{len(public)}/{total}"),
        ("No duplicates in the gallery", len({p['id'] for p in public}) == len(public), ""),
        ("Duplicate copies were not added", stats["totalPhotos"] == total, f"total={stats['totalPhotos']}"),
        ("Guest received every photo live (no refresh)", len(guest.ready) == total, f"{len(guest.ready)}/{total}"),
        ("No failed photos", stats["failedPhotos"] == 0, f"failed={stats['failedPhotos']}"),
        ("Thumbnail served", thumb_ok, ""),
    ]
    for u in stats["uploaders"]:
        checks.append((f"{u['name']}: all photos uploaded", u["photosReady"] == PER_CAMERA,
                       f"{u['photosReady']}/{PER_CAMERA}"))
    cam3_after_outage = [n for n, t in written.items() if n.startswith("CAM3") and outage["down"] <= t < outage["up"]]
    checks.append(("Dashboard showed Camera 3 OFFLINE during the outage", during.get("status") == "OFFLINE",
                   during.get("status", "?")))
    checks.append(("Photos shot offline stayed queued on the laptop (none lost, none leaked)",
                   during.get("shot", 0) > 0 and during.get("leaked") == 0, f"{during.get('shot')} queued"))
    checks.append(("Photos shot during the outage arrived after reconnect",
                   len(cam3_after_outage) > 0 and all(n in by_name and by_name[n]["status"] == "READY" for n in cam3_after_outage),
                   f"{len(cam3_after_outage)} photos"))

    print()
    print("=" * 72)
    print(f"  McreatiK Live Gallery — event simulation   ({event['galleryUrl']})")
    print("=" * 72)
    for u in stats["uploaders"]:
        print(f"  {u['name']:<10} {u['status']:<8} photos uploaded: {u['photosReady']}")
    if latencies:
        lat = sorted(latencies)
        p95 = lat[max(0, int(len(lat) * 0.95) - 1)]
        print(f"  Shot → on guest's screen: median {statistics.median(lat):.1f}s, p95 {p95:.1f}s "
              f"(includes the outage/crash backlog)")
    print("-" * 72)
    ok = True
    for name, passed, detail in checks:
        ok &= bool(passed)
        print(f"  [{'PASS' if passed else 'FAIL'}] {name} {('(' + detail + ')') if detail else ''}")
    print("=" * 72)

    for p in procs.values():
        p.terminate()
    for p in procs.values():
        try:
            p.wait(10)
        except subprocess.TimeoutExpired:
            p.kill()
    if os.environ.get("SIM_KEEP_EVENT") != "1":
        call("DELETE", f"/api/admin/events/{event['id']}", token=token)
        log("Simulation event deleted (set SIM_KEEP_EVENT=1 to keep it and open the gallery)")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
