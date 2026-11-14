# McreatiK Instant Memories

Wedding and event photos on guests' phones within seconds of the shot. Guests scan a QR code: no app, no sign-up. Studios run events from a dashboard; couples get a private portal for album picks and full-quality downloads.

```
Camera -> (Wi-Fi FTP / watched folder) -> Uploader app on the venue laptop (queue, retries)
       -> Cloudflare R2 -> backend -> live gallery (SSE) -> guests' phones
```

The full, current documentation (SOPs for every role, camera and uploader guides, architecture, API, database, deployment, security) lives in the app's Docs and in [`backend/src/main/resources/docs/`](backend/src/main/resources/docs). Super admins read it at `/admin/docs`. Start with [`overview.md`](backend/src/main/resources/docs/overview.md).

## The three apps

| App | Folder | Built with | Runs on |
|---|---|---|---|
| Backend API | [`backend/`](backend) | Java 21, Spring Boot 4, PostgreSQL, Flyway, Cloudflare R2 | Docker on the Oracle VM (im-api.mcreatik.com) |
| Web app | [`web/`](web) | React 19, TypeScript, Vite, Tailwind 4 | Cloudflare Workers static assets (live.mcreatik.com) |
| Uploader | [`uploader/`](uploader) | Java Swing, SQLite queue, built-in FTP server | The photographer's Windows or Mac laptop (latest release 1.0.22) |

The uploader connects with a **connection code** (`MCK1...`) copied from the event dashboard. There is no studio sign-in in the app. It also has [`deploy/oracle/`](deploy/oracle) (deploy, rollback, backup scripts) and [`scripts/`](scripts) (local run, event simulation).

## What the product does

- **Guest gallery and Client gallery.** Each event has two photo sets. The Guest gallery holds compressed photos (2048 px) from the uploader or the dashboard, shown live at `/e/<slug>`. The Client gallery holds full-quality photos uploaded from the dashboard, private to the couple (`/client`): album picks, downloads and ZIPs. Events made before 11 Nov 2026 keep a single set.
- **Likes, HD photo requests and categories.** Guests like photos and can tap Get HD photo to leave an email or WhatsApp number for the studio. Studios sort photos into categories (separate lists for each gallery).
- **Analytics.** People, QR scans, photo views, downloads, likes, peak hour and most loved, viewed and downloaded photos. Guests stay anonymous.
- **Many cameras.** Up to 4 cameras per laptop; a dropped venue connection never loses a photo.

## Packs and pricing

| | Single event | Monthly pack (2 events) | Monthly pack (4 events) |
|---|---|---|---|
| Main (weddings, big functions) | ₹2,499 | Main 2: ₹3,499 | Main 4: ₹4,999 |
| Quick (up to about 200 guests) | ₹1,499 | Quick 2: ₹2,499 | Quick 4: ₹3,999 |

- Main and Quick events include **5 GB for 3 months**.
- **Client gallery only:** ₹250 setup plus storage at ₹15 per GB per month, for the months chosen.
- Payment is by **UPI QR with the UTR** entered in the dashboard; McreatiK confirms it. There is no payment gateway.
- **Retention:** a gallery is open for its months; its photos are deleted for good 30 days after it closes. The event record is kept (archived).

See [`sop-studio.md`](backend/src/main/resources/docs/sop-studio.md) and [`qa-studios-buying.md`](backend/src/main/resources/docs/qa-studios-buying.md) for extra-event prices, upgrades and storage changes.

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
java -jar target/mcreatik-uploader.jar   # paste the connection code, choose a folder
```

In the dashboard: **New event**, then **Overview > Cameras & uploaders > Add camera & get code**, paste the code into the uploader, drop JPEGs into its folder and open the gallery link.

To rehearse a whole event against a running backend: `python3 scripts/simulate_event.py` (needs the uploader jar built; `SIM_KEEP_EVENT=1` keeps the event).

### Tests

```bash
cd backend  && mvn test        # needs PostgreSQL (TEST_DATABASE_URL to override)
cd uploader && mvn test
cd web      && npm test && npm run lint && npm run typecheck
```

CI (`.github/workflows/ci.yml`) runs all three on every push to `main` and every pull request.

## Deploy

Summary only; the runbook is [`deployment.md`](backend/src/main/resources/docs/deployment.md).

- **Web:** Cloudflare Workers (`web/wrangler.jsonc`, `VITE_API_BASE_URL=https://im-api.mcreatik.com`).
- **Backend:** Docker image built by GitHub Actions, deployed on the Oracle VM with `deploy/oracle/deploy.sh` (blue/green slots behind Caddy, `rollback.sh` to undo). Configuration is read from `gallery.env` on the VM (names in `deploy/oracle/gallery.env.example`).
- **Data:** Supabase PostgreSQL; two Cloudflare R2 buckets (private originals, public gallery copies at im-media.mcreatik.com). Nightly `backup.sh` dumps the database.

## For photographers

Download the Windows `.msi` or Mac `.dmg` (Apple Silicon or Intel) from the dashboard's **Install uploader** button; Java is included. Guides:

- [Uploader guide](backend/src/main/resources/docs/guide-uploader.md) (Windows and Mac, several cameras, switching events)
- [Canon EOS R6 Mark II](docs/CAMERA-SETUP-CANON-R6-MARK-II.md), [Sony α7 IV](backend/src/main/resources/docs/guide-sony.md), [Event-day checklist](backend/src/main/resources/docs/guide-event-day.md), [Event-day problem playbook](backend/src/main/resources/docs/guide-event-day-problems.md)
- [Uploader on a Mac](docs/UPLOADER-MACOS.md)

Shoot RAW+JPEG: the JPEG goes to guests, the RAW stays on the card. The uploader resizes each photo on the laptop and uploads only the light copies (about 0.6 MB), so photos reach guests in seconds even on a phone hotspot.

## PDFs in `docs/`

`McreatiK-Instant-Memories-Overview.pdf` and `McreatiK-Live-Gallery-Guide.pdf` are printed from the in-app Docs pages (see `docs/` and the Docs page's Download PDF). Regenerate them when the in-app docs change.
