# Canon EOS R6 Mark II → McreatiK Live Gallery (Wi-Fi FTP)

The camera sends every photo over Wi-Fi to the McreatiK Uploader on your laptop, which puts it in the live gallery.
No cable and no separate FTP software.

```
R6 Mark II ──Wi-Fi (FTP)──> laptop: McreatiK Uploader ──internet──> McreatiK ──> guests' phones
```

## Before the event (once per camera, ~10 minutes)

**1. Laptop**
- Start McreatiK Uploader, paste the connection code from the event dashboard, keep
  **"Receive photos from the camera over Wi-Fi"** ticked, and click OK.
- The window shows a **Camera Wi-Fi (FTP)** box with the **server address, port (2121), user name and password**.
  Keep it visible, you'll type these into the camera.
- Windows: when the firewall asks, click **Allow** (private networks).
- From the command line instead: `java -jar mcreatik-uploader.jar --ftp --connect MCK1.… --folder "D:\McreatiK Photos"`.

**2. One network for both**
- Turn on a **phone hotspot** (4G/5G) and connect the laptop to it. The camera will join the same hotspot.
  The hotspot also carries the upload to McreatiK. Venue Wi-Fi works too if both devices can join it,
  but guest Wi-Fi often blocks devices from talking to each other.

**3. Camera** (Canon's menu names can differ slightly by firmware)
- Network menu → **Network settings / Connection settings** → add a connection for **FTP transfer**.
- Join the same hotspot/Wi-Fi as the laptop.
- FTP server settings:
  - **Mode:** FTP (not FTPS/SFTP)
  - **Address:** the server address shown in the uploader (e.g. `192.168.43.20` or `172.20.10.3`)
  - **Port:** `2121`
  - **Passive mode:** on or off, both work (on is usually more reliable)
  - **Proxy:** none
  - **Login:** user `mcreatik`, the password shown in the uploader
  - **Target folder:** root folder (default)
- **FTP transfer settings:**
  - **Auto transfer** after shooting: **Enable**
  - **Type/size to transfer:** **JPEG** only (keep shooting RAW+JPEG; RAW stays on the card)
  - **Transfer with SET button:** optional
  - If the file already exists: skip or overwrite, both are fine (McreatiK ignores identical re-sends)
- **Power saving:** extend auto power off, or Wi-Fi drops when the camera sleeps.

**4. Test**
Shoot a few frames. Within a few seconds the uploader shows "Camera connected · N received", and the photos appear
in the live gallery.

## During the event
- Keep the laptop and hotspot within a few metres of where you shoot, or carry the phone hotspot on you.
- If the camera loses Wi-Fi, it retries photos that failed once reconnected. If the laptop loses internet,
  photos wait on the laptop and upload automatically. Either way, nothing is lost: every photo is still on the card.
- Wi-Fi transfer uses extra battery: carry spares.

## Field test: Canon EOS R6 Mark II → Windows laptop (home Wi-Fi router)

| Test | Result |
|---|---|
| 10 single frames | 10/10 arrived, 2–3 s from shutter to laptop |
| 3 bursts of ~20 frames | All arrived |
| Shot 10 frames 15–20 m away, walked back | Backlog arrived on its own, no camera action needed |
| Camera off/on, then shot 5 | Reconnected and sent without re-entering settings |
| Uploader closed, shot 5, uploader reopened | Photos arrived after reopening, nothing missing |
| Battery over ~15 min with Wi-Fi transfer | No visible drop on the camera's indicator |

Not yet tested: a full event length (3–6 h) for battery, a phone hotspot instead of a router, and the
laptop → live gallery upload over mobile data (needs the deployed backend).

## Test this before selling it
Run at least one rehearsal with the real camera before a paid event:
- 200+ frames in bursts, check they all reach the gallery.
- Walk away from the laptop and come back, check the backlog catches up.
- Note battery drain per hour with Wi-Fi on.
- Set Windows to never sleep when plugged in (Settings → System → Power & battery), and keep the laptop plugged in.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Camera: "cannot connect to FTP server" | Camera and laptop on the same network? Address typed exactly as shown? Laptop firewall allowed? |
| Camera shows "Busy" on the first connection | Usually clears by itself after the first connection or transfer. If not: allow "OpenJDK Platform binary" in the Windows firewall on Private **and** Public networks, check the laptop's address has not changed, and toggle Passive mode. |
| Connects, but transfers fail | Toggle **Passive mode** in the camera; check the port is 2121. |
| Uploader shows "could not start on port 2121" | Another program uses the port: start with `--ftp-port 2122` and enter 2122 in the camera. |
| Photos arrive on the laptop but not in the gallery | Check the uploader's online status: it is the internet link (hotspot data) that's down. |
