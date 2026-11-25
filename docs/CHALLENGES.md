# Challenges and lessons learned

bbtube targets a 2010 phone with 2010 Java. Most of the work went into finding out what the BlackBerry *actually* does, which is often not what the specs say. Each entry below covers the symptom, the cause, the fix, and the lesson.

---

## 1. No open-source BlackBerry SDK

**Problem:** the request was for a native app built with an open-source SDK. RIM's `rapc` compiler and `net_rim_api.jar` are proprietary. The official GitHub repo [blackberry/Eclipse-JDE](https://github.com/blackberry/Eclipse-JDE) holds only Eclipse plugin source, not the compiler or API.

**Solution:** write a standard **MIDP 2.0 MIDlet** instead of a RIM `UiApplication`. BlackBerry OS runs MIDlets natively and installs them over the air from a JAD. Everything needed comes from Maven Central:
- ECJ (still able to emit Java 1.1 class files);
- MicroEmulator's API jars as the boot classpath;
- ProGuard's `-microedition` preverifier.

**Lesson:** look for the *standard* the platform implements before reaching for the vendor's SDK. MIDP gave up RIM‑only UI classes, and Canvas drawing won all of that back.

---

## 2. Modern `javac` can't build for the phone

**Problem:** CLDC 1.1 needs Java 1.1 class files that are **preverified** (with StackMap attributes). Current JDKs no longer support `-target 1.1`, and Sun's `preverify` binary isn't available for Apple Silicon.

**Solution:** ECJ with `-source 1.3 -target 1.1`, then ProGuard with `-microedition -dontshrink -dontoptimize -dontobfuscate`.

**Lesson:** compiling against the phone's API jars (not the JDK) as the boot classpath catches every desktop‑only class at build time instead of on the phone.

---

## 3. H.264 Main plays as audio only

**Symptom:** the phone said **"video format not supported"** but played the sound.

**Cause:** YouTube's 360p MP4 (itag 18) is H.264 **Main** profile. The 9780's decoder only handles **Baseline**.

**Fix:** convert every video on the Mac to H.264 Baseline 3.0 at up to 480×360, cache it, and stream that. Conversion starts as soon as a video page is opened, and the app shows its progress.

**Lesson:** "MP4" says nothing about decodability. Check the codec **profile and level** against the device. `ffprobe -show_entries stream=profile` would have caught it in a second.

---

## 4. Crackling audio on loud songs

**Symptom:** some music videos crackled through the phone's speaker and headphones.

**Cause:** modern masters peak at 0 dBFS. After AAC encoding, peaks overshoot, and the 9780's decoder clips them.

**Fix:**
- `loudnorm=I=-16:TP=-3` levels to −16 LUFS with a −3 dB true-peak ceiling.
- Use Apple's AAC encoder (`aac_at`). With the same settings ffmpeg's native encoder still overshot to about 0 dBFS after decoding; `aac_at` stayed near −2 dB.
- Confirmed by ear on the device.

**Lesson:** leave real headroom for weak decoders. Measure the **decoded** peak, not just the encoder's settings.

---

## 5. Which network transport?

**Problem:** BlackBerry needs a connection suffix on every URL. Without BIS (no carrier data plan), most suffixes fail, often silently.

**Finding:** on this phone `;interface=wifi` works. `;deviceside=true;interface=wifi` made **no request at all**.

**Fix:** try a short list of suffixes, remember the one that worked, and log the failures to the Mac. When a transport that used to work fails, report it instead of trying others, which only hides the real error.

**Lesson:** log from the device to a server you control. Without that, failed connections on the phone look identical.

---

## 6. Form buttons don't fire on BlackBerry OS 6

**Symptom:** the on-screen **Search** and **Play** buttons did nothing. The same actions worked from the BlackBerry menu.

**Cause:** on OS 6, LCDUI `StringItem` buttons and item commands never deliver `commandAction`. Only **menu commands** and **IMPLICIT `List` selections** arrive.

**Fix:**
- 1.0.5: switched to `List`s.
- 1.2.0: replaced the stock UI entirely with hand-drawn `Canvas` screens, where every key press is ours to handle.

**Lesson:** on old platforms, test the input path on the device before building screens on top of it.

---

## 7. "UI engine accessed without holding the event lock"

**Symptom:** *Play in Media Player* showed `IllegalStateException: UI engine accessed without holding the event lock`.

**Cause:** BlackBerry runs menu `commandAction`s outside its UI event lock, and `platformRequest()` needs that lock.

**Fix:** call `platformRequest` inside `Display.callSerially(Runnable)`. Note that on BlackBerry it returns `false` and runs in parallel, instead of requiring the MIDlet to exit as MIDP describes.

**Lesson:** "runs on the UI thread" means different things on different platforms. `callSerially` is the portable way to get onto the UI thread.

---

## 8. Exceptions vanish

**Problem:** an exception thrown in `commandAction` was simply dropped. No dialog, nothing.

**Fix:** wrap every handler in `try/catch (Throwable)`. Show a short, human `Alert` ("Can't reach the Mac") and send the full technical text to `/log`.

**Lesson:** build the diagnostics channel first. Every later bug on this project was diagnosed from `phone:` lines in `bbtube.log`.

---

## 9. "It looks too plain" means stock LCDUI won't do

**Problem:** stock `List`/`Form` screens rendered as bare text with system defaults, nothing like BlackBerry's own apps.

**Approach:** a critical review against BlackBerry's own OS 6 apps, then a full redraw on `Canvas`:
- the OS 6 dark gradient title bar with icon;
- white rows and grey section bands;
- the blue gradient selection bar and a scroll bar;
- thumbnails with length badges;
- the "hero" video page;
- icons for each action.

**Sub-problem: fonts are huge.** On a Canvas `Font.SIZE_SMALL` bold is about 22 px, too big for a badge on a 67 px thumbnail. **Fix:** burn the badges into the JPEGs on the Mac with ImageMagick (Arial Bold, 10–14 pt).

**Lesson:** when the device can't do something well (small text, WebP), move it to the server. Pixels are cheap there.

---

## 10. The trackpad stopped scrolling (1.2.0)

**Symptom:** after the Canvas redesign, the trackpad couldn't scroll down to *Play in Media Player*.

**Cause:** to stop letters from triggering game actions, keys `0 < key < 127` were ignored. But the 9780 trackpad sends **key codes 1 (up), 6 (down), 2 (left) and 5 (right)**. Those are positive, so the filter swallowed them.

**Fix (1.2.1):** only ignore the printable range 32–126.

**How it was found:** `logKey()` sends every key code and game action to the Mac. The full map:

| Input | Key code | Game action |
|---|---|---|
| Trackpad up / down / left / right | 1 / 6 / 2 / 5 | UP / DOWN / LEFT / RIGHT |
| Trackpad click | −8 | FIRE |
| Enter | 10 | none |
| Space | 32 | FIRE |
| Delete | 8 | **RIGHT** |
| 'd' / 's' / 'c' | 100 / 115 / 99 | **LEFT / RIGHT / DOWN** |

**Lesson:** never guess key codes. Log them from the device. The 9780 maps some letters *and Delete* to game actions, which bit this project twice (1.2.0, then 1.3.2 → 1.3.3).

---

## 11. A search screen that matches

**Problem:** the search screen was the system `TextBox`, the one screen that didn't match the rest.

**Fix (1.3.0):** type directly into a search box drawn on the home screen:
- key codes become characters;
- Delete removes one;
- Enter searches;
- a caret blinks on the animation thread;
- recent searches filter as you type.

**Detail:** the caret repaints only the box's rectangle. Repainting the whole screen eight times a second would have been visible on the 9780.

---

## 12. Video on a Canvas without flicker

**Problem:** with `USE_DIRECT_VIDEO`, the video is an overlay the Canvas can't paint over. Repainting the whole Canvas once a second for the time display made the video flicker.

**Fix (1.3.1):**
- `paint()` fills only the black margins around the video rectangle, the header and the control bar.
- The once-a-second update repaints only the header and bar regions.
- `place()` fits the video between them with its aspect ratio kept. A 16:9 video at 480 px wide exactly fills the gap between a 45 px header and a 45 px bar.
- If the sizing calls throw, it falls back to full screen.

**Follow-ups found in the screenshots:**
- **Header blank at start** (fixed in 1.3.2): the video layer blanked the header when it first appeared. The once-a-second update now repaints the header too.
- **Video full-size for a second** (fixed in 1.3.4): the source size isn't known until **prefetch**. The fix is to hide the video, `prefetch()`, size it, then show it.

---

## 13. Seeking a progressive download

**Problem:** add forward and back on the trackpad.

**Concerns and fixes:**
- **Seeking past the buffered part:** the server already sends `Accept-Ranges` and answers ranged requests with 206, so the player can jump ahead. The log showed 206 responses during seeks.
- **`setMediaTime()` can block:** it runs on a background thread, never the UI thread.
- **Rapid presses:** they add up into one target, and one thread keeps seeking until the target stops changing.
- **Knob jumping back mid-seek:** `tick()` doesn't overwrite the displayed time while a seek is pending.
- **Seeking after the end:** this clears the "ended" flag, so Play continues from the new point.

---

## 14. Getting the progress bar right

**Problem:** the first conversion of a long video took long enough that the phone's player timed out.

**Fix:**
- The app polls `/api/play` until `ready` and only then opens the player.
- ffmpeg's `-progress pipe:1` gives `out_time_us`, which becomes a percentage of the known length.
- It's capped at 99 until the file is actually renamed into place.
- stderr goes to a temp file, because an unread pipe would eventually stall ffmpeg.
- A watchdog kills an encode after an hour.

---

## 15. Installing and updating

| Gotcha | Fix |
|---|---|
| JAD must match the JAR's manifest and size exactly | Generate the JAD from the JAR on every request |
| The app needs to know its server | The JAD carries `BBTube-Server: host:port`, read with `getAppProperty` |
| Unsigned app: permission prompt on every connection | Tick *Set Application Permissions* at install |
| New version doesn't take effect | Close the running app first; reboot when the installer asks |
| Same version number won't replace | Always bump `MIDlet-Version` |

---

## 16. Testing on a device you can't automate

There's no emulator for this phone in the loop, so every change was checked on the real device:
- **screenshots over USB** with Barry's `bjavaloader screenshot`, recorded in a loop that saves a frame each time the screen changes;
- the **phone's own log** of key presses and errors;
- the server's **access log**, which shows what the player actually requested (for example the 206 responses during seeks).

Pure helpers (`urlEncode`, `split`, `seconds`, `clock`, `mix`, `byte_range`, `progress_pct`, `pick_cues`, …) are unit-tested on the desktop in every build.

**Lesson:** when the only real test is a human holding a phone, make the phone tell you everything (key codes, errors, transports), and make every check that *can* run on the desktop run there automatically.

---

## Summary of lessons

1. Prefer the platform's **standard** (MIDP) over the vendor SDK when the SDK is closed.
2. **Log from the device** to a server you control. Build that channel first.
3. **Never guess key codes or event behaviour.** Measure them on the hardware.
4. **Move hard things to the server:** transcoding, WebP, small fonts, loudness.
5. Check codec **profiles and levels**, not just containers.
6. Leave **audio headroom** for weak decoders, and measure after decoding.
7. Keep the UI thread free. Use **`callSerially`** for anything that touches other apps.
8. **Repaint only what changed**, especially next to live video.
9. Generate derived files (the JAD) **from the source of truth** (the JAR) so they can't drift.
10. Look at **real screenshots** after every change. Two of the player bugs were only visible there.
