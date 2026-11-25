# Changelog

Versions are the `MIDlet-Version` in `src/manifest.txt`. Server changes are listed with the app version they shipped alongside.

## 1.3.4
- **Player:** the video no longer appears full size for a moment at start. It stays hidden until `prefetch()` reports its real size, then it's placed and shown.

## 1.3.3
- **Player:** the Delete key no longer skips forward. The 9780 reports Delete as game action RIGHT, so the player now ignores it along with letters.

## 1.3.2
- **Player:** trackpad **left/right seeks −/+10 s**. Repeated presses add up into one jump, the knob moves right away, and `setMediaTime` runs off the UI thread.
- **Player:** a seek after the video ends continues from that point instead of restarting.
- **Player:** the title bar is repainted every second, which fixes it staying blank when the video first appears.
- Letter keys no longer trigger player actions.

## 1.3.1
- **Player redesigned to match the app:**
  - The app's title bar, with *Buffering…*, *Paused* or *Finished* status.
  - Video placed between the title bar and a Media Player‑style control bar: a glossy blue play/pause button, elapsed time, a track with a knob, and time remaining.
  - An eight-dot spinner while connecting.
  - A "Preparing video for BlackBerry" progress bar while the Mac converts.
- Only the black margins, header and bar are painted, never over the video, so there's no flicker.

## 1.3.0
- **Search typed straight into Home's search box.** The system `TextBox` is gone. There's a blinking caret, Delete and Enter work, and recent searches filter as you type. Back clears the text.
- Recent searches can be deleted from the menu.

## 1.2.1
- Fixed the trackpad not scrolling. The key filter was swallowing the trackpad's key codes 1/6/2/5. It now ignores only printable keys 32–126.

## 1.2.0
- **Native OS 6 redesign.** Every screen is drawn on a Canvas:
  - the dark gradient title bar with icon;
  - white rows and grey section bands;
  - the blue gradient selection bar and a scroll bar.
- Results with 120×67 thumbnails, length badges, titles and channels. A "hero" video page with a 192×108 thumbnail and play overlay. Icons for each action.
- **Server:** length badges burned into thumbnails and frames with ImageMagick; new thumbnail sizes 120, 160 and 192; frame height parameter.

## 1.1.0
- **In-app player** waits for the conversion and shows its progress.
- **Server:** conversion progress from ffmpeg `-progress`, `converting N` from `/api/play`, a percentage on the web `/play` page, and a 1-hour watchdog.
- **Server:** audio levelled with `loudnorm` (−16 LUFS, −3 dBTP) and encoded with `aac_at`, which fixed crackling on loud songs.

## 1.0.5
- Actions moved to an IMPLICIT `List` (Play / Play in Media Player / Preview Frames), because Form buttons never fire on OS 6.
- Enter in the search field runs the search.
- *Play in Media Player* fixed: `platformRequest` now runs inside `Display.callSerially`.

## 1.0.x
- First native MIDlet: search, results, video page, playback.
- Connection transports tried in order; `;interface=wifi` confirmed on the 9780.
- **Server:** H.264 Baseline conversion (the 9780 can't decode YouTube's Main profile), a converted-video cache, Range support, the generated JAD, and the `/log` channel.
