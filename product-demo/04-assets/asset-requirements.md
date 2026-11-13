# Asset requirements

## Logos (in the repo)
| File | Use |
| --- | --- |
| `web/src/assets/mcreatik-mark.webp` | Square mark, intro animation and watermark |
| `web/src/assets/mcreatik-studios-logo.webp` | Full logo for light backgrounds (end card, intro) |
| `web/src/assets/mcreatik-studios-logo-light.webp` | Full logo for dark backgrounds |
| `web/src/assets/mk-logo.webp` | MK monogram |
| `web/public/icons/icon-512.png`, `favicon-64.png` | Fallback raster icons |
| Demo studio logo | `scratchpad/docs-env/logo.png` (Lens & Light Studio), already used in the seed |

Convert WebP to PNG with transparency before editing (ffmpeg or Pillow). Never alter the proportions.

## Brand colours (web/src/index.css and print theme)
| Token | Hex | Use in the video |
| --- | --- | --- |
| paper | `#faf9f7` | Intro and end card background, caption plates |
| ink | `#1c1917` | Text on light, dark bands |
| muted | `#78716c` | Secondary text |
| line | `#e7e5e4` | Dividers |
| live | `#dc2626` | The pulsing LIVE dot, used sparingly |
| gold accents (printed poster) | `#8a5a1c`, `#c8913c`, `#e9c47c` | Optional thin accent line, end card |
| ivory / dark board | `#F8EFDF`, `#1A1714` | Board designs, optional background |

## Fonts
- Display: **Cormorant Garamond** (`@fontsource/cormorant-garamond`), for headlines and the end-card tagline.
- Body and UI: **Inter Variable** (`@fontsource-variable/inter`), for callouts and subtitles.
- Both are open fonts installed in `web/node_modules`; copy the TTF/WOFF2 for ffmpeg `drawtext` or use the PNG overlays.

## Demo photos
- Source: `demo-env/photos/` (18 JPEGs, `DSC_5101.JPG` onward), already used by the demo seed. Confirm they are licensed or generated, not client photos. Never use real client photos.

## Footage to record
Scenes 2 to 8 per `02-recording-script/screen-recording-script.md`, saved to `05-recordings/` (not in git): `s2-dashboard.mp4`, `s3-event.mp4`, `s4-uploader.mp4`, `s4-guest-phone.mp4`, `s5-guest.mp4`, `s6-client.mp4`, `s6-couple-tab.mp4`, `s7-results.mp4`, `s8-pricing.mp4`.

## Music
Subtle corporate bed, instrumental, 2:50 or longer, about 90 to 100 BPM, soft pads and light plucks, no vocals, no copyright issues. Generate it (for example ElevenLabs sound generation or a generated synth bed), loop-able. Duck 10 to 12 dB under the voice.

## Voice
ElevenLabs, one professional English voice for all scenes (warm, clear). One file per scene (`s1.mp3` to `s8.mp3`) from `03-voiceover/narration-script.md`. Store in `03-voiceover/audio/` (outside git).

## Other
- Sound effects (optional, very light): soft UI click, soft notification chime for "new photo".
- End card: logo, line "Every moment is live.", "Book your free live demo", `mcreatik.com`, WhatsApp +91 99527 58545.
