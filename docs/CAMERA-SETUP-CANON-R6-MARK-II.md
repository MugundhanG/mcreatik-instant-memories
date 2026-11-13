# Canon EOS R6 Mark II → McreatiK Instant Memories (Wi-Fi FTP)

> The maintained, illustrated version of this guide is `backend/src/main/resources/docs/guide-canon.md` (in-app Docs: **Canon EOS R6 Mark II**, `/admin/docs/guide-canon`), next to the Sony α7 IV, uploader and event-day guides. This copy is a short text version.

The camera sends every photo over Wi-Fi to the McreatiK Instant Memories Uploader on your laptop, which puts it in the live gallery.
No cable and no separate FTP software.

```
R6 Mark II ──Wi-Fi (FTP)──> laptop: McreatiK Instant Memories Uploader ──internet──> McreatiK ──> guests' phones
```

## Before the event (once per camera, ~10 minutes)

**1. Laptop**
- Install and start McreatiK Instant Memories Uploader (Windows `.msi` or Mac `.dmg`, from the dashboard's **Install uploader** button; Java is included). Paste the connection code (starts with `MCK1.`) from the event dashboard (**Overview > Cameras & uploaders > Add camera & get code**), keep **"Receive photos from the camera over Wi-Fi"** ticked and press **Connect**. There is no studio sign-in.
- The **Camera Wi-Fi settings** card shows the **Server, Port (2121), User and Password**. Keep it visible, you'll type these into the camera.
- Windows: when the firewall asks, click **Allow** on Private **and** Public networks (a phone hotspot counts as Public).
- Mac: see [UPLOADER-MACOS.md](UPLOADER-MACOS.md) (Apple Silicon or Intel build, firewall, keeping the Mac awake).

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
Shoot a few frames. Within a few seconds the uploader shows "N received this session", the **Camera** stage turns green (**Receiving**), and the photos appear in the live gallery.

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

Also connected over an **Android phone hotspot** after re-entering the laptop's new address (see below).

Not yet tested: a full event length (3–6 h) for battery, and the laptop → live gallery upload over mobile data
(needs the deployed backend).

## Switching between home Wi-Fi and a phone hotspot
The laptop gets a **different address on each network** (home router e.g. `192.168.1.8`, Android hotspot usually
`192.168.43.x` or `10.x.x.x`, iPhone `172.20.10.x`). The camera stores the Wi-Fi network and the FTP address together,
so keep one camera connection **SET per network** (e.g. SET1 = home, SET2 = event hotspot) and pick the right one.

For the hotspot SET:
1. Connect the laptop to the hotspot, then read the **Server address** in the uploader (or `ipconfig` → IPv4 Address).
2. Run the "Transfer images to FTP server" wizard into an empty SET, choose the hotspot, enter that address,
   port `2121`, Passive mode on, same user and password.
3. Allow McreatiK Instant Memories Uploader in the Windows firewall on **Public** networks too: a new hotspot is Public.
4. Android: hotspot band 2.4 GHz. iPhone: turn on Maximize Compatibility.

Always use the **same phone** as the event hotspot: it normally hands the laptop the same address every time, so the
hotspot SET keeps working with no setup at the venue. If the camera shows "Busy" after a network change, the address
in the camera is the first thing to check.

## Test this before selling it
Run at least one rehearsal with the real camera before a paid event:
- 200+ frames in bursts, check they all reach the gallery.
- Walk away from the laptop and come back, check the backlog catches up.
- Note battery drain per hour with Wi-Fi on.
- Set Windows to never sleep when plugged in (Settings → System → Power & battery), and keep the laptop plugged in.

## Several cameras on one laptop
One uploader serves up to 4 cameras. Press **+ Add camera** in the uploader; each camera uses the **Port on its own tab** (2121, 2122, 2123, 2124), with the same Server, User and Password.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Camera: "cannot connect to FTP server" | Camera and laptop on the same network? Address typed exactly as shown? Port 2121? Laptop firewall allowed? |
| Camera shows "Busy" on the first connection | Usually clears by itself after the first connection or transfer. If not: allow McreatiK Instant Memories Uploader in the Windows firewall on Private **and** Public networks, check the laptop's address has not changed, and toggle Passive mode. |
| Connects, but transfers fail | Toggle **Passive mode** in the camera; check the port is 2121. |
| Uploader shows "could not start on port 2121" | Another program uses the port: start the uploader with `--ftp-port 2131` and enter 2131 in the camera. |
| Photos arrive on the laptop but not in the gallery | Check the uploader's online status: it is the internet link (hotspot data) that's down. |
