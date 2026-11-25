# How-to guide

- [1. Set up the Mac](#1-set-up-the-mac)
- [2. Install the app on the phone](#2-install-the-app-on-the-phone)
- [3. Use the app](#3-use-the-app)
- [4. Build the app](#4-build-the-app)
- [5. Release a new version](#5-release-a-new-version)
- [6. Debugging](#debugging)
- [7. Troubleshooting](#7-troubleshooting)
- [8. Common changes](#8-common-changes)

---

## 1. Set up the Mac

### Prerequisites

```bash
brew install python ffmpeg imagemagick openjdk
```

| Tool | Used for | Check |
|---|---|---|
| Python 3.8+ | `bbtube.py` (standard library only) | `python3 --version` |
| ffmpeg with `libx264` and `aac_at` | video conversion, JPEG re-encoding | `ffmpeg -encoders \| grep -E 'libx264\|aac_at'` |
| ImageMagick 7 | length badges on thumbnails (optional) | `magick -version` |
| Java 11+ | running ECJ and ProGuard for the build | `java -version` |
| Docker | running Invidious | `docker ps` |

`aac_at` is Apple's AudioToolbox encoder, so it exists only in macOS builds of ffmpeg. On other systems, change `-c:a aac_at` to `-c:a aac` in `convert()`. Expect slightly hotter peaks.

### Invidious

bbtube expects Invidious at `http://localhost:3000`. This project uses the official Docker image (`quay.io/invidious/invidious`) with its companion, in `~/invidious`:

```bash
cd ~/invidious && docker compose up -d
curl -s 'http://localhost:3000/api/v1/search?q=test' | head -c 200
```

See the [Invidious installation docs](https://docs.invidious.io/installation/). To point bbtube at a different instance, change `INVIDIOUS` at the top of `bbtube.py`.

### Start bbtube

```bash
cd ~/bbtube && (nohup python3 bbtube.py >> ~/bbtube/bbtube.log 2>&1 &)
```

Check it's running:

```bash
curl -s 'http://localhost:8090/api/search?q=test' | head -3
```

Restart after editing `bbtube.py`:

```bash
kill $(lsof -tiTCP:8090 -sTCP:LISTEN); cd ~/bbtube && (nohup python3 bbtube.py >> ~/bbtube/bbtube.log 2>&1 &)
```

Find the Mac's LAN address for the phone:

```bash
ipconfig getifaddr en0
```

> macOS may ask whether Python can accept incoming connections. Allow it, or the phone can't connect.

---

## 2. Install the app on the phone

1. Connect the BlackBerry to the **same Wi‑Fi** as the Mac.
2. Open the **Browser** and go to `http://<mac-ip>:8090/app/bbtube.jad`. The web version's home page also has an *Install the bbtube app* link.
3. On the download screen, tick **Set Application Permissions**. If you don't, OS 6 asks *"bbtube has attempted to access <mac-ip>…"* on first use. Choose **Download**.
4. If the phone says **"Device must be rebooted to complete install"**, choose **Reboot**. This happens when replacing a version that's still loaded.
5. Open **bbtube** from **Downloads** (or wherever OS 6 put it). Move it to the home screen with the menu's *Move* if you like.

The app reads the server address from its install descriptor, so there's nothing to configure. To change it later, open **Menu → Options**.

---

## 3. Use the app

| Where | Action | Keys |
|---|---|---|
| Home | Search | Just type, then **Enter** |
| Home | Fix a typo | **Delete** |
| Home | Repeat a recent search | Trackpad to it, then **click** |
| Home | Forget a recent search | Highlight it, then **Menu → Delete** |
| Home | Clear the typed text | **Back** |
| Results | Open a video | Trackpad, then **click** |
| Results | Load more | Scroll to the bottom, then **More Results** |
| Video | Play in the app | Click the big thumbnail, or **Menu → Play** |
| Video | Play in Media Player | **Play in Media Player** |
| Video | See frames | **Preview Frames** |
| Player | Pause / resume | Trackpad **click** or **Space** |
| Player | Back / forward 10 s | Trackpad **left / right** (press several times to jump further) |
| Anywhere | Go back | **Back** key (ESC) |
| Anywhere | Server settings | **Menu → Options** |

**In-app player or Media Player?**

| | In-app player | Media Player |
|---|---|---|
| Speed | Quick, stays in the app | Opens the browser, then the Media Player |
| Look | The app's own title bar and controls | Full screen with the system's controls |
| Seeking | Trackpad left/right | The Media Player's own seek bar |
| When the video isn't converted yet | Progress bar in the app | Progress page in the browser |
| Afterwards | Back returns to the video page | bbtube closes; reopen it afterwards |

---

## 4. Build the app

```bash
~/bbtube/build.sh
```

Output: `app/bbtube.jar`, preverified. The build also runs both test suites. A failing assert stops it.

What it does, step by step:

```bash
T=tools; LIB=$T/cldcapi11-2.0.4.jar:$T/midpapi20-2.0.4.jar:$T/microemu-jsr-135-2.0.4.jar
java -jar $T/ecj-3.33.0.jar -source 1.3 -target 1.1 -bootclasspath $LIB -d build/classes src/BBTube.java
cp icon.png res/*.png build/classes/
jar cfm build/raw.jar src/manifest.txt -C build/classes .
java -jar $T/proguard-base-6.2.2.jar -injars build/raw.jar -outjars app/bbtube.jar -libraryjars "$LIB" \
     -microedition -dontshrink -dontoptimize -dontobfuscate
```

### Getting the toolchain jars

`build.sh` downloads them from Maven Central into `tools/` on the first build. For reference:

| Jar | Maven coordinates |
|---|---|
| `ecj-3.33.0.jar` | `org.eclipse.jdt:ecj:3.33.0` |
| `cldcapi11-2.0.4.jar` | `org.microemu:cldcapi11:2.0.4` |
| `midpapi20-2.0.4.jar` | `org.microemu:midpapi20:2.0.4` |
| `microemu-jsr-135-2.0.4.jar` | `org.microemu:microemu-jsr-135:2.0.4` |
| `proguard-base-6.2.2.jar` | `net.sf.proguard:proguard-base:6.2.2` |

URL pattern: `https://repo1.maven.org/maven2/<group with / instead of .>/<artifact>/<version>/<artifact>-<version>.jar`

### Rules for the source

- **Java 1.3 syntax only:** no generics, enhanced `for`, autoboxing, `StringBuilder` or `String.format`.
- **Only CLDC 1.1 / MIDP 2.0 / JSR‑135 classes.** The compiler enforces this through the boot classpath.
- **Never block the UI thread:** network, player and `setMediaTime` calls run in `new Thread()`.
- **Anything that launches another app** (`platformRequest`) runs inside `display.callSerially`.

---

## 5. Release a new version

1. Edit `src/BBTube.java` (and `bbtube.py` if needed).
2. Bump `MIDlet-Version` in `src/manifest.txt`. The phone won't replace an app with the same version.
3. Run `./build.sh`. The JAD is generated from the new JAR automatically, with no server restart needed. Restart only when `bbtube.py` changed.
4. On the phone, **close the running bbtube first** (BlackBerry menu → *Close*, or hold the Menu key and close it in the app switcher). Otherwise the old instance keeps running.
5. Install from the JAD URL again and tick *Set Application Permissions*.
6. Add an entry to `CHANGELOG.md`.

---

## Debugging

### The phone's log

The app reports errors and every key press to the Mac:

```bash
tail -f ~/bbtube/bbtube.log | grep --line-buffered 'phone:'
```

```
phone: key 5 game action 5 (player)
phone: connected with [;interface=wifi] after: ...
phone: seek: javax.microedition.media.MediaException: ...
```

### Screenshots and installs over USB with Barry

[Barry](https://sourceforge.net/projects/barry/) is an open-source desktop sync suite for BlackBerry. In this setup it's a native arm64 build in `~/barry`.

```bash
~/barry/bin/bidentify                                   # is the phone connected? prints its PIN
~/barry/bin/bjavaloader screenshot shot.bmp             # capture the screen (480x360 BMP)
sips -s format png shot.bmp --out shot.png              # convert for viewing
~/barry/bin/bjavaloader dir | grep -i bbtube            # is the app installed?
```

Record every screen change while testing:

```bash
mkdir -p rec && cd rec && last=""
for i in $(seq -w 1 400); do
  ~/barry/bin/bjavaloader screenshot cur.bmp >/dev/null 2>&1 || { sleep 2; continue; }
  h=$(md5 -q cur.bmp)
  [ "$h" != "$last" ] && sips -s format png cur.bmp --out f$i.png >/dev/null && last=$h
  sleep 1
done
magick montage f*.png -tile 6x -geometry 240x180+2+2 sheet.png   # contact sheet
```

If Barry says **"No device selected, or PIN not found"**, unplug and replug the phone, or run `~/barry/sbin/breset`.

### Check the server side by hand

```bash
curl -s 'http://localhost:8090/api/video?v=<id>'
curl -s 'http://localhost:8090/api/play?v=<id>'                 # converting N / ready / failed
ffprobe -v error -show_entries stream=codec_name,profile,width,height ~/bbtube/cache/<id>.mp4
```

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| "Can't reach the Mac" | Phone not on Wi‑Fi, wrong address, Mac firewall, or the server isn't running | Check Wi‑Fi, `curl` the server from the Mac, check **Menu → Options**, allow Python in the macOS firewall |
| Permission prompt on every launch | App installed without *Set Application Permissions* | Reinstall with the box ticked, or **Options → Applications → bbtube → Edit Permissions** on the phone |
| "Video format not supported" but audio plays | The phone got H.264 Main | It should always get the converted `/v/ID.mp4`. Check `ffprobe` shows `profile=Constrained Baseline` |
| "This video couldn't be converted" | No itag 18 stream, or ffmpeg failed | `grep 'convert .* failed' ~/bbtube/bbtube.log`. Opening the video again retries |
| Crackling audio | Hot master clipping the 9780's AAC decoder | Already handled by `loudnorm` + `aac_at`. Delete the cached file to re-encode with current settings |
| Progress stays at 0% | Invidious didn't return `lengthSeconds` | Harmless: the video still converts, and playback starts when it's ready |
| Thumbnails have no length box | ImageMagick missing, or the font path differs | `brew install imagemagick`, or change `BADGE_FONT` in `bbtube.py` |
| New version doesn't appear | The old instance was still running, or the version wasn't bumped | Close bbtube, bump `MIDlet-Version`, reinstall; reboot if asked |
| Install fails: "Invalid JAR" or "size mismatch" | JAD and JAR out of sync | The JAD is generated live; make sure `app/bbtube.jar` finished building, then retry |
| Barry: "No device selected" | USB dropped | Replug the phone, or `~/barry/sbin/breset` |
| Search box ignores keys | Keys are going to another app or a dialog | Close any dialog. Typing only goes to the search box while Home is showing |

---

## 8. Common changes

| Change | Where |
|---|---|
| Skip length per trackpad press | `VideoScreen.SKIP` in `BBTube.java` |
| Colours | `HEAD_TOP`, `SEL_TOP`, `BLUE`, … constants at the top of `BBTube.java` |
| Number of recent searches | `runSearch()` in `BBTube.java` (8) |
| Number of cached videos | `KEEP` in `bbtube.py` |
| Video bitrate or size | `convert()` in `bbtube.py` |
| Concurrent encodes | `ENCODERS = threading.Semaphore(2)` |
| Number of preview frames | `FRAMES` in `bbtube.py` |
| Invidious address | `INVIDIOUS` in `bbtube.py` |
| Server port | `PORT` in `bbtube.py` (then reinstall the app, or change it in Options) |
