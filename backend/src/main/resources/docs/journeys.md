# User journeys

Each journey follows one person from the screen, through the API and backend, to the table or file storage it touches.

## McreatiK onboards a studio

```mermaid
sequenceDiagram
  actor M as McreatiK
  participant UI as StudiosPage
  participant API as Backend
  participant DB as Postgres
  M->>UI: New studio (name, email, temporary password, plan)
  UI->>API: POST /api/admin/studios
  API->>API: AdminAccess.requireSuperAdmin
  API->>DB: INSERT admin_users (STUDIO, must_change_password)
  API-->>UI: 201 Studio
  M->>M: Sends the login by WhatsApp or call (outside the app)
```

1. `AdminBootstrap` creates McreatiK's own account on first boot.
2. `StudioAccordionItem` (one row per studio on `StudiosPage`) holds the plan, monthly quota, credits and `BrandingCard`, with Reset password, Disable and Delete.
3. `PricingPage` (menu: Pricing & payments) holds the prices and `PaymentsToCheck`, which lists PENDING UPI payments. McreatiK checks the UTR in the bank app, then approves, which adds credits.
4. `GalleryAccessCard` turns an event's guest or couple gallery off. Open phones get `GALLERY_CLOSED` after the commit.

## A studio runs an event

```mermaid
sequenceDiagram
  actor S as Studio
  participant UI as Dashboard
  participant API as Backend
  S->>UI: Log in
  UI->>API: POST /api/auth/login
  API-->>UI: token, mustChangePassword
  UI->>UI: RequireSession forces /admin/account
  UI->>API: POST /api/admin/account/password
  S->>UI: New event
  UI->>API: POST /api/admin/events
  alt allowance or credit left
    API-->>UI: 201 (slug, 6 default categories)
  else plan used up
    API-->>UI: 402
    UI->>S: BuyEventsDialog (UPI, enter UTR)
  end
```

1. **Prepare.** Print the QR from `QrPanel` or `PrintQrPage`; the link carries `?src=qr` to count scans. Add an uploader in `UploadersPanel` and copy the `MCK1.` connection code, which is shown once.
2. **During the event.** `PhotosPanel` likes, hides, sets the cover, deletes and moves photos. `AnalyticsPanel` refreshes every 30 s.
3. **Afterwards.** Set the status to COMPLETED. Create the couple's login in `ClientsCard` and send it with `SendButtons`. Download the album picks as a ZIP.

## The camera operator at the venue

```mermaid
sequenceDiagram
  participant Cam as Camera
  participant L as Uploader app
  participant Q as SQLite queue
  participant API as Backend
  participant R2 as R2
  L->>L: Paste MCK1 code (ConnectionCode.parse)
  Cam->>L: FTP upload into .incoming
  L->>L: Move after transfer completes (226)
  L->>Q: SHA-256 and save before any network call
  L->>L: PhotoPreparer makes 2048 and 640 px JPEGs
  L->>API: POST /api/uploader/uploads
  API-->>L: presigned PUT links (or DUPLICATE)
  L->>R2: PUT web and thumbnail
  L->>API: POST /uploads/{id}/complete
  API->>API: first photo makes the event LIVE
```

- **Connection code:** one string, `MCK1.<base64 {server, token}>`, so nobody types a URL and a 48-character token at the venue.
- **No half photos:** `FtpPhotoSource` moves a file into the watched folder only after a complete transfer.
- **Nothing lost:** `UploadEngine` queues first. Network errors retry with backoff. A revoked token or archived event pauses uploading. A restart returns in-flight photos to the queue.
- **Online status:** the dashboard shows the uploader online if a heartbeat arrived in the last 45 s, with its queue counts.

## A guest at the wedding

```mermaid
sequenceDiagram
  actor G as Guest phone
  participant W as live.mcreatik.com
  participant API as Backend
  participant CDN as im-media
  G->>W: Scan QR → /e/{slug}?src=qr
  W-->>G: Gallery code only
  G->>API: event + first 40 photos
  G->>API: activity VISIT (source qr)
  G->>API: EventSource /stream
  G->>CDN: thumbnails
  API-->>G: PHOTO_READY
  G->>G: Viewer (?photo=id), download, share
  G->>API: activity batch every 4 s
```

- **No sign-up.** The browser keeps only a random visitor ID.
- **Possible screens:**
  - not found (draft or archived event)
  - unavailable (McreatiK switched the gallery off)
  - the live grid
- **No jumping grid.** New photos wait behind a "new photos" button when the guest has scrolled down or has a photo open.
- **Removal requests.** Every photo has a "Request removal" email link to connect@mcreatik.com.
- **No originals.** Guests only get gallery copies.

## The couple

```mermaid
sequenceDiagram
  actor C as Couple
  participant UI as /client
  participant API as Backend
  participant R2 as R2 originals
  C->>UI: The studio's link /client/login?event=slug, email + temporary password
  UI->>API: POST /api/client/auth/login (email, password, eventSlug)
  API-->>UI: token, mustChangePassword → PasswordPage (or 409 CHOOSE_EVENT: pick the event, log in again)
  UI->>API: GET /api/client/me, /photos
  C->>UI: Tick a photo
  UI->>API: PUT /photos/{id}/selected
  API-->>UI: count (409 at the album limit → tick rolls back)
  C->>UI: Download
  UI->>API: GET /photos/{id}/download
  API-->>UI: link valid 10 minutes
  UI->>R2: original
```

Forgot password emails only while the login is active, the event isn't archived and the couple's gallery is on.

## Event life cycle

```mermaid
stateDiagram-v2
  [*] --> UPCOMING: created
  DRAFT --> UPCOMING: studio
  UPCOMING --> LIVE: first uploader photo or studio
  LIVE --> COMPLETED: studio
  COMPLETED --> ARCHIVED: studio
  UPCOMING --> Deleted: McreatiK
  LIVE --> Deleted: McreatiK
  COMPLETED --> Deleted: McreatiK
  ARCHIVED --> Deleted: McreatiK
  COMPLETED --> PhotosDeleted: RetentionJob
  PhotosDeleted --> ARCHIVED: record kept
  Deleted --> [*]
```

- Guests can see UPCOMING, LIVE and COMPLETED events (`EventStatus.isPubliclyVisible`).
- Every status except ARCHIVED accepts uploads.

> **Note** The automatic switch to LIVE (`promoteToLive`) does not send `EVENT_STATUS`. A guest who opened the gallery early keeps the "Upcoming" badge until they reload, even while photos arrive.
