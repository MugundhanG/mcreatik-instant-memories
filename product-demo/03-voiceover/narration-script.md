# Narration script

Voice: warm, professional, calm English (ElevenLabs). Pace about 2.4 words per second. Audience: studio owners. Total **342 words** (as spoken, numbers written out), about 142 seconds of speech inside a 2:38 video. Every sentence maps to a verified feature in `00-analysis/feature-analysis.md`.

| # | Scene | Words | Speech (s) | Scene length (s) |
| --- | --- | --- | --- | --- |
| 1 | Hook | 35 | 15 | 17 |
| 2 | Studio dashboard | 36 | 15 | 17 |
| 3 | Create an event | 44 | 18 | 21 |
| 4 | Uploader | 40 | 17 | 19 |
| 5 | Guests | 54 | 22 | 25 |
| 6 | Client gallery | 45 | 18 | 21 |
| 7 | Results | 39 | 16 | 18 |
| 8 | Pricing and CTA | 49 | 20 | 20 |
| | **Total** | **342** | **142** | **158** |

Leave about 0.4 s of silence at the start and 1 to 2 s after the last word of every scene file (a past recording was cut off at the end). Scene 8 counts the prices as spoken words.

---

## Scene 1: Hook (target 15 s of speech, 17 s scene)
At every wedding, guests ask the same question: when do we get the photos? Usually, they wait days. With McreatiK Instant Memories, they see them while the celebration is still going. Every moment is live.

## Scene 2: Studio dashboard (15 s)
You run everything from one simple dashboard. Sign in, and every event you shoot is in one place. Add your studio name, logo and WhatsApp number once, and they appear on every gallery and printed card.

## Scene 3: Create an event (18 s)
Creating an event takes about a minute. Choose Main for weddings, or Quick for smaller functions, add the couple's names and the date. Then download your studio board and event cards, with your logo and a QR code, ready to print for the venue.

## Scene 4: Uploader (17 s)
At the venue, connect your camera to the uploader on your laptop over Wi-Fi. One laptop can handle up to four cameras. Then keep shooting. Every photo goes to the gallery automatically, and if the internet drops, nothing is lost.

## Scene 5: Guests (22 s)
Guests scan the QR code. No app, no sign-up. The live gallery opens with your studio's name, and new photos appear within seconds. They can view each photo full screen, like it and download it. And your WhatsApp and call buttons are always one tap away, so every guest can become your next enquiry.

## Scene 6: Client gallery (18 s)
After the event, upload your edited, full-quality photos to the couple's private gallery. Only the couple sees these, never the guests. They pick their album photos with one tap, and download everything in full quality. You see their choices at once, ready for album design.

## Scene 7: Results (16 s)
Then see the results. How many guests visited, which photos they loved most, and who asked for an HD photo. Each request comes with a WhatsApp or email link, so it is a possible booking waiting for your reply.

## Scene 8: Pricing and call to action (19 s)
A Main event is two thousand four hundred and ninety-nine rupees, and a Quick event is one thousand four hundred and ninety-nine. Monthly packs bring the price down further. Let your next event be seen live. Book your free live demo today. McreatiK Instant Memories. Every moment is live.

---

## TTS blocks (plain text, one file per scene; no stage directions)

Name the files `s1.mp3` ... `s8.mp3`. Say "McreatiK" as "Em-kree-ay-tick" if the voice mispronounces it (use a pronunciation hint, not a spelling change on screen).

### s1
At every wedding, guests ask the same question: when do we get the photos? Usually, they wait days. With McreatiK Instant Memories, they see them while the celebration is still going. Every moment is live.

### s2
You run everything from one simple dashboard. Sign in, and every event you shoot is in one place. Add your studio name, logo and WhatsApp number once, and they appear on every gallery and printed card.

### s3
Creating an event takes about a minute. Choose Main for weddings, or Quick for smaller functions, add the couple's names and the date. Then download your studio board and event cards, with your logo and a QR code, ready to print for the venue.

### s4
At the venue, connect your camera to the uploader on your laptop over Wi-Fi. One laptop can handle up to four cameras. Then keep shooting. Every photo goes to the gallery automatically, and if the internet drops, nothing is lost.

### s5
Guests scan the QR code. No app, no sign-up. The live gallery opens with your studio's name, and new photos appear within seconds. They can view each photo full screen, like it and download it. And your WhatsApp and call buttons are always one tap away, so every guest can become your next enquiry.

### s6
After the event, upload your edited, full-quality photos to the couple's private gallery. Only the couple sees these, never the guests. They pick their album photos with one tap, and download everything in full quality. You see their choices at once, ready for album design.

### s7
Then see the results. How many guests visited, which photos they loved most, and who asked for an HD photo. Each request comes with a WhatsApp or email link, so it is a possible booking waiting for your reply.

### s8
A Main event is two thousand four hundred and ninety-nine rupees, and a Quick event is one thousand four hundred and ninety-nine. Monthly packs bring the price down further. Let your next event be seen live. Book your free live demo today. McreatiK Instant Memories. Every moment is live.

## Claim check
| Sentence | Backed by |
| --- | --- |
| Photos appear within seconds | `sop-guest.md` s.2 ("usually within seconds") |
| No app, no sign-up | `sop-guest.md` s.1 |
| Up to four cameras per laptop | `CameraSet.MAX = 4` |
| If the internet drops, nothing is lost | `guide-uploader.md` s.5 |
| Album picks seen at once | `sop-couple.md` s.5 |
| Only the couple sees the Client gallery | `sop-studio.md` s.7 |
| HD request with WhatsApp or email link | `sop-studio.md` s.10 |
| Prices | `sop-studio.md` s.3, pricing page |
