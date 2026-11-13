# Screen recording script

Environment: local demo (reset with `demo-env/reset.sh`). Web dev server `http://localhost:4174`, backend `http://localhost:8080`. Helpers: `demo-env/common.cjs` (`studioLogin`, `phone`, `finger`, `tap`, `smoothScroll`), `demo-env/rec.cjs` (CDP screencast).

## Global rules
- **Dashboard scenes:** viewport 1920x1080, device scale 1, browser zoom 100 percent (use 110 percent for scenes 2 and 7 if text is small). 30 fps.
- **Guest and client scenes:** phone viewport 390x844, `deviceScaleFactor` 3, touch enabled. Render into a 16:9 frame later (phone centred, about 540x1080 on the 1920x1080 canvas), so record at native phone size and place it in editing.
- **Cursor:** visible, slow, eased paths (about 0.6 to 0.9 s per move), pause 0.4 s before each click. On phone scenes use the finger circle from `finger()` and `tap()`.
- **Scrolls:** `smoothScroll`, 0.9 to 1.2 s per step, never fast. Hold 1 s after every scroll.
- **Secrets:** never show passwords, connection codes or the camera FTP password. Type the studio login off camera (log in in a hidden step, or record from a stored session and trim). Mask the Uploader password (leave the eye closed).
- **Names and data:** only demo data (Lens & Light Studio, Arun & Priya, Karthik & Meera). Mask HD-request emails and phone numbers.
- **Take extra:** hold 2 s at the start and end of every clip so editing has handles.

## Demo data: what exists and what must be seeded
`demo-env/seed_demo.py` creates: studio **Lens & Light Studio** (plan MAIN_4, logo, Instagram `lensandlight.studio`, phone 9876543210) and one event **Karthik & Meera Reception** (MAIN, UPCOMING, 12 days ahead, no photos). Studio login is in the seed (email `hello@lenslight.test`; password in the script, do not film it). `demo-env/photos/` holds 18 demo JPEGs (`DSC_5101.JPG` ...). `demo-env/event.json` references a wedding event "Arun & Priya Wedding" with a client login used by earlier recordings.

**Gaps the recording stage must seed through the API** (nothing exists after a clean `reset.sh`):
1. A **LIVE event with photos** for guest and results scenes: set "Arun & Priya Wedding" to LIVE, upload the 18 demo photos to the **Guest gallery** with at least 3 categories, one camera marked Online.
2. **Extra events** so the events list looks real: 4 to 5 events across statuses (Live, Upcoming, Completed) and types (Main, Quick).
3. **Cameras**: 2 to 3 cameras added (Camera 1, Camera 2, Candid) via "Add camera & get code"; at least one must be Online (uploader connected, online threshold is 10 min).
4. **Client gallery photos**: upload 10 to 18 full-quality demo photos to the **Client gallery** tab (originals), in categories.
5. **Client login** for the event (name, email, phone, Album limit 30) and a few album picks (5 to 8) already selected.
6. **Guest activity** for analytics: simulated guests (anonymous ids) with views, downloads, Likes (about 20 to 40), QR scans, 2 to 3 "Contacted you" taps, and 2 to 3 **HD photo requests** (fake contact numbers or emails). Include "Most loved" variety.
7. **Studio board / cards** need a contact number set (already 9876543210) so it prints on the board.
8. **Pricing page** needs no seed (public page).
9. Optional: a **second guest browser** profile to show a photo arriving live in scene 4/5.

## Scene 1: Hook (17 s)
- **Page:** none (motion graphics). Use demo photos from `demo-env/photos/`.
- **Actions:** none to record. Prepare 3 stills, blurred.

## Scene 2: Studio dashboard (17 s)
- **URL:** `/admin/login` then `/admin` (events list), then `/admin/account`.
- **Viewport:** 1920x1080.
- **Actions:**
  1. 0:00 Login page visible 2 s, email field typed on camera, password off camera or cut. Click **Sign in**.
  2. 0:03 Events list loads; hold 2 s. Cursor drifts to the plan summary at the top, then down the events list rows.
  3. 0:08 Cursor to the studio name (top right), click it to open **Account**.
  4. 0:10 Slow scroll 400 px: logo, studio name, Instagram, contact number. Cursor traces the logo, then the contact number field.
- **Highlight:** studio name and logo; WhatsApp contact field.
- **Transition:** crossfade 0.4 s.

## Scene 3: Create an event, board and QR (21 s)
- **URL:** `/admin/events/new` (via **New event**), then the event's Overview, then `/admin/events/<id>/cards` (**Event & table cards**), then Account, **Studio board**.
- **Viewport:** 1920x1080.
- **Actions:**
  1. 0:00 Click **New event**. Hold the type choice: **Main event** selected (cursor circles Main and Quick).
  2. 0:04 Click the Event name field, type "Karthik & Meera Reception", set the date. Do not press Create on camera if it would spend a pack event; use a pre-created event instead and cut.
  3. 0:09 Cut to the event Overview. Scroll to the **Guest QR code** card (smooth, 600 px). Hold on the QR.
  4. 0:12 Click **Event & table cards (8 x 12 in)**. Event card and table card show with logo and QR; slow zoom 1.1x.
  5. 0:17 Back, Account then **Studio board**: pick **Cream** then **Dark** design (one click each, 1 s apart).
- **Highlight:** Main/Quick radio, QR card, event and table cards, studio board.
- **Transition:** crossfade to uploader.

## Scene 4: Uploader (19 s)
- **Pages:** dashboard event Overview, **Cameras & uploaders**; then the Swing app under Xvfb (`uploader/`).
- **Viewport:** dashboard 1920x1080; uploader window 1280x800 (or 1920x1080 Xvfb screen), light theme.
- **Actions:**
  1. 0:00 Dashboard Overview: scroll to **Cameras & uploaders**. Cameras show Online with a green dot and photo counts. Cursor passes over each camera. (Do not click Add camera, since the code shows only once.)
  2. 0:05 Cut to Uploader **Live Upload** page: event name and camera, status card **Online**, the camera strip (2 to 3 cameras, "+ Add camera" visible), queue counts, pipeline Camera, Computer, Upload server, Live gallery.
  3. 0:09 Drop 3 demo photos one by one into the watched camera folder (or FTP push to port 2121): **Recent photos** gets a new row each time; counters and pipeline turn green.
  4. 0:15 Split screen (built in editing): right side is the guest phone gallery from scene 5 with the same photo appearing.
- **Highlight:** the camera strip, the pipeline, the Recent photos list.
- **Do not show:** the Camera Wi-Fi password (eye closed), the connection code.
- **Note:** film the uploader under Xvfb with a fixed window size; keep the cursor off screen except for one hover on "+ Add camera".
- **Transition:** whip/cut to the phone.

## Scene 5: Guest experience (25 s)
- **URL:** `/e/<slug>` (for example `/e/arun-priya-wedding-xxjc8fgw4v`).
- **Viewport:** 390x844, dpr 3, touch. Placed in the 16:9 frame.
- **Actions:**
  1. 0:00 Show the **event card** (QR) image full screen on the laptop (1 s), then open the gallery link (acts as the scan). Gallery loads: LIVE badge, studio name and logo, categories, grid.
  2. 0:04 From the studio side, drop one photo (from scene 4): the "1 new photo" button or a new tile appears at the top. Hold 2 s.
  3. 0:09 Tap a photo: full-screen viewer. Swipe left once. Tap the heart (**Like**), then **Download**. Pause 1 s.
  4. 0:15 Tap **Get HD photo**: the field appears; show it empty, then close without submitting (or type a fake number off camera).
  5. 0:18 Close viewer. Smooth scroll to the **Book Lens & Light Studio for your event** bar with **WhatsApp** and **Call** buttons. Tap WhatsApp circle with the finger only (do not leave the page).
  6. 0:22 Scroll back to top.
- **Highlight:** LIVE badge, new photo, heart, Download, Get HD photo, Book bar.
- **Transition:** crossfade.

## Scene 6: Client gallery (21 s)
- **URLs:** dashboard `/admin/events/<id>` Photos tab (Client gallery), then phone `/client/login?event=<slug>`, then dashboard Couple tab.
- **Viewport:** dashboard 1920x1080 then phone 390x844 dpr 3.
- **Actions:**
  1. 0:00 Dashboard **Photos**, tab **Client gallery**: grid of full-quality finals with categories. Hold 2 s.
  2. 0:04 Cut to phone: client login page, enter email, password typed off camera, **Sign in**.
  3. 0:08 Portal: tabs (All, Rituals, ...), tap the round **+** on 3 photos (turn green); counter "n / 30 selected" visible. Tap **My album** tab.
  4. 0:15 Open a photo, tap **Download original** (stay on the page).
  5. 0:17 Cut to dashboard **Couple** tab: **Album selection** list; cursor to **Download all (.zip)**, hover (do not download).
- **Highlight:** the + toggles, the selected counter, My album, Album selection and Download all.
- **Transition:** crossfade.

## Scene 7: Results (18 s)
- **URL:** `/admin/events/<id>` **Guests** tab.
- **Viewport:** 1920x1080 (zoom 110 percent).
- **Actions:**
  1. 0:00 Guests tab loads; counters: people, QR scans, views, downloads, **Likes**, **Contacted you**. Cursor moves left to right across them.
  2. 0:06 Smooth scroll 500 px: hourly chart (hold 2 s).
  3. 0:09 Scroll to **Most loved** list; click tab **Most downloaded**, then back to **Most loved**.
  4. 0:13 Scroll to **HD photo requests**: row with WhatsApp link and **Done** tick. Cursor to WhatsApp, then tick Done (one click).
- **Mask:** contact details of requests (use fake demo numbers; blur in editing anyway).
- **Highlight:** Likes, Contacted you, Most loved, HD request row.
- **Transition:** crossfade.

## Scene 8: Pricing and call to action (20 s)
- **URL:** `/pricing` (public page; `/admin/pricing` is the signed-in version, use the public one).
- **Viewport:** 1920x1080.
- **Actions:**
  1. 0:00 Open `/pricing`. Slow scroll to the Main and Quick prices. Cursor rests beside ₹2,499 then ₹1,499.
  2. 0:08 Scroll to monthly packs. Hold 3 s.
  3. 0:13 The end card is built in editing (no recording).
- **Highlight:** Main ₹2,499, Quick ₹1,499, monthly packs.
- **Transition:** crossfade to the end card.

## Recording order (to save time)
1. Seed data (above). 2. Scenes 2, 3, 7, 8 (dashboard). 3. Scene 6 dashboard parts. 4. Scenes 5 and 6 phone parts. 5. Scene 4 uploader under Xvfb with the guest phone recording at the same time for the split screen. 6. Reset and redo any clip with a glitch.
