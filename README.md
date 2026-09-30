# McreatiK Live Gallery

Guests scan a QR code at an event and watch the photographers' photos arrive live on their phones. No app, no sign-up.

```
Camera → (camera's own transfer: FTP / Wi-Fi app / tethering / card) → watched folder
       → McreatiK Uploader (queue, retries) → Cloudflare R2 → processing → live gallery (SSE) → guests
```

Multiple cameras upload into the same event simultaneously; a dropped venue connection never loses a photo.

| Folder | What | Stack |
|---|---|---|
| [`backend/`](backend) | API, image processing, realtime | Java 21, Spring Boot 4.1, PostgreSQL, Cloudflare R2 |
| [`web/`](web) | Guest gallery (`/e/:slug`) + staff dashboard (`/admin`) | React 19, TypeScript, Vite, Tailwind 4 |
| [`uploader/`](uploader) | Desktop uploader for photographers' laptops | Java 21, SQLite, Swing |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Architecture, schema, protocol, risks, roadmap | |
| [`scripts/`](scripts) | Local run + end-to-end event simulation | |

V1 deliberately excludes face search, payments, subscriptions and a mobile app. The processing pipeline and `PhotoReadyEvent` are the extension points for "Find my photos" later.

---

## Run it locally

Prerequisites: Java 21, Maven, Node 22, PostgreSQL 16.

```bash
# 1. Database
createuser -P mcreatik          # password: mcreatik
createdb -O mcreatik mcreatik
createdb -O mcreatik mcreatik_test   # for backend tests

# 2. Backend (filesystem storage, no R2 needed)
cd backend && mvn -q package -DskipTests && cd ..
scripts/run-backend-local.sh    # http://localhost:8080, admin: admin@mcreatik.local / change-me-please

# 3. Web
cd web && npm install && npm run dev   # http://localhost:5173/admin

# 4. Uploader
cd uploader && mvn -q package -DskipTests
java -jar target/mcreatik-uploader.jar            # window: paste connection code, choose folder
```

In the dashboard: **New event → Add uploader** (copy the connection code) → paste it into the uploader → drop JPEGs into the chosen folder → open the gallery link or scan the QR.

### Simulate a real event (V1 acceptance test)

With the backend running:

```bash
cd uploader && mvn -q package -DskipTests && cd ..
python3 scripts/simulate_event.py
```

It creates "Arun & Priya Wedding", registers three uploaders, starts three real uploader processes, and has three "cameras" write 60 photos. Midway it cuts camera 3's internet for 25 s and `kill -9`s camera 2's uploader and restarts it. It also copies duplicates into the folders. It then verifies that every photo reached the gallery exactly once, that a connected guest received each one live, and the per-camera counts. `SIM_KEEP_EVENT=1` keeps the event so you can open the gallery afterwards.

### Tests

```bash
cd backend  && mvn test          # 40 tests, needs PostgreSQL (TEST_DATABASE_URL to override)
cd uploader && mvn test          # 23 tests incl. outage/reconnect, restart, duplicates
cd web      && npm test && npm run lint && npm run typecheck
```

CI runs all three on every PR (`.github/workflows/ci.yml`).

---

## Deploy

| Piece | Where | Notes |
|---|---|---|
| Database | Supabase Postgres | Use the **session pooler** or direct connection string (Flyway + `SKIP LOCKED` need a session). |
| Storage | Cloudflare R2 | Two buckets, see below. |
| Backend | Any Docker host (Render, Railway, Fly.io, a VPS) | `backend/Dockerfile`. One instance, 1–2 GB RAM. All config via env: see `backend/.env.example`. |
| Web | Vercel | Root directory `web`, framework Vite, env `VITE_API_BASE_URL=https://api.mcreatik.com`. `vercel.json` handles SPA routing. |

**R2 setup**
1. Create `mcreatik-originals` (private) and `mcreatik-media` (public through a custom domain, e.g. `media.mcreatik.com`).
2. Create an R2 API token with Object Read & Write on both buckets → `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`.
3. CORS on `mcreatik-media`: allow `GET` from `https://gallery.mcreatik.com` (guests download photos with `fetch`).
4. Set `R2_MEDIA_PUBLIC_BASE_URL=https://media.mcreatik.com`.

**Domains**
- `gallery.mcreatik.com` → Vercel (guest links are `https://gallery.mcreatik.com/e/<slug>`; set `PUBLIC_GALLERY_BASE_URL` to match).
- `api.mcreatik.com` → backend (`API_PUBLIC_BASE_URL`, `CORS_ALLOWED_ORIGINS=https://gallery.mcreatik.com`).

Scaling beyond one backend instance: processing is already multi-instance safe; swap the in-memory `GalleryBroadcaster` for Redis/Postgres pub-sub first.

---

## For photographers (uploader)

1. Set the camera to shoot **RAW+JPEG** (JPEGs are what guests see) and to transfer JPEGs to a folder on the laptop (camera app / FTP / tethering software).
2. Start McreatiK Uploader, paste the connection code from the dashboard, choose that folder.
3. Leave it running. Green means online. Amber means offline, and photos are safely queued and upload automatically when the connection returns.

One laptop per camera is simplest. To run two cameras on one laptop, start a second instance with `--data-dir ~/.mcreatik-camera2`. Headless mode: `java -jar mcreatik-uploader.jar --headless`.
