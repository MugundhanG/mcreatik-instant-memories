# McreatiK Live Gallery — V1 Architecture

> Status: V1 baseline. Scope is deliberately narrow: **multiple cameras upload automatically, guests see the photos live via a QR code.** No face recognition, payments, subscriptions or mobile app.

## 0. Repository findings

The repository was empty at the start of V1 (no commits, no existing McreatiK code), so there is nothing to reuse or conflict with. Everything below is greenfield.

## 1. System overview

```
 Camera 1 ─(FTP/Wi-Fi/tether/card)─> watched folder ─> Uploader 1 ─┐
 Camera 2 ─────────────────────────> watched folder ─> Uploader 2 ─┼─(1) POST /uploader/uploads  ──> Spring Boot API ──> PostgreSQL
 Camera 3 ─────────────────────────> watched folder ─> Uploader 3 ─┘   (2) PUT presigned URL ───────> Cloudflare R2 (originals, private)
                                                                       (3) POST .../complete ─────> API
                                                                                                    │
                                                              Processing worker (DB-backed queue) ◄─┘
                                                              validate → decode → web 2048px → thumb 640px → metadata
                                                                                                    │ PHOTO_READY
                                                                                                    ▼
     Guest phone ── QR ──> gallery.mcreatik.com/e/{slug} (Vercel, React) ◄── SSE /api/public/events/{slug}/stream
                           images ◄── cdn (R2 public bucket: web + thumb only)
```

Three logical apps, three folders:

| Folder      | App                         | Tech                                           | Deploys to                      |
|-------------|-----------------------------|------------------------------------------------|---------------------------------|
| `backend/`  | API + processing + realtime | Java 21, Spring Boot 4.1, JPA, Flyway          | Any container host (Render/Railway/Fly/VPS) |
| `web/`      | Guest gallery + Admin       | React 19, TypeScript, Vite, Tailwind 4         | Vercel                          |
| `uploader/` | Desktop uploader            | Java 21, SQLite queue, Swing status window     | Photographer laptop (fat jar → jpackage later) |

## 2. Key technical decisions

| Decision | Options considered | V1 choice | Why |
|---|---|---|---|
| Realtime | WebSocket, SSE, polling | **SSE** | One-way server→guest is all we need. Works over plain HTTP, auto-reconnects natively, `Last-Event-ID`-style catch-up via a cursor. No STOMP/broker. |
| Upload path | Through API, presigned PUT, multipart | **Presigned single PUT to R2** | Large files never touch the API. JPEGs are 5–30 MB so single PUT is fine; interrupted uploads restart from byte 0 (cheap), the *queue* is what's resumable. |
| Processing queue | Kafka/SQS, Redis, in-memory, DB | **PostgreSQL row queue (`FOR UPDATE SKIP LOCKED`)** | Zero new infrastructure, survives restarts, safe with >1 worker/instance. Photos table *is* the queue. |
| Image processing | libvips (native), ImageMagick, pure Java | **Pure Java (ImageIO + TwelveMonkeys + subsampled decode)** | No native deps in deploy image. Subsampled decode keeps a 24 MP JPEG at ~25 MB RAM. EXIF orientation applied. Can swap for libvips behind `ImageResizer` later if CPU becomes the bottleneck. |
| Folder watching | OS `WatchService`, polling scan | **Polling scan (2 s) + file-stability check** | `WatchService` is unreliable on macOS/network/USB volumes and fires while cameras are still writing. Scanning + "size unchanged across two scans" is boring and correct. |
| Uploader stack | Electron, Tauri, Python, Java | **Java 21 + SQLite** | Same language as backend, one fat jar, SQLite is a crash-safe local queue. Engine is UI-agnostic (Swing window or `--headless`). |
| Admin auth | Supabase Auth, OAuth, own JWT | **Own admin users + bcrypt + HS256 JWT** (Spring Security resource server) | One staff team, no third-party dependency on the critical path. Swappable for Supabase Auth later. |
| Uploader auth | API key, mTLS, JWT | **Random 256-bit token, stored as SHA-256 hash, shown once** | Simple, revocable (rotate), never stored in plaintext. Sent as `Authorization: Bearer`. |
| Image delivery | API streaming, signed URLs, public CDN | **Public R2 bucket for web/thumb (unguessable keys) + private bucket for originals** | Fastest possible for 500 guests (Cloudflare edge cache), zero API load for images. Originals never public. |
| Frontend | Two apps, one app | **One Vite app, admin code-split** | Guests never download admin code (`React.lazy`). One deploy. |
| Photo ordering | EXIF capture time, arrival time | **Arrival order (`ready_at desc, id desc`)**; EXIF `captured_at` stored | Camera clocks are rarely in sync and late uploads (after an outage) would otherwise be buried. Live guests expect "newest on top". |

## 3. Database schema (PostgreSQL, Flyway `V1__init.sql`)

```
admin_users (id uuid pk, email citext unique, password_hash, display_name, created_at)

events (
  id uuid pk, owner_id uuid fk admin_users,
  name, slug unique, event_date date, start_time timestamptz, end_time timestamptz,
  status  DRAFT|UPCOMING|LIVE|COMPLETED|ARCHIVED,
  cover_photo_id uuid null,               -- "coverImage"
  retention_until date null,              -- privacy: scheduled deletion
  created_at, updated_at
)

uploaders (
  id uuid pk, event_id uuid fk events ON DELETE CASCADE,
  name, device_identifier null,
  token_hash char(64) unique,             -- sha256(token); plaintext shown once
  token_prefix varchar(12),               -- for display ("mku_ab12…")
  status ONLINE|OFFLINE|ERROR, last_seen_at, last_error,
  queue_pending int, queue_failed int,    -- reported by heartbeat
  created_at, updated_at
)

photos (
  id uuid pk, event_id uuid fk ON DELETE CASCADE, uploader_id uuid fk ON DELETE SET NULL,
  original_file_name, storage_key, optimized_storage_key, thumbnail_storage_key,
  file_size bigint, width int, height int, mime_type, checksum_sha256 char(64),
  captured_at, uploaded_at, processed_at, ready_at,
  status QUEUED|UPLOADING|UPLOADED|PROCESSING|READY|FAILED,
  failure_reason, processing_attempts int, processing_started_at,
  created_at, updated_at,
  UNIQUE (event_id, checksum_sha256)       -- duplicate prevention, race-safe
)
indexes: photos(event_id, ready_at desc, id desc) where status='READY'   -- gallery feed
         photos(status, updated_at) where status in ('UPLOADED','PROCESSING') -- worker
         photos(event_id, uploader_id)                                     -- per-camera stats
```

Deletion is clean by construction: deleting an event cascades to uploaders and photos, and all objects live under `events/{eventId}/…` so R2 cleanup is a prefix delete.

**Future AI (not built):** add `photo_faces (id, photo_id fk cascade, bbox, embedding vector(512))` with pgvector, filled by a separate `FaceIndexer` that listens to `PhotoReadyEvent`. The upload/processing core does not change.

## 4. API surface

```
Admin (JWT)                                   Uploader (Bearer uploader token)
POST /api/auth/login                          GET  /api/uploader/me              → event + uploader info
GET  /api/admin/events                        POST /api/uploader/heartbeat       → lastSeen, queue stats, device id
POST /api/admin/events                        POST /api/uploader/uploads         → presigned PUT or {duplicate}
GET  /api/admin/events/{id}                   POST /api/uploader/uploads/{id}/complete
PATCH/DELETE /api/admin/events/{id}
GET  /api/admin/events/{id}/stats             Public (no auth, rate limited)
GET  /api/admin/events/{id}/photos            GET /api/public/events/{slug}
DELETE /api/admin/photos/{id}                 GET /api/public/events/{slug}/photos?before=cursor&limit=
POST /api/admin/events/{id}/uploaders         GET /api/public/events/{slug}/photos?after=cursor   (catch-up)
POST /api/admin/uploaders/{id}/rotate-token   GET /api/public/events/{slug}/stream              (SSE)
DELETE /api/admin/uploaders/{id}
```

SSE message: `event: PHOTO_READY`, data `{eventId, photoId, thumbnailUrl, webUrl, width, height, readyAt, cursor}`. Heartbeat comment every 20 s keeps proxies from closing the stream. On reconnect the client calls `?after=<last cursor>` so nothing is missed.

## 5. Upload protocol (per file)

1. Uploader sees a new, *stable* file in the watched folder → sniffs magic bytes (JPEG/PNG only) → SHA-256 → inserts into local SQLite queue (`UNIQUE(sha256)` ⇒ local duplicates ignored).
2. `POST /uploads {fileName, fileSize, mimeType, sha256}`
   - server validates type/size, event status, then `INSERT … ` with `UNIQUE(event_id, checksum)`.
   - existing `READY/UPLOADED/PROCESSING` row ⇒ `{duplicate: true}` (uploader marks done, no bytes sent).
   - existing `UPLOADING/FAILED` row ⇒ fresh presigned URL for the same photo id (resume).
3. `PUT` bytes to R2 (presigned, 15 min expiry, content-type and length bound).
4. `POST /uploads/{id}/complete` → server `HEAD`s the object, checks size ⇒ `UPLOADED` ⇒ wakes worker.
5. Worker claims row (`SKIP LOCKED`) ⇒ `PROCESSING` ⇒ pipeline ⇒ `READY` ⇒ publish `PHOTO_READY`.

Every step is idempotent, so any crash/network drop is fixed by simply retrying from the uploader's persisted state.

## 6. Processing pipeline (modular)

```java
interface PhotoProcessingStage { void process(PhotoProcessingContext ctx) throws Exception; }
PhotoProcessingPipeline = [ ValidateStage, DecodeStage, OptimizedImageStage, ThumbnailStage, MetadataStage ]
→ on success: PhotoReadyEvent (Spring ApplicationEvent) → GalleryBroadcaster (SSE)
                                                     → (future) FaceIndexer, async, never blocks the gallery
```

Stages are Spring beans ordered with `@Order`; adding a stage = adding a bean. Failures are retried up to 3 times, then `FAILED` with a reason visible in the admin.

## 7. Uploader architecture

```
PhotoSource (interface)  ← FolderPhotoSource (polling scan + stability)
                         ← FtpPhotoSource (built-in FTP server for camera Wi-Fi transfer, e.g. Canon R6 II)
                         ← CompositePhotoSource (both at once)          (later: Canon/Sony/Nikon SDKs)
      │ candidate files
      ▼
UploadQueue (SQLite: path, sha256, size, status, attempts, next_attempt_at, photo_id, error)
      │
UploadWorker × N (default 2 per uploader)  ── ApiClient (Java HttpClient) ── backend / R2
      │  exponential backoff: 2s,4s,8s … capped 5 min, +jitter; network errors never mark FAILED
      ▼
Heartbeat (15 s) ─ reports queue counts + connectivity
StatusModel ─► Swing window (connection, queue, progress, errors)  |  --headless logs
```

Local statuses: `QUEUED → UPLOADING → DONE` (or `DUPLICATE`), `FAILED` only for permanent errors (file unreadable, rejected type/size, 4xx). Network/5xx errors go back to `QUEUED` with a later `next_attempt_at`. On restart, rows left in `UPLOADING` are reset to `QUEUED`. The watched folder is also rescanned on start, so files added while the app was closed are picked up.

Onboarding: admin creates an uploader → dashboard shows a **connection code** once (base64 of `{server, token}`) → photographer pastes it into the uploader and picks the camera's folder.

## 8. Deployment

| Component | Where | Notes |
|---|---|---|
| `web/` | Vercel | `VITE_API_BASE_URL`, SPA rewrite to `index.html`. `gallery.mcreatik.com` |
| `backend/` | Docker image on Render / Railway / Fly.io (1 instance, 1–2 GB RAM) | `api.mcreatik.com`. Env-only config. Health: `/actuator/health`. |
| PostgreSQL | Supabase (use the **session pooler / direct** connection string, not transaction pooler) | Flyway migrates on boot. |
| R2 | 2 buckets: `mcreatik-instant-memories-originals` (private), `mcreatik-instant-memories-media` (public, custom domain `live-media.mcreatik.com`) | CORS on both: PUT from uploader (not needed, not a browser), GET from gallery origin for downloads. |

**Single backend instance in V1.** The SSE broadcaster is in-memory. Scaling out later means swapping `GalleryBroadcaster` for Redis pub/sub or Postgres `LISTEN/NOTIFY` (interface already isolated). The processing queue is already multi-instance safe.

## 9. Security

- Admin: bcrypt passwords, stateless JWT (HS256, `JWT_SECRET` ≥ 32 bytes, 12 h expiry). First admin bootstrapped from `ADMIN_EMAIL`/`ADMIN_PASSWORD` env on empty DB.
- Uploader: hashed tokens, per-event scope, rotatable, revocable (delete). Tokens never returned after creation.
- Uploads: presigned URLs, 15 min, key chosen by server, content-type/length signed; size limit (default 60 MB); type sniffed from bytes in the pipeline (extension ignored).
- Originals private; public bucket holds only resized derivatives under unguessable UUID keys.
- Public API: rate limited per IP (token bucket, in-memory); DRAFT/ARCHIVED events are 404.
- CORS allow-list via `CORS_ALLOWED_ORIGINS`. No secrets in code — all via env.

## 10. Privacy & retention

No guest accounts, no guest PII stored (not even IPs beyond the in-memory rate limiter). Event owner can delete photos or the entire event (DB cascade + R2 prefix delete). `retention_until` defaults to event date + 90 days; a daily job archives/deletes expired events (configurable, V1 logs + deletes originals first). No biometric processing in V1.

## 10b. Implementation notes (as built)

- Uploads are accepted in every status except ARCHIVED, so a forgotten status switch never loses photos. The first upload into an UPCOMING event flips it to LIVE automatically.
- Guests can open galleries in UPCOMING, LIVE and COMPLETED; DRAFT and ARCHIVED return 404.
- Web image 2048 px (q 0.85, progressive JPEG), thumbnail 640 px (q 0.78). Derivatives carry no EXIF (no GPS or camera serials reach guests).
- Deleting a photo also pushes `PHOTO_REMOVED` so it disappears from open guest screens.
- Local development uses a filesystem storage adapter with HMAC-signed URLs that behaves like R2 presigned URLs, so the full flow runs without cloud credentials.
- End-to-end verification: `scripts/simulate_event.py` (3 real uploader processes, 25 s internet outage on one laptop, kill -9 + restart of another, duplicate copies) passes all V1 success criteria.

### Camera FTP source

- Apache FtpServer embedded in the uploader, plain FTP on the LAN only (port 2121 by default, passive ports 50000–50100, active mode also allowed; anonymous login off; random 8-character camera-friendly password stored in the uploader config).
- Uploads land in a hidden `.incoming` folder and move into the photo folder only after reply 226 (transfer complete), so a Wi-Fi drop never publishes a half photo. Identical re-sends are dropped; same name with different content gets a `_1` suffix.
- Completed files are handed to the queue immediately; the folder scanner still runs and the queue dedupes.

## 11. Biggest technical risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Venue internet** is poor/shared | Photos late or not at all | Persistent queue + backoff (built); 2 concurrent uploads per laptop; advise 4G/5G hotspot per uploader. Later: upload a small "preview" first, original later. |
| **Camera → folder transfer** (the real bottleneck) | If files don't land in the folder, nothing works | Folder abstraction is camera-agnostic. Must field-test with our actual cameras (FTP via camera Wi-Fi, Canon/Sony transfer apps, tethering software). **Validate before selling.** |
| Partially written files picked up | Corrupt uploads | Stability check (size/mtime unchanged ≥2 scans) + magic-byte check + server re-validation. |
| RAW files | Can't be displayed | V1 accepts JPEG/PNG only; set cameras to RAW+JPEG and watch the JPEG folder. |
| CPU for image processing on a small host | Lag between shot and gallery | Subsampled decode; bounded worker pool; measured. Swap to libvips if needed. |
| SSE through proxies/CDNs | Streams cut/buffered | 20 s heartbeats, `X-Accel-Buffering: no`, client auto-reconnect + cursor catch-up (also a 60 s safety poll). |
| Guessable slugs (`arun-priya`) | Strangers browse a private event | Slug gets a short random suffix by default (`arun-priya-7k3d`); editable. |
| Single backend instance | Downtime = no live updates | Uploaders keep queuing during downtime; nothing is lost. Scale-out path documented. |

## 12. Phased plan

| Phase | Deliverable | Verification |
|---|---|---|
| 1 | This document, schema, repo layout | review |
| 2 | Backend: events, uploaders, auth, photos, upload sessions, dup detection | JUnit + Postgres integration tests |
| 3 | Storage abstraction: R2 (S3 SDK) + local filesystem adapter for dev/tests; processing pipeline | tests with real JPEGs |
| 4 | Uploader: source, SQLite queue, workers, retry, heartbeat, UI | JUnit incl. restart + outage tests against a fake server |
| 5 | Admin dashboard: login, events, QR, uploaders, stats | Vitest, typecheck, lint |
| 6 | Guest gallery: grid, viewer, download, share | Vitest |
| 7 | SSE realtime + catch-up | backend + frontend tests |
| 8 | Reliability: outage simulation, restart, duplicate storms | uploader tests + e2e |
| 9 | End-to-end simulation script: 3 uploaders → one event → gallery | `scripts/simulate-event.sh` |
