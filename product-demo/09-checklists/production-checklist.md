# Production checklist

## Pre-recording
- [ ] Demo environment reset (`demo-env/reset.sh`) and backend healthy on :8080, web on :4174.
- [ ] Demo data gaps seeded (see `02-recording-script/screen-recording-script.md`): live event with photos, extra events, cameras Online, Client gallery photos, client login with picks, guest activity, HD requests.
- [ ] No production data, no real client photos, no real emails or phone numbers.
- [ ] Browser: clean profile, no extensions, notifications off, zoom 100 percent, cursor visible.
- [ ] Phone profile: 390x844, dpr 3, touch, finger circle enabled.
- [ ] Uploader runs under Xvfb at a fixed size, light theme, FTP port reachable; camera password hidden.
- [ ] Brand assets ready (logos, fonts, colours).
- [ ] Final narration approved (`03-voiceover/narration-script.md`).

## Recording
- [ ] Each scene recorded with 2 s handles at start and end.
- [ ] Smooth scrolls, no jerky mouse moves, no accidental clicks.
- [ ] No password, connection code or secret on screen; masked fields cut or blurred.
- [ ] No error toasts, console overlays or dev tools visible.
- [ ] Uploader and guest phone recorded together for the split screen (sync marker: the photo drop).
- [ ] Files saved to `05-recordings/` with the names from the asset list (not in git).

## Voiceover
- [ ] One voice chosen from the ElevenLabs list; same voice for all scenes.
- [ ] One take per scene, silence at start and 1 to 2 s tail, "McreatiK" pronounced correctly.
- [ ] Each scene's length measured and used for timing.
- [ ] No word in the audio that is missing from the claim check.

## Editing
- [ ] Intro logo animation and end card built in brand colours and fonts.
- [ ] Crossfades 0.4 s, zooms subtle, callouts minimal (max 2 per scene).
- [ ] Subtitles: 2 lines max, 42 characters per line, in sync, SRT saved in `07-subtitles/`.
- [ ] Music bed ducked under voice, mix at -14 LUFS, true peak -1 dBTP.
- [ ] Sensitive data blurred (HD request contacts).

## QA
- [ ] 1920x1080, 30 fps, H.264, length 2:30 to 2:40.
- [ ] Frame check at the middle of every scene: legible text, no broken UI, no personal data.
- [ ] Prices on screen match `sop-studio.md` and the pricing page.
- [ ] Spelling of McreatiK, WhatsApp number +91 99527 58545 and mcreatik.com correct.
- [ ] Listen on laptop speakers and phone; voice clear over music.
- [ ] Subtitles do not cover callouts.

## Delivery
- [ ] Final `McreatiK-Instant-Memories-Demo.mp4` and `subtitles.srt` sent to the owner (outside git).
- [ ] Text files committed through a pull request (no media).
- [ ] Note any open items: testimonials, free first event decision, tested guest limits.
