# API reference

All endpoints live under `https://im-api.mcreatik.com`. Requests and responses are JSON unless noted otherwise. Logged-in calls send `Authorization: Bearer <token>`.

## Conventions

| Badge | Who | Token |
| --- | --- | --- |
| Public | Anyone | None |
| Admin | Studio (own events) or McreatiK (all) | Admin JWT |
| Super | McreatiK only | Admin JWT + `AdminAccess.requireSuperAdmin`; others get 403 |
| Client | The couple | Client JWT, limited to one event |
| Uploader | Camera app | `mku_…` token, limited to one event |

**Errors** always have the shape `{"code": "...", "message": "..."}`.

| Status | Example codes | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED`, `BAD_REQUEST`, `INVALID_SLUG` | Input is wrong |
| 401 | none | Not logged in, expired, or password changed since login |
| 402 | `EVENT_PAYMENT_REQUIRED`, `CLIENT_PAYMENT_REQUIRED` | Plan used up, or no client gallery paid for; payment needed |
| 403 | `FORBIDDEN`, `GALLERY_UNAVAILABLE`, `GALLERY_EXPIRED`, `ACCOUNT_DISABLED`, `EXTEND_INSTEAD` | Not allowed (`EXTEND_INSTEAD`: studios can't set the delete date; they keep it open more months) |
| 404 | `NOT_FOUND` | Missing, or not yours (deliberately the same) |
| 409 | `EVENT_CLOSED`, `CLIENT_EXISTS`, `EMAIL_TAKEN`, `UPLOAD_NOT_FOUND`, `STORAGE_FULL` | Conflicts with current state |
| 400 (gallery rules) | `GUEST_COMPRESSED_ONLY`, `CLIENT_ORIGINAL_REQUIRED`, `GALLERY_MISMATCH`, `INVALID_CONTACT` | Guest gallery takes compressed uploads only; Client gallery needs the original file; photo and category in different galleries; bad HD-request contact |
| 409 (limit) | `TOO_MANY_REQUESTS` | A visitor made too many HD requests |
| 429 | `RATE_LIMITED` | Too many requests (`Retry-After` header) |
| 503 | `TOO_MANY_VIEWERS` | Live stream full |

**Paging:**

- Guests use a cursor: `nextCursor`, then `?before=`.
- The dashboard and portal use an offset: `nextOffset`, then `?offset=`.

## Login and account

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | Public | `{email, password}` → `{token, expiresAt, email, displayName, role, mustChangePassword}` |
| POST | `/api/auth/forgot-password` | Public | `{email}` → always 204 |
| GET / PATCH | `/api/admin/account` | Admin | Account and branding. PATCH takes any of `displayName`, `instagram`, `phone` (contact number for guests; a 10-digit Indian number gets +91, blank removes it, 400 `INVALID_PHONE`), `logoScale` (50–200) and `boardTheme` (`cream`, `dark` or `white`: the studio board and card design). Public events carry it as `brand.phone` |
| GET / POST / DELETE | `/api/admin/account/logo` | Admin | GET: the logo file itself (for the printed board; 404 without a logo). POST: multipart `logo`, PNG, JPEG or WebP up to 1 MB |
| POST | `/api/admin/account/password` | Admin | `{currentPassword, newPassword}` → new token; other devices logged out |

## Studios

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET / POST | `/api/admin/studios` | Super | List; create with temporary password and plan |
| PATCH | `/api/admin/studios/{studioId}` | Super | Rename, disable, reset password |
| PATCH | `/api/admin/studios/{studioId}/branding` | Super | Name, Instagram, contact number (`phone`), logo size |
| POST / DELETE | `/api/admin/studios/{studioId}/logo` | Super | Studio logo |

There is no delete endpoint for studios; they are only disabled.

## Events

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/admin/events` | Admin | Own events (all for McreatiK), with photo counts |
| POST | `/api/admin/events` | Admin | `{name, eventDate, eventType? (MAIN / QUICK / CLIENT), status?, slug?, …, ownerId? (super)}` → 201, or 402. The detail carries `activeUntil`, `storageQuotaGb`, `storageBytes`, `storageLocked`, `expired` |
| GET / PATCH / DELETE | `/api/admin/events/{eventId}` | Admin | Delete is permanent, files included |
| GET | `/api/admin/events/{eventId}/logo` | Admin | The owning studio's logo file, for the event and table cards (404 without a logo) |
| PATCH | `/api/admin/events/{eventId}/access` | Super | `{liveGallery?, clientGallery?}` |
| GET | `/api/admin/events/{eventId}/stats` | Admin | Counts, storage, per-uploader status |
| GET | `/api/admin/events/{eventId}/analytics` | Admin | Totals, hourly chart, top photos |

## Photos

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/admin/events/{eventId}/photos` | Admin | Filters: `gallery` (`GUEST` or `CLIENT`), `sort`, `uploaderId`, `liked`, `hidden`, `day`, `category`, `selected`, `q` (file-name search, partial match), `offset`, `limit` (60) |
| GET | `/api/admin/events/{eventId}/photo-facets` | Admin | Day counts, liked, hidden; takes `?gallery=` |
| POST | `/api/admin/events/{eventId}/photos` | Admin | Multipart web + thumbnail + metadata (12 MB max) |
| POST | `/api/admin/events/{eventId}/original-uploads` | Admin | Presigned PUT for an original |
| POST | `/api/admin/events/{eventId}/photos/{photoId}/original` | Admin | Confirm original arrived |
| POST | `/api/admin/events/{eventId}/photos/bulk` | Admin | `{photoIds[] (max 1,000), action: LIKE \| UNLIKE \| HIDE \| SHOW \| DELETE}` → `{changed}`; only this event's photos; files removed in batches after commit |
| PATCH | `/api/admin/photos/{photoId}` | Admin | `{hidden?, liked?}` |
| GET | `/api/admin/photos/{photoId}/original` | Admin | `{url}` valid 5 minutes |
| DELETE | `/api/admin/photos/{photoId}` | Admin | Also sends `PHOTO_REMOVED` |

## Categories, client login and album

All paths in this section start with `/api/admin/events/{eventId}`.

| Method | Path | Notes |
| --- | --- | --- |
| GET / POST | `/categories` | List (`?gallery=GUEST\|CLIENT`), create (`{name, gallery}`); each gallery has its own list |
| PATCH / DELETE | `/categories/{categoryId}` | Rename, remove |
| POST | `/photos/category` | `{photoIds[], categoryId}`; 400 `GALLERY_MISMATCH` if a photo and the category are in different galleries |
| GET | `/hq-requests` | Guests' HD photo requests, newest first, with `contact`, `kind` (`EMAIL` / `WHATSAPP`), `photoId` and `done` |
| PATCH | `/hq-requests/{requestId}` | `{done}`: tick a request as handled |
| GET / POST | `/clients` | One login per event; second create → 409 `CLIENT_EXISTS` |
| PATCH / DELETE | `/clients/{clientId}` | New password, disable, phone |
| GET / PUT | `/album` | Picks; `{limit}` |
| POST | `/downloads` | `{photoIds[]?, album?}` → ZIP `{path, progressPath, photos, fileName}` |

## Billing

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/admin/billing` | Admin | Plan, this month's pack usage, `carryOver`, `mainCredits` / `quickCredits` (`credits` = both), `packActive`, `planPaidUntil`, `unitPrice` / `quickUnitPrice` (what one more Main / Quick event costs this studio: the pack's extra price while a pack is paid up), `prices`, UPI details |
| POST | `/api/admin/billing/payments` | Admin | `{item, quantity, eventId?, plan?, utr}` → PENDING payment, applied at once. `item`: `MAIN_EVENT` / `QUICK_EVENT` (quantity = events), `UPGRADE` (`eventId` of a Quick event; 409 `NOT_QUICK`), `PLAN` (`plan` = a pack, quantity = months; a different pack starts today), `CLIENT_SETUP` (quantity = client galleries, 1–50), `STORAGE` (`eventId`, quantity = GB, 1–1000), `EXTEND` (`eventId`; quantity is set by the server to the event's GB, rounded up; adds `active_months`, 3 by default). The older `{events, utr}` still means Main events |
| GET | `/api/admin/billing/payments?status=` | Super | Payments to check |
| POST | `/api/admin/billing/payments/{id}/approve` · `/reject` | Super | Reject reverses what the payment applied (credits, upgrade or pack month) |
| GET / PATCH | `/api/admin/billing/studios[/{studioId}]` | Super | `{plan?, monthlyQuota?, addCredits?, addQuickCredits?, addClientCredits?, planPaidUntil?}`; moving onto a pack sets paid-until to a month from today unless given |
| GET / PUT | `/api/admin/billing/settings` | Super | UPI ID, payee, prices (incl. `main2ExtraPrice` … `quick4ExtraPrice`; a missing price keeps its value) |
| POST | `/api/admin/billing/quote` | Admin | Same body as `/payments` (without `utr`) → the price the server would charge `{amount, months, includedGb, includedMonths, …}`; nothing is bought |
| GET | `/api/public/pricing` | Public | Current prices for the `/pricing` page |

## Camera uploaders

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| POST | `/api/admin/events/{eventId}/uploaders` | Admin | Returns token and `MCK1.` connection code, shown once |
| POST | `/api/admin/uploaders/{uploaderId}/rotate-token` | Admin | Old token stops working |
| PATCH | `/api/admin/uploaders/{uploaderId}` | Admin | `{name?, paused?}`: rename (unique per event, 409 `CAMERA_NAME_TAKEN`) and/or pause / resume |
| DELETE | `/api/admin/uploaders/{uploaderId}` | Admin | Remove |
| GET | `/api/uploader/me` | Uploader | Event and uploader details, including `eventDate`, `eventStartTime` (may be null) and `paused` |
| PATCH | `/api/uploader/me` | Uploader | `{name?, paused?}` from the app → `{name, paused}` |
| POST | `/api/uploader/cameras` | Uploader | "Add camera" in the app: `{name?}` (blank: "Camera N") → a new uploader in the caller's event `{uploaderId, name, token}`; 409 `TOO_MANY_CAMERAS` (8 per event), `EVENT_ARCHIVED` or `CAMERA_NAME_TAKEN` |
| DELETE | `/api/uploader/me` | Uploader | "Remove camera": the uploader deletes itself; its photos stay |
| POST | `/api/uploader/heartbeat` | Uploader | Queue counts → `{eventStatus, acceptingUploads, paused, uploaderName}` |
| POST | `/api/uploader/uploads` | Uploader | `{fileName, fileSize, mimeType, checksumSha256, prepared?}` → presigned PUTs or `DUPLICATE`; 409 `UPLOADER_PAUSED` while the camera is paused |
| POST | `/api/uploader/uploads/{photoId}/complete` | Uploader | Safe to repeat |

## Guest gallery

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/public/events/{slug}` | Event, cover, count, branding; 404 for draft/archived; 403 `GALLERY_UNAVAILABLE` when switched off |
| GET | `/api/public/events/{slug}/photos` | `?before=` or `?after=` cursor; 40 default, 100 max, 200 for catch-up; `?category=` filters by category. Guests only ever get `GUEST` photos |
| GET | `/api/public/events/{slug}/categories` | The event's guest-gallery categories, with photo counts |
| POST | `/api/public/events/{slug}/hq-requests` | "Get HD photo": `{photoId, contact, name?, visitorId}`; `contact` is an email or a 10-digit WhatsApp number → 204. 400 `INVALID_CONTACT`; 409 `TOO_MANY_REQUESTS` (per visitor) |
| GET | `/api/public/events/{slug}/stream` | `text/event-stream`: `CONNECTED`, `PHOTO_READY`, `PHOTO_REMOVED`, `EVENT_STATUS`, `GALLERY_CLOSED` |
| POST | `/api/public/events/{slug}/activity` | `{visitorId, items[]}`, max 50 items → 204. Types: `VISIT`, `PHOTO_VIEW`, `DOWNLOAD`, `LIKE` / `UNLIKE` (the heart; one like per visitor and photo), `SHARE` (old clients only; the app no longer sends it), `INSTAGRAM`, `CONTACT` (WhatsApp / call tap, counted as `totals.contacts`) |

## Couple portal

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/client/auth/login` | Public; 403 when disabled or gallery off |
| POST | `/api/client/auth/forgot-password` | Public; always 204 |
| GET | `/api/client/me` | Event, branding, album limit, categories |
| GET | `/api/client/photos?category=&offset=` | Paged photos |
| PUT | `/api/client/photos/{photoId}/selected` | `{selected}` → 409 at the limit |
| GET | `/api/client/photos/{photoId}/download` | `{url, original}` valid 10 minutes |
| POST | `/api/client/downloads` | `{album?, photoIds[]?, category?}` → ZIP |
| POST | `/api/client/password` | New token |

## Downloads, docs and health

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/public/downloads/{token}` | Link holder | Streams the ZIP; link valid 30 minutes; 3 at once, others queue up to 10 minutes |
| GET | `/api/public/downloads/{token}/progress` | Link holder | `{state, done, total, percent}` |
| GET | `/api/admin/docs` · `/api/admin/docs/{slug}` · `/api/admin/docs/images/{name}` | Admin | This documentation. The super admin gets every page (each with a `studio` flag); a studio gets only the 10 studio guides and their images, and 404 for anything else |
| GET | `/actuator/health` | Public | Used by `deploy.sh` |
| PUT / GET | `/local-storage/**` | Development only | Exists only when `STORAGE_TYPE=local` |
