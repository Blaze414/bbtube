# References and citations

Specifications, tools and documentation that bbtube is built on or that informed its design. Versions are the ones used in this project.

## Java ME specifications

1. **JSR 118: Mobile Information Device Profile (MIDP) 2.0.** Java Community Process. The MIDlet lifecycle, LCDUI (`Canvas`, `Command`, `List`, `Form`, `Alert`, `Display.callSerially`), `platformRequest`, RecordStore (RMS), OTA install and the JAD format. <https://jcp.org/en/jsr/detail?id=118>
2. **JSR 139: Connected Limited Device Configuration (CLDC) 1.1.** Java Community Process. The VM and class-library subset, the Generic Connection Framework (`Connector`, `HttpConnection`), and class-file preverification. <https://jcp.org/en/jsr/detail?id=139>
3. **JSR 135: Mobile Media API (MMAPI) 1.x.** Java Community Process. `Manager.createPlayer`, `Player` states (realize/prefetch/start), `VideoControl` (`USE_DIRECT_VIDEO`, `setDisplaySize`, `setDisplayLocation`), `setMediaTime`, and `PlayerListener` events including `bufferingStarted`/`bufferingStopped`. <https://jcp.org/en/jsr/detail?id=135>

## BlackBerry

4. **BlackBerry Java Development Environment / BlackBerry Java SDK documentation** (Research In Motion, 2010–2012; archived). The connection suffixes (`;interface=wifi`, `;deviceside=true`), MIDlet support on BlackBerry OS, application permissions, and OTA installation. Archived copies are available through the Internet Archive (<https://web.archive.org/>), searching for `docs.blackberry.com` BlackBerry Java SDK pages.
5. **blackberry/Eclipse-JDE**, GitHub. The open-sourced Eclipse plugin, which doesn't include `rapc` or `net_rim_api.jar`. <https://github.com/blackberry/Eclipse-JDE>
6. **Barry: open-source BlackBerry desktop tools** (Net Direct Inc.). `bjavaloader` (screenshots, app install and listing), `bidentify`, `breset`. Used here to capture screenshots over USB. <https://sourceforge.net/projects/barry/>

## Build toolchain

7. **Eclipse Compiler for Java (ECJ) 3.33.0.** Eclipse Foundation. Used for `-source 1.3 -target 1.1` output. Maven: `org.eclipse.jdt:ecj:3.33.0`. <https://projects.eclipse.org/projects/eclipse.jdt>
8. **MicroEmulator 2.0.4** API jars (`cldcapi11`, `midpapi20`, `microemu-jsr-135`). Bartek Teodorczyk et al., LGPL. Used as the compile-time boot classpath. Maven: `org.microemu:*:2.0.4`. <https://github.com/barteo/microemu>
9. **ProGuard 6.2.2.** Guardsquare. The `-microedition` option preverifies classes for CLDC. Maven: `net.sf.proguard:proguard-base:6.2.2`. <https://github.com/Guardsquare/proguard> · Manual: <https://www.guardsquare.com/manual/configuration/usage>
10. **Maven Central Repository**, the source of all toolchain jars. <https://repo1.maven.org/maven2/>

## Server side

11. **Invidious**, an open-source alternative front-end to YouTube. iv-org, AGPL‑3.0. Search, video metadata, storyboards, thumbnails and stream proxying (`/latest_version`). <https://github.com/iv-org/invidious> · API docs: <https://docs.invidious.io/api/> · Installation: <https://docs.invidious.io/installation/>
12. **FFmpeg 8.1.** Transcoding, the `scale` and `loudnorm` filters, the `-progress` output, JPEG re-encoding and WebP decoding. <https://ffmpeg.org>
    - ffmpeg command-line documentation (`-progress`, `-fpsmax`, `-movflags +faststart`): <https://ffmpeg.org/ffmpeg.html>
    - Filters (`loudnorm`, `scale`, `crop`): <https://ffmpeg.org/ffmpeg-filters.html>
    - Encoders (`aac`, `aac_at`): <https://ffmpeg.org/ffmpeg-codecs.html>
    - H.264 encoding guide (profiles, levels, compatibility): <https://trac.ffmpeg.org/wiki/Encode/H.264>
13. **x264 / libx264.** VideoLAN. The H.264 Baseline encoder. <https://www.videolan.org/developers/x264.html>
14. **ImageMagick 7.1.** Burns the length badges into thumbnails. <https://imagemagick.org> · Text labels: <https://imagemagick.org/Usage/text/>
15. **Python 3 standard library.** `http.server.ThreadingHTTPServer`, `urllib`, `subprocess`, `zipfile`. <https://docs.python.org/3/library/http.server.html>

## Standards

16. **ITU‑T H.264 / ISO/IEC 14496‑10: Advanced Video Coding.** The Baseline vs Main profiles and levels (Baseline 3.0 is what the Bold 9780 decodes). <https://www.itu.int/rec/T-REC-H.264>
17. **ITU‑R BS.1770: Algorithms to measure audio programme loudness and true-peak audio level.** The basis of LUFS and dBTP, as used by `loudnorm`. <https://www.itu.int/rec/R-REC-BS.1770>
18. **EBU R 128: Loudness normalisation and permitted maximum level of audio signals.** <https://tech.ebu.ch/publications/r128>
19. **RFC 9110: HTTP Semantics**, §14 Range Requests (206 Partial Content, `Content-Range`, 416). It supersedes RFC 7233. <https://www.rfc-editor.org/rfc/rfc9110#name-range-requests>
20. **ISO/IEC 14496‑12 / ‑14: ISO Base Media File Format / MP4.** The `moov` atom placement that `+faststart` changes.

## Hardware

21. **BlackBerry Bold 9780** (2010). 480×360 display, trackpad, QWERTY keyboard, BlackBerry OS 6.0, Wi‑Fi 802.11 b/g/n. The device every behaviour in this project was measured on.

---

*Observed behaviours documented in [CHALLENGES.md](CHALLENGES.md) were measured on that device and are not taken from the sources above. They include the trackpad key codes, letters mapping to game actions, Form buttons not firing, the `callSerially` requirement for `platformRequest`, and the AAC clipping. Where they differ from the specs, the device wins.*
