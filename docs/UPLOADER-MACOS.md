# McreatiK Instant Memories Uploader on a Mac

The Mac app is a normal installer. It includes Java and keeps the Mac awake by itself while it runs. Works on Apple Silicon (M1-M4) and Intel Macs, macOS 12 Monterey or newer. The full illustrated guide (Windows and Mac, several cameras, switching events, rehearsal) is `backend/src/main/resources/docs/guide-uploader.md`, also in the app's Docs page `/admin/docs/guide-uploader`. Current version: 1.0.22.

```
Camera --Wi-Fi (FTP)--> Mac: McreatiK Instant Memories Uploader --internet--> McreatiK --> guests' phones
```

## 1. Download and install (once)

In the dashboard press **Install uploader** in the top bar, or download from the latest release:

| Mac | File |
|---|---|
| Apple chip (M1-M4) | `McreatiK-Instant-Memories-Uploader-Mac-AppleSilicon.dmg` |
| Older Mac (Intel) | `McreatiK-Instant-Memories-Uploader-Mac-Intel.dmg` |

Not sure which? Apple menu > **About This Mac**: "Chip Apple M..." is Apple chip, "Processor Intel" is Intel.

1. Open the `.dmg` and drag **McreatiK Instant Memories Uploader** into **Applications**.
2. Open it from Applications. The first time macOS says it can't check the app (not code-signed yet): click **Done**, then **System Settings > Privacy & Security**, scroll to the app and click **Open Anyway**.

## 2. Connect to the event

1. In the event dashboard: **Overview > Cameras & uploaders > Add camera & get code > Copy code** (for a camera you already added, **Get new code**). The code starts with `MCK1.`.
2. In the uploader's **Setup** window press **Paste**. There is no studio sign-in; the connection code is all it asks for.
3. Keep **"Receive photos from the camera over Wi-Fi"** ticked (untick only if your camera software saves into a folder). The default photo folder is **McreatiK Photos** in your home folder.
4. Press **Connect**. Next time it remembers the event. To change event: wait until the queue is empty, then **Change event...** and paste the new code.

## 3. Allow the camera through the firewall

When macOS asks whether **McreatiK Instant Memories Uploader** may accept incoming network connections, click **Allow**. If you clicked Deny: **System Settings > Network > Firewall > Options** and allow it.

## 4. Keep the Mac ready

- Plugged in, lid open (a closed lid sleeps the Mac).
- **System Settings > Battery > Options:** prevent automatic sleeping on power adapter when the display is off. Turn off Low Power Mode.

## 5. One network for the camera and the Mac

Use a phone hotspot (or venue Wi-Fi where devices can see each other) and join it from both the Mac and the camera. A Mac can't easily act as the hotspot. iPhone hotspot: turn on **Maximize Compatibility**; Android: use 2.4 GHz. The window's **Camera Wi-Fi settings** card shows the **Server** address, **Port 2121**, **User mcreatik** and the **Password** to type into the camera.

## 6. Set up the camera

Follow [CAMERA-SETUP-CANON-R6-MARK-II.md](CAMERA-SETUP-CANON-R6-MARK-II.md) (or the Sony guide in the in-app Docs): FTP mode, the Server from the uploader, port 2121 (each extra camera uses the port on its own tab: 2122, 2123, 2124), user `mcreatik`, JPEG only, auto transfer on.

## Rehearsal before the first paid event

Once per Mac and camera model, on a test event: the app opens and shows **Online**; the camera connects ("N received"); 10 single frames and 3 bursts of about 20 arrive; switch the hotspot's internet off for a minute, shoot 5, switch on and they upload by themselves; quit the uploader, shoot 5, reopen and nothing is missing; 30 minutes with the display off and the Mac stays awake.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Can't be opened" / unidentified developer | System Settings > Privacy & Security > **Open Anyway**. |
| Setup says the code is invalid | Copy the whole code again (starts with `MCK1.`) or press **Get new code** on the dashboard. |
| Camera: "cannot connect to FTP server" | Same network? Server typed exactly as shown? Port correct? App allowed in the firewall? |
| Photos stop after a while | The Mac or the camera slept: keep it plugged in with the lid open, extend the camera's auto power off. |
| "Could not start on port 2121" | Another program uses it: close it, or run the app with `--ftp-port 2131` and enter 2131 in the camera. |
| Photos reach the Mac but not the gallery | Amber **Offline**: the hotspot has no internet. Photos wait and upload when it returns. |

More: the event-day checklist (`guide-event-day.md`).
