# Editing instructions

Output: `08-final-video/McreatiK-Instant-Memories-Demo.mp4`, 1920x1080, 30 fps, H.264 (CRF 18, yuv420p, AAC 192 kbps), 2:30 to 2:40. Subtitles: burned in plus `07-subtitles/subtitles.srt`. The voice sets timing: measure each scene audio and trim or hold video to fit (add 0.4 s lead and 1 to 1.5 s tail per scene).

## Look and style
- Colours from `04-assets/asset-requirements.md`: paper #faf9f7, ink #1c1917, red LIVE #dc2626 for the live dot, optional gold #c8913c line.
- Fonts: Cormorant Garamond for headlines, Inter for callouts and subtitles.
- Motion: slow, eased (ease-out), no flashy effects. Short crossfades (0.4 s) between scenes; hard cut only inside a scene.

## Global timeline (approximate; follow the voice)
| Scene | Start | Length | Source video |
| --- | --- | --- | --- |
| 1 Hook | 0:00 | 17 s | motion graphics + stills |
| 2 Dashboard | 0:17 | 17 s | `s2-dashboard` |
| 3 Event, board, QR | 0:34 | 21 s | `s3-event` |
| 4 Uploader | 0:55 | 19 s | `s4-uploader` + `s4-guest-phone` (split) |
| 5 Guests | 1:14 | 25 s | `s5-guest` in phone frame |
| 6 Client gallery | 1:39 | 21 s | `s6-client` (phone) + `s6-couple-tab` |
| 7 Results | 2:00 | 18 s | `s7-results` |
| 8 Pricing + CTA | 2:18 | 20 s | `s8-pricing` + end card |

## Scene-by-scene
1. **Intro (0:00 to 0:03):** the MK mark fades in on paper background, scales from 96 to 100 percent, holds 1 s, then the full logo replaces it. Optional soft whoosh. Then text cards: "Guests ask: when do we get the photos?" (2.5 s), "Usually, days." (2 s), "Every moment is live." with a red pulsing dot (3 s). Place blurred demo photos behind with a dark overlay (35 percent). Voice starts at 0:03.
2. **Dashboard:** slow zoom-in 100 to 108 percent on the events list. At the Account page, zoom 115 percent onto logo, studio name and contact field. Callout: "Your logo, name and WhatsApp, set once". Mask nothing needed except the password (cut it).
3. **Create event:** zoom to the Main/Quick choice (110 percent), then to the QR card and the table card. Callouts: "Main for weddings, Quick for smaller functions" and "Print-ready cards". Add a subtle highlight ring around the QR.
4. **Uploader:** at the uploader, zoom to the camera strip, then the pipeline, then Recent photos. Split screen at 0:15: uploader left (60 percent), phone right (40 percent) on a ink #1c1917 band; the new photo appears on both sides in sync (align at the drop). Callouts: "Up to 4 cameras, one laptop", "Offline? Photos wait and upload later".
5. **Guests:** place the phone recording (390x844) centred in a 540x1080 frame on a soft blurred version of the same footage, with 8 px rounded corners and a light shadow. Callouts at the side of the phone (not on it): "No app. No sign-up.", "New photos in seconds", "Every guest is a possible client". Zoom 120 percent on the heart and Download tap. Add the soft notification sound when the new photo arrives.
6. **Client gallery:** dashboard clip for 4 s, then the phone frame for 10 s, then the Couple tab with zoom to "Album selection". Callouts: "Full quality, only for the couple" and "You see their picks at once".
7. **Results:** zoom on the counters (Likes, Contacted you), then the Most loved row, then the HD request row. Blur contact details. Callouts: "See what guests loved", "HD requests become enquiries".
8. **Pricing and end card:** zoom on ₹2,499 and ₹1,499; callout "Monthly packs lower the price". From about 0:12 of the scene, crossfade to the end card (below) and hold for 5 to 6 s after the last word.

## Callouts
- Minimal: one per scene moment, max 2 per scene, 1.8 to 2.5 s each. Pill shape, ink #1c1917 background at 90 percent, white Inter Medium 36 px, 18 px padding, 16 px radius, fade in 0.2 s. Place near the element, not over key content.
- Highlight rings: 3 px, white or gold #c8913c, animated draw 0.3 s.
- No emojis, no arrows pointing wildly, no screen shake.

## Subtitle style
- Position: bottom centre, 64 px from the bottom edge.
- Font: Inter Medium 44 px, white, soft dark band (ink #1c1917 at 55 percent, radius 12 px, padding 12 x 24 px).
- Max 2 lines, max 42 characters per line. Break at natural phrases.
- Timing: each cue 1.2 to 5 s, at most 17 characters per second; start with the spoken word, end at 0.2 s after it.
- Build from the narration; align cue times to the voice using the audio length per scene (use a transcription pass for exact word times). Export `07-subtitles/subtitles.srt` and burn in with ffmpeg `subtitles` filter (style via `force_style`).
- Subtitle position must not cover callouts: move callouts to the upper third when a subtitle is up.

## Music and mix
- Music bed: subtle corporate, 0 to 0:03 slightly forward (-14 dB), then duck to about -26 dB under voice, return to -18 dB in silences, swell 1 dB at the end card. Fade out over the last 3 s.
- Ducking: sidechain compress (ffmpeg `sidechaincompress`: threshold 0.05, ratio 8, attack 20 ms, release 400 ms) or manual envelope.
- Voice: high-pass at 80 Hz, light de-ess, -3 dB peak limit.
- Loudness: master to **-14 LUFS integrated**, true peak -1.0 dBTP (ffmpeg `loudnorm=I=-14:TP=-1.0:LRA=9`, two-pass). Check with `ffmpeg -af ebur128`.

## End card (last 6 s, 2:32 to 2:38)
- Background paper #faf9f7. Centre: McreatiK logo (about 360 px wide), below it in Cormorant Garamond 64 px "Every moment is live.", then Inter 36 px "Book your free live demo".
- Footer row: `mcreatik.com` and WhatsApp `+91 99527 58545` (Inter 32 px, muted #78716c, with a thin gold line above).
- Animation: logo fades in 0.5 s, lines appear one by one every 0.6 s, final hold 2.5 s, audio fades out under the last second.

## Export commands (reference)
- Per scene clip: scale/pad to 1920x1080, 30 fps, trim to voice length.
- Assemble with ffmpeg `xfade` (0.4 s) and `acrossfade`, mix voice and music, loudnorm, burn subtitles.
- Final: `-c:v libx264 -crf 18 -preset slow -pix_fmt yuv420p -r 30 -c:a aac -b:a 192k -movflags +faststart`.
