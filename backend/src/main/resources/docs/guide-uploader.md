# McreatiK Uploader

The **McreatiK Uploader** runs on a laptop at the venue. It receives each photo from the camera over Wi-Fi (or from a folder), makes a light, sharp copy, and uploads it to the live gallery. If the internet drops, photos wait safely on the laptop and upload by themselves later.

![How a photo reaches every guest](images/flow-photo-journey.svg)

| | Windows | Mac |
| --- | --- | --- |
| Status | Field-tested with a Canon EOS R6 Mark II | **Not yet field-tested**: rehearse first (section 7) |
| Install | `McreatiK-Uploader-Windows.msi` | `McreatiK-Uploader-Mac-AppleSilicon.dmg` (M1–M4) or `…-Mac-Intel.dmg` |
| Needs | Nothing else: Java is included | Nothing else: Java is included; macOS 12+ |

One laptop per camera is simplest. Two cameras on one laptop is possible (section 6).

## 1. Download and install (once)

Download from the event dashboard (**Overview → Cameras & uploaders → Download McreatiK Uploader**), or use these links:

| Laptop | Download |
| --- | --- |
| Windows | [McreatiK-Uploader-Windows.msi](https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Uploader-Windows.msi) |
| Mac with Apple chip (M1–M4) | [McreatiK-Uploader-Mac-AppleSilicon.dmg](https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Uploader-Mac-AppleSilicon.dmg) |
| Older Mac (Intel) | [McreatiK-Uploader-Mac-Intel.dmg](https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Uploader-Mac-Intel.dmg) |

Not sure which Mac you have? Apple menu → **About This Mac**: "Chip Apple M…" means Apple chip; "Processor Intel" means Intel.

**Windows**
1. Open the `.msi` file. Windows may show "Windows protected your PC" (the app isn't code-signed yet): click **More info → Run anyway**.
2. Click through the installer. **McreatiK Uploader** appears in the Start menu and on the desktop.

**Mac**
1. Open the `.dmg` file and drag **McreatiK Uploader** into **Applications**.
2. Open it from Applications. The first time, macOS says it can't check the app (not code-signed yet): click **Done**, then go to **System Settings → Privacy & Security**, scroll down to McreatiK Uploader and click **Open Anyway**.
3. The app keeps the Mac awake by itself while it runs.

## 2. Start and connect to the event

Start **McreatiK Uploader** from the Start menu (Windows) or Applications (Mac).

The first time, the **Setup** window opens:

![Uploader setup window](images/uploader-setup-empty.webp)

1. **Connection code:** paste the code from the event dashboard (Overview → Cameras & uploaders → Add uploader → Copy code).
2. **Camera folder:** where photos are saved on this laptop. The default "McreatiK Photos" in your home folder is fine.
3. Keep **"Receive photos from the camera over Wi-Fi"** ticked for Canon or Sony FTP. Untick it only if your camera software already saves into a folder.
4. Press **OK**.

![Setup with the connection code pasted](images/uploader-setup-filled.webp)

Next time it remembers the event and goes straight to the main window.

## 3. The main window

![Uploader waiting for the camera](images/uploader-main-waiting.webp)

| Part | Meaning |
| --- | --- |
| Event name, "This computer: …" | Which event and which camera this laptop serves |
| Green **Online — photos upload automatically** | Connected to McreatiK |
| **Camera Wi-Fi (FTP)** box | What to type into the camera: **Server** (this laptop's address), **Port 2121**, **User mcreatik**, **Password** |
| Waiting · Uploading · Uploaded · Duplicates · Failed | The queue counts |
| Photo list | Each photo's status and time |
| Open folder · Retry failed · Open live gallery | Quick actions |

Now set up the camera: [Canon EOS R6 Mark II](/admin/docs/guide-canon) · [Sony α7 IV](/admin/docs/guide-sony).

When the camera connects, the FTP box turns green: **Camera connected · N received**.

![Photos arriving from the camera and uploading](images/uploader-main-uploading.webp)

![All photos uploaded](images/uploader-main-uploaded.webp)

## 4. When the internet drops

The status turns amber: **Offline — photos are safely queued**. The camera can keep sending, and photos wait in the queue on the laptop.

![Offline: photos wait in the queue](images/uploader-main-offline.webp)

When the connection returns, they upload by themselves, oldest first, and guests' phones catch up. There's nothing to press.

![Back online: the queue empties by itself](images/uploader-main-recovered.webp)

**Duplicates** counts photos the camera sent twice. They're skipped, which is normal. **Failed** photos (rare) retry with **Retry failed**.

## 5. Keep the laptop ready

**Windows**
- When the firewall asks, click **Allow** for **McreatiK Uploader** on **Private and Public** networks. A new phone hotspot counts as Public.
- Settings → System → Power & battery: **never sleep when plugged in**. Keep it plugged in.

**Mac**
- When macOS asks whether **McreatiK Uploader** may accept incoming network connections, click **Allow**. If you clicked Deny, go to System Settings → Network → Firewall → Options and allow it.
- System Settings → Battery → Options: **prevent automatic sleeping on power adapter when the display is off**. Keep the lid open; a closed lid sleeps the Mac.

**Both**
- The camera and the laptop must be on the **same Wi-Fi or phone hotspot**. iPhone hotspot: turn on **Maximize Compatibility**. Android hotspot: use 2.4 GHz.
- The laptop's address changes with each network (iPhone hotspot usually `172.20.10.x`, Android `192.168.43.x`). Always use the **Server** shown in the window.

## 6. Two cameras on one laptop

Start a second copy with its own data folder and FTP port, and its own connection code. This needs the command-line version (see "Advanced" below):
```
java -jar mcreatik-uploader.jar --data-dir ~/.mcreatik-camera2 --ftp-port 2122
```
Enter **2122** as the port in the second camera.

Other options: `--headless` (no window, for advanced use), and `--upload-originals` (send full-size files for server-side processing instead of the light copy).

## 7. Rehearsal before the first paid event

Do this once per laptop, with the real camera, on a test event:

1. The app installs and opens (first-open steps above).
2. The uploader shows **Online** (green).
3. The firewall prompt was allowed.
4. The camera connects: "Camera connected · N received".
5. 10 single frames appear in the gallery within seconds.
6. 3 bursts of about 20 frames all arrive.
7. Turn the hotspot's internet off for a minute, shoot 5, then turn it back on: the 5 upload by themselves.
8. Quit the uploader, shoot 5, start it again: nothing is missing.
9. 30 minutes with the display off: the laptop didn't sleep.
10. Note battery use per hour.

| Date | Laptop (model, OS) | Camera | Network | Result |
| --- | --- | --- | --- | --- |
| Oct 2026 | Windows laptop | Canon EOS R6 Mark II | Home Wi-Fi, Android hotspot | Passed: 2–3 s shutter to laptop, bursts and reconnects OK |
| _to do_ | Mac | | | |

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "Windows protected your PC" / Mac "can't be opened" | The app isn't code-signed yet: Windows → More info → Run anyway; Mac → System Settings → Privacy & Security → Open Anyway. |
| Setup says the code is invalid | Copy the whole code again from the dashboard (it starts with `MCK1.`). Lost it? **New code** on the dashboard. |
| Amber "Offline" | No internet on this laptop: check the hotspot's data. Photos are safe in the queue. |
| Camera: "cannot connect to FTP server" | Same Wi-Fi? Server address typed exactly as shown? Port 2121? Firewall allowed? |
| "Could not start on port 2121" | Another program uses it: add `--ftp-port 2122` and enter 2122 in the camera. |
| Photos stop after a while | The laptop slept (keep it plugged in and the lid open; the Mac app keeps itself awake), or the camera slept (extend its auto power off). |
| Dashboard shows the camera Offline | The uploader on that laptop is closed or has no internet. |

More: [Event-day checklist and troubleshooting](/admin/docs/guide-event-day).

## Advanced: the command-line version

For two cameras on one laptop, or Linux: install **Java 21** (adoptium.net → Temurin 21), then run `mcreatik-uploader.jar` (sent by McreatiK). On a Mac, start it with `caffeinate -dims java -jar mcreatik-uploader.jar` so the Mac stays awake. Options: `--data-dir`, `--ftp-port`, `--headless` (no window), `--upload-originals`.
