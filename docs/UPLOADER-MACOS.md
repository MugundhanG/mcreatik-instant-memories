# McreatiK Instant Memories Uploader on a Mac (macOS)

> **Status: not yet field-tested on a Mac.** The uploader is plain Java 21 and is written to run on macOS
> (port 2121 instead of 21, folder polling instead of macOS file events, data in your home folder), but every
> real-camera test so far was on Windows. Run the [rehearsal](#rehearsal-before-the-first-paid-event) below before
> promising Mac support to a studio, then record the result in [Test results](#test-results).

Works on Apple Silicon (M1–M4) and Intel Macs, macOS 12 Monterey or newer.

```
Camera ──Wi-Fi (FTP)──> Mac: McreatiK Instant Memories Uploader ──internet──> McreatiK ──> guests' phones
```

## 1. Install Java 21 (once, ~5 minutes)

1. Go to **adoptium.net**, choose **Temurin 21 (LTS)**, operating system **macOS**, and download the **.pkg**.
   It detects Apple Silicon or Intel for you; if asked, pick **aarch64** for M1–M4 and **x64** for Intel.
2. Open the .pkg and click through the installer.
3. Check it: open **Terminal** (Cmd + Space, type `Terminal`, Enter) and run:
   ```
   java -version
   ```
   It should print `openjdk version "21…"`.

## 2. Put the uploader somewhere permanent

Move `mcreatik-uploader.jar` (sent by McreatiK) from Downloads into a folder of its own, for example
**Documents → McreatiK**. The commands below assume `~/Documents/McreatiK/mcreatik-uploader.jar`.

## 3. Start it (every event)

In Terminal:

```
caffeinate -dims java -jar ~/Documents/McreatiK/mcreatik-uploader.jar
```

- `caffeinate -dims` keeps the Mac awake for as long as the uploader runs. Without it the Mac can go to sleep
  mid-event and uploads stop. It stops on its own when you close the uploader.
- Start it from Terminal rather than double-clicking the jar. macOS may block a downloaded jar with
  "unidentified developer"; starting it with `java -jar` avoids that.
- Keep the Terminal window open (you can minimise it). Closing it closes the uploader.

The first time, the window asks for the **connection code** (copy it from the event dashboard: Overview →
Cameras & uploaders → Add uploader). Keep **"Receive photos from the camera over Wi-Fi"** ticked and click OK.
The default photo folder is **McreatiK Photos** in your home folder.

## 4. Allow the camera through the firewall

When macOS asks **"Do you want the application 'java' to accept incoming network connections?"**, click **Allow**.
Without this the camera can't send photos to the Mac.

If you clicked Deny by mistake, or the camera says "cannot connect":
**System Settings → Network → Firewall → Options**, find **java** and set it to **Allow incoming connections**.
(If the firewall is off, there is nothing to allow.)

## 5. Keep the Mac awake and online

- Keep it **plugged in** with the **lid open**. A closed lid sleeps the Mac even with `caffeinate`, unless an
  external display is attached.
- **System Settings → Battery → Options** (Intel: **Energy Saver**): turn on
  **"Prevent automatic sleeping on power adapter when the display is off"**.
- Turn off **Low Power Mode** during events.

## 6. One network for the camera and the Mac

- Use a **phone hotspot** (or venue Wi-Fi where devices can see each other) and join it from both the Mac and the camera.
  A Mac can't easily share its own Wi-Fi as a hotspot, so don't try to make the Mac the hotspot.
- **iPhone hotspot:** turn on **Maximize Compatibility** (Settings → Personal Hotspot) so the camera can join it.
- The uploader window shows the Mac's **server address** for the camera (iPhone hotspot usually `172.20.10.x`,
  Android `192.168.43.x` or `10.x.x.x`). To check it yourself: hold **Option** and click the Wi-Fi icon in the
  menu bar, or run `ipconfig getifaddr en0` in Terminal.

## 7. Set up the camera

Exactly as in the [Canon EOS R6 Mark II guide](CAMERA-SETUP-CANON-R6-MARK-II.md#before-the-event-once-per-camera-10-minutes):
FTP mode, the server address from the uploader window, port `2121`, user `mcreatik` and the password shown,
JPEG only, auto transfer on. Keep one camera connection SET per network (home Wi-Fi, event hotspot).

## Rehearsal before the first paid event

Do this once on the actual Mac, with the real camera, on a test event:

1. `java -version` shows 21.
2. The uploader starts, accepts the connection code and shows **Online** (green).
3. The firewall prompt appeared and you clicked Allow (or the firewall is off).
4. Camera connects: the window shows "Camera connected · N received".
5. 10 single frames arrive in the gallery within seconds.
6. 3 bursts of ~20 frames all arrive.
7. Turn off the hotspot's internet for a minute, shoot 5, turn it back on: the 5 upload by themselves.
8. Quit the uploader, shoot 5, start it again: nothing is missing.
9. Leave it running 30 minutes with the display off: the Mac didn't sleep and uploads kept working.
10. Note battery use per hour if you run unplugged.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `java: command not found` | Java isn't installed or Terminal was open during install: close Terminal, reopen it, run `java -version`. |
| "Unidentified developer" when opening the jar | Start it from Terminal with `java -jar …` (step 3) instead of double-clicking. |
| Camera: "cannot connect to FTP server" | Same network? Address typed exactly as the window shows? `java` allowed in the firewall (step 4)? |
| Photos stop after a while | The Mac slept: start with `caffeinate -dims` and keep the lid open and the charger in (step 5). |
| "Could not start on port 2121" | Another program uses it: start with `--ftp-port 2122` added after the jar name, and enter 2122 in the camera. |
| Photos reach the Mac but not the gallery | The window shows amber/offline: the hotspot has no internet. Photos wait and upload when it's back. |

## Test results

| Date | Mac (model, chip, macOS) | Camera | Network | Result |
|---|---|---|---|---|
| _not yet tested_ | | | | |
