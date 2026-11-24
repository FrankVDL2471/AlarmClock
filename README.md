# Bedside Alarm Clock (Android, controlled over REST / Loxone)

This turns an old Android phone (Android 9 or newer) into a bedside clock:

- A black screen with large white digits (24h) and the date, in landscape and fullscreen. The screen stays on.
- **Burn-in protection**: the clock moves to a random spot on the screen every minute.
- A **REST API on port 8080**: one-time alarms, recurring alarms, playing MP3s (from a file on the phone or a URL), stopping, snoozing, volume, and dimming the screen.
- Alarms use the Android alarm clock API, so they still go off in Doze and after a reboot.

## Building (no local tools needed)

GitHub Actions builds the APK ([.github/workflows/build.yml](.github/workflows/build.yml)).

1. Create a repository on GitHub and push this folder to `main`:
   ```
   git remote add origin https://github.com/<you>/AlarmClock.git
   git push -u origin main
   ```
2. On GitHub, go to **Actions → Build APK** and wait for the green check.
3. Download the APK:
   - from the **Releases** page, release `latest` → `AlarmClock.apk` (every push to `main` updates it), or
   - from the workflow run → artifact `AlarmClock` (a zip).
   - In a *private* repo you have to be logged in to GitHub to download it. You can also download it on the PC and copy it to the phone over USB.
4. Push a tag such as `v1.0` to create a versioned release.

Every build is signed with the same key ([keystore/release.p12](keystore/release.p12), password `alarmclock`), so a new APK installs over the old one and keeps your alarms.

## Installing on the phone

1. Open the APK on the phone and allow "install unknown apps" for your browser or file manager.
2. Start **Alarm Clock** and accept what it asks for:
   - Notifications (Android 13 and newer)
   - Music/audio access (for the MP3 files)
   - Battery optimization: choose **Allow** / "don't optimize"
   - Display over other apps (Android 10 and newer). This lets the clock open by itself after a reboot.
   - Full-screen notifications (Android 14 and newer)
3. **Long-press the clock** to see the API address, for example `http://192.168.1.50:8080`. Open it in a browser to see the list of endpoints.
4. Recommended:
   - Give the phone a **fixed IP address** (a DHCP reservation in your router).
   - Keep it on the charger, and turn off the phone's own lock screen timeout and its battery saver.
   - Some brands (Xiaomi, Huawei, Samsung) have their own "auto-start" or "app sleep" settings. Exclude the app there too.

### MP3 files on the phone

Copy MP3 files over USB to **`Music/AlarmClock/`** on the phone's storage. The app creates this folder. `GET /api/files` lists what it finds. Refer to a file by its name, for example `file=wake.mp3`. You can leave out `.mp3`, and the name is not case-sensitive.

## REST API

The base URL is `http://<phone-ip>:8080`. Every endpoint works with **GET and query parameters**, which is the easiest way to use it from Loxone. `POST /api/alarms` with a JSON body works as well. Every response is JSON with `"ok": true`, or `"ok": false` and an `"error"`, with HTTP status 400 or 404.

### One-time alarm (for example, for tomorrow morning)

```
GET /api/alarm/once?time=07:30
GET /api/alarm/once?time=07:30&sound=wake.mp3&volume=70&label=Work
GET /api/alarm/once?time=06:45&date=2026-12-01
GET /api/alarm/once/cancel
```

- Without `date`, it rings at the **next** 07:30: today if that time is still ahead, otherwise tomorrow.
- It deletes itself after it rings.
- There is only one one-time alarm (id `once`). Each call replaces it, so Loxone can send it every evening.

### Recurring and named alarms

```
GET /api/alarms                                   list
GET /api/alarms/set?id=work&time=06:45&days=mon-fri&sound=wake.mp3&volume=60
GET /api/alarms/set?id=weekend&time=09:00&days=weekend
GET /api/alarms/enable?id=work&on=0               (0 = off, 1 = on)
GET /api/alarms/delete?id=work
GET /api/alarms/clear                             delete all
POST /api/alarms  {"id":"work","time":"06:45","days":["mon","tue"],"sound":"wake.mp3"}
```

| Parameter | Meaning |
|---|---|
| `id` | Name of the alarm (default `default`). Using an existing id updates that alarm. |
| `time` | `07:30`, `7.30` or `0730`. Text in front of the time is ignored: only the last word counts, so `Vandaag, 19:01` and `Tuesday, 01.12. 19:01` both mean 19:01. Alternatives: `hour=7&minute=30`, `sec=27000` (seconds since midnight) or `min=450` (minutes since midnight). These are handy with Loxone `<v>`. |
| `days` | `mon,tue,…`, `mon-fri`, `1-5` (1 = Monday), `weekdays`, `weekend`, `daily`, and Dutch day names `ma,di,wo,do,vr,za,zo`. **Without `days` it is a one-time alarm.** |
| `date` | `2026-12-01`, for a one-time alarm on a specific day |
| `sound` | A file name in `Music/AlarmClock`, an `http(s)://` URL to an MP3, or `default` (the phone's alarm tone). If the file can't be found or played, the alarm tone is used. |
| `volume` | 0–100, the alarm volume while it rings |
| `label` | Text shown on the screen while it rings |

A ringing alarm stops by itself after 15 minutes.

### Playback, stop, snooze

```
GET /api/play?file=doorbell.mp3
GET /api/play?url=http://192.168.1.10/sounds/news.mp3&volume=50
GET /api/play?file=rain.mp3&loop=1
GET /api/stop                      stops the ringing alarm AND any playback
GET /api/snooze?minutes=9          only while an alarm is ringing
```

### Status, volume, display

```
GET /api/status
GET /api/files
GET /api/volume?level=60&stream=alarm      (stream: alarm | media)
GET /api/display?dim=1&brightness=5        (brightness 0–100, -1 = system setting)
GET /api/display?dim=0&brightness=-1
```

`/api/status` returns, among other things, `"ringing": 0|1`, `"playing": 0|1`, `"nextAlarm"`, `"minutesToNextAlarm"`, `"volumeAlarm"` and `"dim"`. Numbers are used instead of true/false so that Loxone can read them easily.

## Loxone

**Virtual Output** (Loxone Config → Virtual Outputs → add one):

- Address: `http://192.168.1.50:8080` (the phone's IP)
- Add Virtual Output Commands (HTTP method GET), for example:

| Command | Command for ON |
|---|---|
| Wake tomorrow | `/api/alarm/once?sec=<v>` (connect an analog value in seconds since midnight) |
| Wake 07:00 | `/api/alarm/once?time=07:00` |
| Cancel alarm | `/api/alarm/once/cancel` |
| Stop | `/api/stop` |
| Doorbell | `/api/play?file=doorbell.mp3&volume=80` |
| Dim at night | `/api/display?dim=1&brightness=3` (and use *Command for OFF* `/api/display?dim=0&brightness=-1`) |

**Virtual HTTP Input** (to read the status):

- URL: `http://192.168.1.50:8080/api/status`, polling every 10 s
- Virtual HTTP Input Command, for example `Ringing`: command recognition `\i"ringing": \i\v`
- Others: `\i"playing": \i\v`, `\i"minutesToNextAlarm": \i\v`

## Project layout

| File | Purpose |
|---|---|
| [ClockActivity.kt](app/src/main/java/nl/home/alarmclock/ClockActivity.kt) | Fullscreen clock, burn-in drift, ringing screen, permission setup |
| [ClockService.kt](app/src/main/java/nl/home/alarmclock/ClockService.kt) | Foreground service: HTTP server, Wi-Fi lock and wake lock |
| [HttpServer.kt](app/src/main/java/nl/home/alarmclock/HttpServer.kt) | REST endpoints (NanoHTTPD) |
| [Controller.kt](app/src/main/java/nl/home/alarmclock/Controller.kt) | State: alarms, ringing, playback, display |
| [Model.kt](app/src/main/java/nl/home/alarmclock/Model.kt) | `Alarm`, next-trigger calculation, parameter parsing |
| [AlarmScheduler.kt](app/src/main/java/nl/home/alarmclock/AlarmScheduler.kt) | Registers the next alarm with `AlarmManager.setAlarmClock` |
| [SoundPlayer.kt](app/src/main/java/nl/home/alarmclock/SoundPlayer.kt) | ExoPlayer / alarm tone, volume |
| [Receivers.kt](app/src/main/java/nl/home/alarmclock/Receivers.kt) | App class, alarm receiver, boot receiver |
