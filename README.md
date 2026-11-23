# HU Launcher

A light home screen and debloater for unbranded Android car head units.

## Why this exists
Cheap no-name Android head units ship with weak hardware (this one: MT8163, 2 GB RAM,
1024×600) and a lot of preinstalled software you never asked for. That includes OTA clients,
log uploaders, location services that report to third parties, and vendor "speed-up" tools that
kill your music app. On top of that comes a heavy stock launcher. HU Launcher was made to deal
with these units:
- **Keep resource use down:** a home screen that uses about 0% CPU when idle and around 20 MB
  of RAM, plus tools to disable bloat or keep it stopped.
- **Protect your privacy:** find and switch off the apps that phone home. A per-app firewall
  cuts the rest off the internet, with no root and no cloud service.

All of it runs on the unit or on your own PC on your own Wi-Fi. Nothing is sent anywhere else.

## Features
- **Home:** large clock and date, a large now-playing card (album art, blurred art backdrop,
  96–112 dp play/skip buttons), and a dock of up to 5 pinned apps. Tap the card to open
  Spotify's Now Playing. Swipe up anywhere to open All apps.
- **All apps:** horizontal pages of 5 × 2 very large icons. Swipe left and right to change page,
  and swipe down to close. Long-press an app to pin it, open App info, or uninstall it.
- **Debloat:** scans every package and sorts it into Bloat, Maybe, Keep or Disabled. It then
  goes through them one by one: it opens App info, you tap Disable, and the list moves on by itself.
- **Files:** a built-in file manager (All apps → Files) for browsing internal storage, USB and SD. You can rename files and folders (extension included), install APKs, read text files and extract zips. It runs in its own process, so it never slows down the home screen.
- **Let PC decide:** the unit runs a small server on your Wi-Fi with a PIN (`_hulauncher._tcp`,
  port 8765). A PC reads the full app list and sends back verdicts.
- **Setup screen:** on first start, one screen lists every permission (home app, notification access, accessibility, usage access, storage, install APKs). Each card opens that exact setting, where Android allows it, and shows a tick when it's on. You can open it again from Tools → Permissions & setup.
- **Remote control:** turn on Tools → PC link, then open `http://<unit-ip>:8765` in a PC browser. You see the screen live and can click, swipe, scroll, type and press Back/Home. Screen sharing asks once each time it starts (tick "Don't show again"). Touch needs the "HU Launcher remote" accessibility service, which you turn on once.
- **Helper (same accessibility service):** Debloat → Review → **Auto-disable all** presses Disable and confirms for each app. Activity can **force-stop** an app or **stop background apps** (only apps you installed, never the one playing music). Screen-share prompts are accepted automatically.
- **Network firewall:** a VPN-based allow-list (Tools → Network firewall). Allowed apps bypass it entirely, so they run at full speed. Every other app is cut off. It comes back on by itself after a reboot.
- **Activity:** live RAM, CPU clocks and temperature, plus each app's screen time and data used today (needs Usage access). Android 9 does not let apps see other apps' CPU or RAM.

Plain Java and framework Views only: no AndroidX, Compose or native code. The APK is about 130 KB.

## Download
Get `HULauncher.apk` from [Releases](../../releases), or build it yourself (below). Release APKs
are signed with the author's key. If you build your own, uninstall the release first: Android
won't update an app signed with a different key.
Only APIs up to 28 are used, because the unit claims Android 13 but its framework is really Pie.

## Build
```powershell
.\build.ps1
```
Gives `HULauncher.apk` (JDK 21 and an Android SDK are needed; no Gradle).
The first build creates your own throwaway signing key, `signing.keystore`, which is git-ignored.
Android only installs an update if it's signed with the same key, so keep that file if you plan to update later.

## Install on the unit
1. Send `HULauncher.apk` to the unit (Bluetooth or USB stick) and install it.
2. Open **HU Launcher**. When asked, tap **Choose home app**, pick HU Launcher, then **Always**.
3. Tap the music card and allow **Notification access** for HU Launcher. This shows the song
   and the art. Play, pause and skip work without it.
4. When asked, choose **Scan now** (later: All apps → Debloat).

## Let the PC decide
1. On the unit: All apps → Debloat → **Let PC decide**. Note the PIN.
2. On a PC on the same Wi-Fi:
   ```powershell
   .\pc\hu.ps1 pull -Pin 123456
   ```
   The script finds the unit by itself and saves `pc\apps.json`.
3. Write `pc\verdicts.json` (`{"pkg": {"v": "bloat|maybe|keep", "why": "..."}}`). Start from
   `pc\verdicts.example.json`, a real list for an MT8163 "hcn" unit. You can also have Claude
   write it from `apps.json`, then run:
   ```powershell
   .\pc\hu.ps1 push -Pin 123456
   ```
4. On the unit, open the Bloat tab and tap **Review … one by one**.

Order of priority: your own choices (long-press a row) beat PC verdicts, and PC verdicts beat the
built-in rules. The built-in rules are cautious. Anything that looks like car hardware (CAN bus,
MCU, radio, Bluetooth, cameras, steering keys, vendor core) and every other home app is kept.

## Limits
- Without root, the only way to remove an app is the system App info page (Disable or Uninstall).
  Some vendor and system apps show a greyed-out Disable button. Those go on the **keep stopped**
  list instead (automatically when Disable is greyed out, or Activity → app → Keep it stopped):
  - **When it checks:** at most once a minute, when the home screen comes up. A check costs well under 1 ms.
  - **If an app has started by itself:** it's killed silently in the background.
  - **Full Force stop:** happens with the helper on, after 10 s of no touches on the home screen. It also blocks the app's boot and alarm auto-starts, until something launches it again. App info shows for about 2 s.
  - **Apps that keep coming back:** each return after a force stop doubles the wait before the next one (10 min → 24 h). So it can never get stuck in a stop/restart loop, and it never interrupts another app you're using.
- Installing over the PC link needs a tap on **Install/Update** on Android 14 and newer, which hide
  the installer's buttons from accessibility services. On Android 9 to 13 the helper presses them for you.
- Android 9 lets apps change files only on internal storage. USB sticks and SD cards can be browsed and opened, but renaming or extracting on them fails with a clear message.
- Some Chinese units set their own launcher back as home after a reboot. If that happens, disable
  the stock launcher only once HU Launcher works well as home.

## PC script commands
```powershell
.\pc\hu.ps1 remote -Pin 123456            # start screen sharing, then open the printed address
.\pc\hu.ps1 shot -Pin 123456              # save a screenshot to pc\screen.jpg
.\pc\hu.ps1 tap 0.5 0.5 -Pin 123456       # x y as fractions of the screen
.\pc\hu.ps1 key back -Pin 123456          # back | home | recents | notifications
.\pc\hu.ps1 net -Pin 123456               # firewall state per app
.\pc\hu.ps1 block com.adups.fota -Pin 123456
.\pc\hu.ps1 stats -Pin 123456             # RAM / CPU / temperature + today's per-app usage
.\pc\hu.ps1 install HULauncher.apk -Pin 123456   # upload an APK over Wi-Fi and install it
.\pc\hu.ps1 install bt -Pin 123456        # install the newest APK the unit received over Bluetooth
.\pc\hu.ps1 keep -Pin 123456              # keep-stopped list   (keep add|remove <pkg...>)
```
Updating HU Launcher itself works the same way. The PC link comes back on its own with the same
PIN, so the script waits and prints the new version. The PIN only changes when you stop the link
or the unit reboots, and the link stays off after a reboot.
