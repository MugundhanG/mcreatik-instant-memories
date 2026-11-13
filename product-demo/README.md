# Product demo: McreatiK Instant Memories

A 16:9, about 2.5 minute demo video for photography studios, recorded from the real app. This folder holds the text planning documents. The video, recordings and audio are **not** committed.

## Contents
| Path | What it is |
| --- | --- |
| `00-analysis/feature-analysis.md` | Modules, user journey, features verified in code, what not to show |
| `00-analysis/positioning.md` | Problem, customer, positioning, selling points, pricing, proof points |
| `01-storyboard/storyboard.md` | 8 scenes with goal, visuals, on-screen text, timing |
| `02-recording-script/screen-recording-script.md` | Exact page, actions, cursor, viewport and demo data to seed |
| `03-voiceover/narration-script.md` | 342-word narration and per-scene TTS blocks |
| `04-assets/asset-requirements.md` | Logos, colours, fonts, photos, music, voice |
| `05-recordings/` | Screen recordings (kept outside git; `.gitkeep` only) |
| `06-editing/editing-instructions.md` | Transitions, callouts, subtitles, mix, end card |
| `07-subtitles/` | `subtitles.srt` (generated after the voice exists) |
| `08-final-video/` | `McreatiK-Instant-Memories-Demo.mp4` (outside git) |
| `09-checklists/production-checklist.md` | Checklists from pre-recording to delivery |

## Where media lives
Recordings, audio and the final MP4 are kept outside git (the scratchpad or shared drive) and sent to the owner as files. `.gitignore` here blocks `*.mp4 *.mov *.wav *.mp3 *.png *.webm` in `05-recordings` and `08-final-video`.

## How to regenerate
1. Reset and seed the demo environment (`demo-env/reset.sh`), then seed the extra demo data listed in the recording script.
2. Generate one voice file per scene from the TTS blocks in the narration script (ElevenLabs).
3. Record the scenes with Playwright (dashboard 1920x1080, phone 390x844) and the uploader under Xvfb.
4. Edit with ffmpeg following `06-editing/editing-instructions.md`; export the SRT and the MP4.
5. Run the production checklist.
