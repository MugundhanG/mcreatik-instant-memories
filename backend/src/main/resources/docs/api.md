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
| 402 | none | Plan used up; payment needed |
| 403 | `FORBIDDEN`, `GALLERY_UNAVAILABLE`, `ACCOUNT_DISABLED` | Not allowed |
| 404 | `NOT_FOUND` | Missing, or not yours (deliberately the same) |
| 409 | `EVENT_CLOSED`, `CLIENT_EXISTS`, `EMAIL_TAKEN`, `UPLOAD_NOT_FOUND` | Conflicts with current state |
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
| GET / PATCH | `/api/admin/account` | Admin | Account and branding |
| POST / DELETE | `/api/admin/account/logo` | Admin | Multipart `logo`, PNG or JPEG up to 1 MB |
| POST | `/api/admin/account/password` | Admin | `{currentPassword, newPassword}` → new token; other devices logged out |

## Studios

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET / POST | `/api/admin/studios` | Super | List; create with temporary password and plan |
| PATCH | `/api/admin/studios/{studioId}` | Super | Rename, disable, reset password |
| PATCH | `/api/admin/studios/{studioId}/branding` | Super | Name, Instagram, logo size |
| POST / DELETE | `/api/admin/studios/{studioId}/logo` | Super | Studio logo |

There is no delete endpoint for studios; they are only disabled.

## Events

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/admin/events` | Admin | Own events (all for McreatiK), with photo counts |
| POST | `/api/admin/events` | Admin | `{name, eventDate, status?, slug?, …, ownerId? (super)}` → 201, or 402 |
| GET / PATCH / DELETE | `/api/admin/events/{eventId}` | Admin | Delete is permanent, files included |
| PATCH | `/api/admin/events/{eventId}/access` | Super | `{liveGallery?, clientGallery?}` |
| GET | `/api/admin/events/{eventId}/stats` | Admin | Counts, storage, per-uploader status |
| GET | `/api/admin/events/{eventId}/analytics` | Admin | Totals, hourly chart, top photos |

## Photos

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/admin/events/{eventId}/photos` | Admin | Filters: `sort`, `uploaderId`, `liked`, `hidden`, `day`, `category`, `selected`, `offset`, `limit` (60) |
| GET | `/api/admin/events/{eventId}/photo-facets` | Admin | Day counts, liked, hidden |
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
| GET / POST | `/categories` | List, create |
| PATCH / DELETE | `/categories/{categoryId}` | Rename, remove |
| POST | `/photos/category` | `{photoIds[], categoryId}` |
| GET / POST | `/clients` | One login per event; second create → 409 `CLIENT_EXISTS` |
| PATCH / DELETE | `/clients/{clientId}` | New password, disable, phone |
| GET / PUT | `/album` | Picks; `{limit}` |
| POST | `/downloads` | `{photoIds[]?, album?}` → ZIP `{path, progressPath, photos, fileName}` |

## Billing

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/api/admin/billing` | Admin | Plan, allowance used, credits, UPI details |
| POST | `/api/admin/billing/payments` | Admin | `{events, utr}` → PENDING payment |
| GET | `/api/admin/billing/payments?status=` | Super | Payments to check |
| POST | `/api/admin/billing/payments/{id}/approve` · `/reject` | Super | Approve adds credits |
| GET / PATCH | `/api/admin/billing/studios[/{studioId}]` | Super | `{plan?, monthlyQuota?, addCredits?}` |
| GET / PUT | `/api/admin/billing/settings` | Super | UPI ID, payee, prices |
| POST | `/api/admin/billing/online-orders` | Admin | `{events}` → Razorpay order at the server's price `{paymentId, razorpayOrderId, keyId, amountPaise, …}` (201) |
| POST | `/api/admin/billing/online-orders/{paymentId}/confirm` | Admin | `{razorpayPaymentId, razorpaySignature}` → checks the signature and the captured amount, adds the events once |
| POST | `/api/public/razorpay/webhook` | Razorpay | `X-Razorpay-Signature` over the raw body; `payment.captured` / `order.paid` add the events (idempotent). 404 when online payment is off |
| GET | `/api/public/pricing` | Public | Current prices for the `/pricing` page |

## Camera uploaders

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| POST | `/api/admin/events/{eventId}/uploaders` | Admin | Returns token and `MCK1.` connection code, shown once |
| POST | `/api/admin/uploaders/{uploaderId}/rotate-token` | Admin | Old token stops working |
| PATCH / DELETE | `/api/admin/uploaders/{uploaderId}` | Admin | Rename, remove |
| GET | `/api/uploader/me` | Uploader | Event and uploader details, including `eventDate` and `eventStartTime` (shown in the uploader; may be null) |
| POST | `/api/uploader/cameras` | Uploader | "Add camera" in the app: a new uploader ("Camera N") in the caller's event → `{uploaderId, name, token}`; 409 `TOO_MANY_CAMERAS` (8 per event) or `EVENT_ARCHIVED` |
| DELETE | `/api/uploader/me` | Uploader | "Remove camera": the uploader deletes itself; its photos stay |
| POST | `/api/uploader/heartbeat` | Uploader | Queue counts → `{eventStatus, acceptingUploads}` |
| POST | `/api/uploader/uploads` | Uploader | `{fileName, fileSize, mimeType, checksumSha256, prepared?}` → presigned PUTs or `DUPLICATE` |
| POST | `/api/uploader/uploads/{photoId}/complete` | Uploader | Safe to repeat |

## Guest gallery

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/public/events/{slug}` | Event, cover, count, branding; 404 for draft/archived; 403 `GALLERY_UNAVAILABLE` when switched off |
| GET | `/api/public/events/{slug}/photos` | `?before=` or `?after=` cursor; 40 default, 100 max, 200 for catch-up |
| GET | `/api/public/events/{slug}/stream` | `text/event-stream`: `CONNECTED`, `PHOTO_READY`, `PHOTO_REMOVED`, `EVENT_STATUS`, `GALLERY_CLOSED` |
| POST | `/api/public/events/{slug}/activity` | `{visitorId, items[]}`, max 50 items → 204 |

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
| GET | `/api/admin/docs` · `/api/admin/docs/{slug}` | Super | This documentation |
| GET | `/actuator/health` | Public | Used by `deploy.sh` |
| PUT / GET | `/local-storage/**` | Development only | Exists only when `STORAGE_TYPE=local` |
