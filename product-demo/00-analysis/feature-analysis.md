# Feature analysis: McreatiK Instant Memories

Source of truth: the in-app guides (`backend/src/main/resources/docs/sop-studio.md`, `guide-uploader.md`, `sop-guest.md`, `sop-couple.md`, `qa-*.md`) checked against the code. Anything not listed in "Verified features" must not appear in the video or the narration.

## 1. Modules

| Module | Folder | Who uses it | What it does |
| --- | --- | --- | --- |
| Studio dashboard | `web/src/admin/` (`/admin`) | Photography studio | Events, cameras, photos, QR cards, couple logins, guest analytics, buying events |
| Guest gallery | `web/src/gallery/` (`/e/<slug>`) | Wedding guests, on a phone | Live photo grid, full-screen viewer, Like, Download, Get HD photo, studio contact bar |
| Client portal | `web/src/client/` (`/client/login?event=<slug>`) | The couple | Private gallery: browse, pick album photos, download originals and ZIPs |
| Uploader | `uploader/` (Java Swing desktop app) | Photographer's laptop at the venue | Receives photos from cameras (FTP over Wi-Fi or folder), uploads a light copy, queues when offline |
| Backend API | `backend/` (Spring Boot) | All of the above | Auth, events, photos, billing, analytics |

## 2. User journey

1. **Studio signs in** at `/admin`, sets logo, name, Instagram and WhatsApp number once (Account).
2. **Creates an event** (Main or Quick) and prints the studio board and the event and table cards with the QR code.
3. **Connects cameras**: on the event's Overview, "Add camera & get code", paste the code into the Uploader. One laptop serves up to 4 cameras.
4. **Event day**: photographer keeps shooting; each photo reaches the guest gallery within seconds.
5. **Guests** scan the QR, no app or sign-up, watch photos arrive, Like, Download, ask for an HD photo, tap the studio's WhatsApp or Call.
6. **Couple** gets a private login after the event: browses the studio's full-quality finals, picks album photos (optional limit), downloads originals and ZIPs. The studio sees the picks live.
7. **Results**: Guests tab shows people, scans, views, downloads, Likes, "Contacted you", Most loved/downloaded/viewed, and HD photo requests as leads.
8. **Billing**: Main event, Quick event, monthly packs, Client-gallery-only, extra storage, paid by UPI QR in the dashboard.

## 3. Verified features (with references)

| # | Feature | Evidence |
| --- | --- | --- |
| 1 | Studio sign-in at `/admin` | `web/src/admin/LoginPage.tsx`, `sop-studio.md` s.1 |
| 2 | Studio branding: logo, name, Instagram, WhatsApp number, shown on gallery and printed cards | `web/src/admin/BrandingCard.tsx`, `sop-studio.md` s.2 |
| 3 | Events list with plan summary, sort and filters | `web/src/admin/EventsPage.tsx`, `sop-studio.md` s.3 |
| 4 | New event with types Main, Quick, Client gallery only; date, status, gallery address, months | `web/src/admin/NewEventPage.tsx`, `plans.ts` |
| 5 | Studio board (Cream, Dark, White designs) and per-event cards: event card, table card (8 x 12 in), A4 poster, QR as PNG/SVG, copy link | `web/src/admin/QrPanel.tsx`, `PrintQrPage.tsx`, `print/printParts.tsx` |
| 6 | Cameras and uploaders: "Add camera & get code", one-time connection code, Online/Paused status, Pause/Resume, Get new code | `web/src/admin/UploadersPanel.tsx`, `sop-studio.md` s.4 |
| 7 | Uploader app: Live Upload page, status card, camera Wi-Fi (FTP) settings, queue counts, pipeline Camera, Computer, Upload server, Live gallery, recent photos, Open live gallery | `uploader/src/main/java/com/mcreatik/uploader/ui/`, `guide-uploader.md` s.3 |
| 8 | Up to 4 cameras per laptop, with a camera strip and "+ Add camera" | `uploader/.../ui/components/CameraStrip.java`, `config/CameraSet.java` (`MAX = 4`) |
| 9 | Offline queue: photos wait on the laptop and upload when internet returns | `guide-uploader.md` s.5, `qa-studios-product.md`, `queue/UploadQueueTest.java` |
| 10 | Guest gallery: LIVE badge, no app, no login, new photos appear by themselves, "N new photos" button | `web/src/gallery/GalleryPage.tsx` |
| 11 | Full-screen viewer, swipe, Download, Like | `web/src/gallery/PhotoViewer.tsx` |
| 12 | "Get HD photo" request (email or WhatsApp number) | `PhotoViewer.tsx` (`onRequestHd`), `admin/AnalyticsPanel.tsx` |
| 13 | Gallery bar "Book <studio> for your event" with WhatsApp and Call; Follow on Instagram prompt | `GalleryPage.tsx` (lines near 187 and 245) |
| 14 | Photos tab: Guest gallery and Client gallery tabs, filters, Like, Hide, Make cover, Select, bulk actions | `web/src/admin/PhotosPanel.tsx`, `sop-studio.md` s.7 |
| 15 | Couple login (WhatsApp send), album limit, "Album selection" with Download all (.zip), Copy file names, Download list | `web/src/admin/ClientsCard.tsx` |
| 16 | Client portal: tabs, My album, "+" to pick, "x / limit selected", Download original, Download all ZIP | `web/src/client/ClientPortal.tsx`, `sop-couple.md` s.4-6 |
| 17 | Guest analytics: Likes, "Contacted you", Most loved, Most downloaded, Most viewed, hourly chart, HD requests with WhatsApp/email and Done | `web/src/admin/AnalyticsPanel.tsx`, `sop-studio.md` s.10 |
| 18 | Pricing and UPI payment: Main, Quick, monthly packs, extra events, client gallery, storage | `web/src/shared/policies/PricingPage.tsx`, `web/src/admin/BuyEventsDialog.tsx`, `sop-studio.md` s.3 |
| 19 | Studio contact: WhatsApp +91 99527 58545, mcreatik.com | `web/src/shared/policies/business.ts` |

## 4. Claims we can make (and wording to stay inside)

- "Photos appear in the guest gallery within seconds" (guides: "usually within seconds").
- "No app, no sign-up" for guests.
- "Up to four cameras on one laptop."
- "If the internet drops, nothing is lost; photos upload when it returns."
- "The couple sees full-quality finals; guests see lighter copies" (new events only; events made before 11 Nov 2026 keep one photo set, irrelevant for the demo).
- "You see the couple's album picks at once."

## 5. What NOT to show

- Super-admin pages: Studios, Billing admin, plan changes, payment confirmation (`StudiosPage.tsx`, `BillingAdmin.tsx`, `StudioAccordionItem.tsx`).
- Technical docs pages (architecture, database, api, findings, security), and the in-app Guides library, unless a short glimpse is wanted.
- Anything marked "decide" or "confirm" in the guides: free first event, tested concurrent-guest numbers, refund terms, "your own domain" (not supported).
- Unclaimed features: RAW support (not supported), face recognition, AI, video, custom domains, multiple staff logins (one login per studio today).
- Real data: any passwords, connection codes, camera FTP password, studio UPI/UTR details, real client photos, emails. The connection code is shown once; blur or cut it.
- Error states, "Draft/Archived" statuses, expired galleries.
- Competitor names and unverified stats such as "3x more bookings" or concurrent-user figures.
