#!/usr/bin/env python3
"""bbtube: YouTube for BlackBerry OS, on top of the local Invidious.

Serves the native app (app/bbtube.jar, a MIDP 2.0 MIDlet built by build.sh) and its plain-text API,
plus a no-JavaScript web version of the same thing.

Run:    python3 ~/bbtube/bbtube.py
Phone:  browse to http://<mac-lan-ip>:8090/app/bbtube.jad to install the app.
Needs ffmpeg with libx264: the Bold 9780 can't decode YouTube's H.264 Main profile, so videos are converted
to Baseline (cached in cache/), and storyboard sheets are WebP, so preview frames are re-encoded to JPEG.
"""
import html
import json
import re
import subprocess
import tempfile
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

INVIDIOUS = "http://localhost:3000"
PORT = 8090
FRAMES = 6
HERE = Path(__file__).parent
JAR = HERE / "app" / "bbtube.jar"
CACHE = HERE / "cache"
KEEP = 10  # converted videos kept on disk (about 5 MB per minute of video)
VIDEO_ID = re.compile(r"[\w-]{11}")
THUMB = re.compile(r"/vi/[\w-]{11}/(default|mqdefault)\.jpg")
STREAM = re.compile(r"/v/([\w-]{11})\.mp4")
ICON = (HERE / "icon.png").read_bytes()
esc = html.escape

# BlackBerry OS 6/7 look: dark title bar, list rows, blue focus bar for trackpad navigation.
# Solid colour first, then -webkit-gradient, so OS 5's browser still gets a usable flat version.
CSS = """
body{margin:0;background:#fff;color:#000;font-family:"BBAlpha Sans",sans-serif;font-size:medium}
a{color:inherit;text-decoration:none}
.bar{background:#1e1e1e;background:-webkit-gradient(linear,left top,left bottom,from(#4a4a4a),to(#111));color:#fff;padding:5px 6px;border-bottom:1px solid #000}
.bar b{font-size:large}
.bar form{margin:5px 0 1px}
.q{width:66%;padding:3px;border:1px solid #000}
.row{display:block;padding:5px 6px;border-bottom:1px solid #d8d8d8;overflow:hidden}
.row:hover,.row:focus,.play:hover .btn,.play:focus .btn{background:#1a5bbf;background:-webkit-gradient(linear,left top,left bottom,from(#5aa0f0),to(#1a5bbf));color:#fff}
.row:hover .m,.row:focus .m{color:#dde8ff}
.th{float:left;position:relative;width:120px;height:68px;overflow:hidden;margin-right:6px;background:#000}
.th img{display:block;margin-top:-11px}
.d{position:absolute;right:2px;bottom:2px;background:#000;color:#fff;font-size:x-small;padding:0 3px}
.m{color:#666;font-size:small}
.t{padding:5px 6px}
.play{display:block;padding:0 6px 6px}
.hero{display:block;position:relative;width:320px;height:180px;margin:0 auto;background:#000}
.btn{display:block;margin-top:6px;padding:9px;text-align:center;font-weight:bold;color:#fff;background:#333;background:-webkit-gradient(linear,left top,left bottom,from(#5a5a5a),to(#222))}
.off{background:#999}
.hd{background:#e6e6e6;color:#444;font-size:small;font-weight:bold;padding:3px 6px;border-top:1px solid #ccc;border-bottom:1px solid #ccc}
.strip{padding:4px 0;text-align:center}
.f{display:inline-block;margin:2px;font-size:x-small;color:#666}
.msg{padding:12px 6px;color:#444}
"""


def api(path):
    with urllib.request.urlopen(INVIDIOUS + path, timeout=15) as r:
        return r.read()


def own_host(host_header):
    """host:port the phone used to reach bbtube, for absolute URLs in the app descriptor."""
    return host_header if re.fullmatch(r"[\w.-]+(:\d+)?", host_header or "") else f"localhost:{PORT}"


def duration(s):
    h, rest = divmod(int(s or 0), 3600)
    return "%d:%02d:%02d" % (h, *divmod(rest, 60)) if h else "%d:%02d" % divmod(rest, 60)


def pick_cues(vtt, length=0, n=FRAMES):
    """n evenly spaced (time_label, sheet_path, 'x,y,w,h') from an Invidious storyboard VTT."""
    cues = [(int(h) * 3600 + int(m) * 60 + int(s), p, c) for h, m, s, p, c in re.findall(
        r"^(\d+):(\d+):(\d+)\.\d+ -->[^\n]*\n(/sb/[^#\s]+)#xywh=(\d+,\d+,\d+,\d+)", vtt, re.M)]
    if length:  # Invidious pads the last sheet with empty (black) cells past the end of the video
        cues = [cue for cue in cues if cue[0] < length]
    if len(cues) > n:
        cues = [cues[(2 * i + 1) * len(cues) // (2 * n)] for i in range(n)]
    return [(duration(t), p, c) for t, p, c in cues]


def frame_url(path, crop):
    return "/frame?" + urllib.parse.urlencode({"p": path, "c": crop})


def video_data(vid):
    """(info, has_360p_mp4, preview cues) for one video."""
    info = json.loads(api(f"/api/v1/videos/{vid}?fields=title,author,lengthSeconds,formatStreams"))
    has360 = any(f.get("itag") == "18" for f in info.get("formatStreams", []))
    try:
        cues = pick_cues(api(f"/api/v1/storyboards/{vid}?width=160&height=90").decode(),
                         int(info.get("lengthSeconds") or 0))
    except Exception:  # many videos have no storyboard: just skip the preview
        cues = []
    if has360:
        conversion(vid)  # start converting now, so it's (nearly) ready by the time Play is pressed
    return info, has360, cues


def search_videos(q, pg):
    params = urllib.parse.urlencode({"q": q, "type": "video", "page": pg, "fields": "videoId,title,author,lengthSeconds"})
    return [v for v in json.loads(api("/api/v1/search?" + params)) if VIDEO_ID.fullmatch(v.get("videoId", ""))]


JOBS = {}  # video id -> thread converting it
JOBS_LOCK = threading.Lock()
ENCODERS = threading.Semaphore(2)  # opening several videos in a row shouldn't start several encodes at once
PROGRESS = {}  # video id -> percent converted, for the app's progress bar


def progress_pct(line, length):
    """Percent done from one line of ffmpeg's -progress output, or None for other lines (and 'N/A' times)."""
    key, _, value = line.strip().partition("=")
    if key != "out_time_us" or not length or not value.isdigit():
        return None
    return min(99, int(value) // (length * 10_000))


def convert(vid):
    """YouTube's 360p MP4 -> H.264 Baseline the 9780 can decode, sized to its 480x360 screen.
    Audio: AAC-LC 44.1 kHz stereo 128k, levelled to -16 LUFS with a -3 dBTP ceiling. Loud masters peak at
    0 dBFS and crackle on the 9780's decoder. Apple's AAC encoder (aac_at) keeps the decoded peak near -2 dB;
    ffmpeg's built-in one overshoots to about 0 dB with the same settings."""
    out, part = CACHE / f"{vid}.mp4", CACHE / f"{vid}.part"
    try:
        length = int(json.loads(api(f"/api/v1/videos/{vid}?fields=lengthSeconds")).get("lengthSeconds") or 0)
    except Exception:
        length = 0  # the progress bar just stays at 0%
    with ENCODERS, tempfile.TemporaryFile() as err:  # stderr to a file: a full pipe would stall ffmpeg
        CACHE.mkdir(exist_ok=True)
        proc = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-progress", "pipe:1", "-nostats", "-y", "-i", f"{INVIDIOUS}/latest_version?id={vid}&itag=18&local=true",
             "-vf", "scale=480:360:force_original_aspect_ratio=decrease:force_divisible_by=2", "-fpsmax", "30",
             "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p", "-preset", "veryfast",
             "-b:v", "600k", "-maxrate", "700k", "-bufsize", "1400k",
             "-af", "loudnorm=I=-16:TP=-3:LRA=11,aresample=44100", "-c:a", "aac_at", "-b:a", "128k", "-ac", "2",
             "-movflags", "+faststart", "-f", "mp4", str(part)],
            stdout=subprocess.PIPE, stderr=err, text=True)
        watchdog = threading.Timer(3600, proc.kill)
        watchdog.start()
        for line in proc.stdout:
            pct = progress_pct(line, length)
            if pct is not None:
                PROGRESS[vid] = pct
        code = proc.wait()
        watchdog.cancel()
        err.seek(0)
        error = err.read().decode(errors="replace")[-300:]
    PROGRESS.pop(vid, None)
    if code:
        part.unlink(missing_ok=True)
        print(f"convert {vid} failed: {error}", file=sys.stderr, flush=True)
        return
    part.rename(out)
    for old in sorted(CACHE.glob("*.mp4"), key=lambda p: p.stat().st_mtime)[:-KEEP]:
        old.unlink()


def conversion(vid):
    """'ready', 'converting' or 'failed'. Starts the conversion if it isn't done or running; a failed one retries next call."""
    if (CACHE / f"{vid}.mp4").exists():
        return "ready"
    with JOBS_LOCK:
        job = JOBS.get(vid)
        if job and job.is_alive():
            return "converting"
        if job:  # finished without producing the file
            del JOBS[vid]
            return "failed"
        JOBS[vid] = threading.Thread(target=convert, args=(vid,), daemon=True)
        JOBS[vid].start()
    return "converting"


def byte_range(header, size):
    """Inclusive (start, end) of a single 'bytes=a-b' Range header, or None for the whole file. start > end = unsatisfiable."""
    m = re.fullmatch(r"bytes=(\d*)-(\d*)", (header or "").strip())
    if not m or not any(m.groups()):
        return None
    a, b = m.groups()
    if not a:  # suffix form: the last b bytes
        return max(0, size - int(b)), size - 1
    return int(a), min(int(b), size - 1) if b else size - 1


def tsv(*fields):
    """One line of the app's plain-text API: the phone splits on tabs and newlines, so strip them from values."""
    return "\t".join(re.sub(r"[\t\r\n]+", " ", str(f)) for f in fields)


def jad(host):
    """App descriptor. Copies the MIDlet-* lines from the jar's own manifest, since the phone rejects any mismatch."""
    with zipfile.ZipFile(JAR) as z:
        manifest = z.read("META-INF/MANIFEST.MF").decode()
    lines = [l for l in manifest.splitlines() if l.startswith(("MIDlet-", "MicroEdition-"))]
    lines += [f"MIDlet-Jar-URL: http://{host}/app/bbtube.jar", f"MIDlet-Jar-Size: {JAR.stat().st_size}",
              f"BBTube-Server: {host}"]
    return "\n".join(lines) + "\n"


def to_jpeg(data, vf):
    return subprocess.run(
        ["ffmpeg", "-v", "error", "-i", "pipe:0", "-vf", vf, "-frames:v", "1",
         "-q:v", "4", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1"],
        input=data, capture_output=True, timeout=15, check=True).stdout


LENGTH = re.compile(r"(\d{1,2}:)?\d{1,2}:\d\d|live")
BADGE_FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"


def burn_length(jpeg, text, height):
    """The app's video length in a corner box, burned into the JPEG: phone fonts are too big for a 68-pixel thumbnail.
    Needs ImageMagick; without it the thumbnail just has no box."""
    if not text or not LENGTH.fullmatch(text):
        return jpeg
    live = text == "live"
    size = min(14, max(10, height // 6))  # 11 points on a 68-pixel-high thumbnail
    try:
        return subprocess.run(
            ["magick", "jpeg:-", "(", "-background", "#cc0000" if live else "#000000c0", "-fill", "white",
             "-font", BADGE_FONT, "-pointsize", str(size), f"label: {'LIVE' if live else text} ", ")",
             "-gravity", "SouthEast", "-geometry", "+3+3", "-composite", "-quality", "88", "jpeg:-"],
            input=jpeg, capture_output=True, timeout=15, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return jpeg


def page(title, body, q=None, head=""):
    """q=None hides the search box, so on a video page the trackpad lands on Play first."""
    search = "" if q is None else (f'<form action="/search"><input class="q" name="q" value="{esc(q)}"> '
                                   f'<input type="submit" value="Search"></form>')
    return f"""<!DOCTYPE html>
<html><head><meta http-equiv="Content-Type" content="text/html; charset=utf-8">{head}
<meta name="viewport" content="width=device-width, initial-scale=1.0"><meta name="HandheldFriendly" content="true">
<link rel="icon" href="/icon.png"><link rel="apple-touch-icon" href="/icon.png">
<title>{esc(title)}</title><style>{CSS}</style></head>
<body><div class="bar"><b>bbtube</b>{search}</div>
{body}</body></html>""".encode()


def clip(text, n=70):
    return text if len(text) <= n else text[:n - 3].rstrip() + "..."


def badge(seconds):
    return f'<span class="d">{duration(seconds)}</span>' if seconds else ""  # 0 = live/upcoming


class Handler(BaseHTTPRequestHandler):
    def send(self, code, body, ctype="text/html; charset=utf-8", cache=False):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if cache:  # images: lets Back redraw pages without refetching
            self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        self.wfile.write(body)

    def text(self, lines):
        self.send(200, "".join(l + "\n" for l in lines).encode(), "text/plain; charset=utf-8")

    def bad(self, msg):
        self.send(400, page("Bad request", f'<p class="msg">{esc(msg)}</p>', ""))

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        qs = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        routes = {"/": self.home, "/search": self.search, "/watch": self.watch, "/frame": self.frame,
                  "/icon.png": self.icon, "/api/search": self.api_search, "/api/video": self.api_video,
                  "/api/thumb": self.api_thumb, "/api/play": self.api_play, "/play": self.play, "/log": self.log,
                  "/app/bbtube.jad": self.app_jad, "/app/bbtube.jar": self.app_jar}
        if THUMB.fullmatch(url.path):
            route = self.thumb
        elif STREAM.fullmatch(url.path):
            route = self.stream
        else:
            route = routes.get(url.path)
        if not route:
            return self.send(404, page("Not found", '<p class="msg">Not found.</p>', ""))
        try:
            route(qs)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the phone hung up (player stopped or seeked)
        except Exception as e:  # Invidious down/slow, ffmpeg failure: say so instead of dropping the connection
            self.send(502, page("Error", '<p class="msg">Couldn\'t load this from Invidious. Check the Mac is on, '
                                f'then try again.<br><span class="m">{esc(str(e))}</span></p>', ""))

    # --- native app: install files and plain-text API ---

    def app_jad(self, qs):
        self.send(200, jad(own_host(self.headers.get("Host"))).encode(), "text/vnd.sun.j2me.app-descriptor")

    def app_jar(self, qs):
        self.send(200, JAR.read_bytes(), "application/java-archive")

    def api_search(self, qs):
        try:
            pg = max(1, min(int(qs.get("page", 1)), 50))
        except ValueError:
            pg = 1
        q = qs.get("q", "").strip()
        self.text([tsv(v["videoId"], duration(v.get("lengthSeconds")) if v.get("lengthSeconds") else "live",
                       v.get("title", ""), v.get("author", "")) for v in search_videos(q, pg)] if q else [])

    def api_video(self, qs):
        """Lines: title, author, duration, 1/0 for 360p available, then one 'time<TAB>frame path' per preview frame."""
        vid = qs.get("v", "")
        if not VIDEO_ID.fullmatch(vid):
            return self.bad("Bad video id.")
        info, has360, cues = video_data(vid)
        self.text([tsv(info.get("title", "")), tsv(info.get("author", "")), duration(info.get("lengthSeconds")),
                   "1" if has360 else "0"] + [tsv(t, frame_url(p, c)) for t, p, c in cues])

    def api_thumb(self, qs):
        """16:9 thumbnail, 80 (default), 120, 160 or 192 pixels wide; t=4:57 or t=live adds the length box."""
        vid, size = qs.get("v", ""), qs.get("size", "80")
        if not VIDEO_ID.fullmatch(vid) or size not in ("80", "120", "160", "192"):
            return self.bad("Bad thumbnail.")
        height = int(size) * 9 // 16
        if size == "80":  # letterbox bars cropped off the 120x90 default.jpg
            jpeg = to_jpeg(api(f"/vi/{vid}/default.jpg"), "crop=120:68:0:11,scale=80:45")
        else:
            jpeg = to_jpeg(api(f"/vi/{vid}/mqdefault.jpg"), f"scale={size}:{height}")
        self.send(200, burn_length(jpeg, qs.get("t"), height), "image/jpeg", cache=True)

    def log(self, qs):
        """The app reports on-device errors here, so they show up in bbtube.log."""
        print("phone:", re.sub(r"[\r\n]+", " ", qs.get("m", ""))[:500], file=sys.stderr, flush=True)
        self.text([])

    def api_play(self, qs):
        """'ready', 'converting <percent>' or 'failed': the app polls this before opening the player, so the player never waits."""
        vid = qs.get("v", "")
        if not VIDEO_ID.fullmatch(vid):
            return self.bad("Bad video id.")
        state = conversion(vid)
        self.text([f"converting {PROGRESS.get(vid, 0)}" if state == "converting" else state])

    def stream(self, qs):
        """The converted MP4 at a short URL with no redirects, with Range support so the phone's player can seek."""
        vid = STREAM.fullmatch(urllib.parse.urlsplit(self.path).path).group(1)
        while (state := conversion(vid)) == "converting":  # fallback for players that come straight here
            time.sleep(1)
        if state == "failed":
            return self.send(502, page("Error", '<p class="msg">This video couldn\'t be converted.</p>', ""))
        path = CACHE / f"{vid}.mp4"
        size = path.stat().st_size
        r = byte_range(self.headers.get("Range"), size)
        if r and r[0] > r[1]:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            return self.end_headers()
        start, end = r or (0, size - 1)
        self.send_response(206 if r else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if r:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with path.open("rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0 and (chunk := f.read(min(65536, left))):
                self.wfile.write(chunk)
                left -= len(chunk)

    # --- web version ---

    def home(self, qs):
        self.send(200, page("bbtube", '<p class="msg">Search YouTube.</p>'
                            '<a class="row" href="/app/bbtube.jad"><b>Install the bbtube app</b></a>', ""))

    def icon(self, qs):
        self.send(200, ICON, "image/png", cache=True)

    def thumb(self, qs):
        # same origin as the pages, so the phone only ever needs one host:port for everything but video
        self.send(200, api(urllib.parse.urlsplit(self.path).path), "image/jpeg", cache=True)

    def search(self, qs):
        q = qs.get("q", "").strip()
        if not q:
            return self.home(qs)
        try:
            pg = max(1, min(int(qs.get("page", 1)), 50))
        except ValueError:
            pg = 1
        rows = "".join(
            f'<a class="row" href="/watch?v={v["videoId"]}"><span class="th">'
            f'<img src="/vi/{v["videoId"]}/default.jpg" width="120" height="90" alt="">{badge(v.get("lengthSeconds"))}'
            f'</span>{esc(clip(v.get("title", "")))}<br><span class="m">{esc(v.get("author", ""))}</span></a>'
            for v in search_videos(q, pg))
        more = f'<a class="row" href="/search?{esc(urllib.parse.urlencode({"q": q, "page": pg + 1}))}"><b>More results</b></a>'
        self.send(200, page(q, rows + more if rows else '<p class="msg">No results.</p>', q))

    def watch(self, qs):
        vid = qs.get("v", "")
        if not VIDEO_ID.fullmatch(vid):
            return self.bad("Bad video id.")
        info, has360, cues = video_data(vid)
        hero = (f'<span class="hero"><img src="/vi/{vid}/mqdefault.jpg" width="320" height="180" alt="">'
                f'{badge(info.get("lengthSeconds"))}</span>')
        if has360:
            # thumbnail and button are one link: one trackpad stop, and the whole area is tappable on touch Bolds
            player = f'<a class="play" href="/play?v={vid}">{hero}<span class="btn">Play video</span></a>'
        else:
            player = f'<div class="play">{hero}<span class="btn off">Not available at 360p</span></div>'
        # 150x85 so three fit across the 480px Bold screens (the cue itself is 158x90)
        strip = "".join(f'<span class="f"><img src="{esc(frame_url(p, c))}" width="150" height="85" alt=""><br>{t}</span>'
                        for t, p, c in cues)
        preview = f'<div class="hd">Preview</div><div class="strip">{strip}</div>' if cues else ""
        self.send(200, page(info.get("title", vid), f'<div class="t"><b>{esc(info.get("title", ""))}</b><br>'
                  f'<span class="m">{esc(info.get("author", ""))}</span></div>{player}{preview}'))

    def play(self, qs):
        """Waits out the conversion on a self-refreshing page (no JavaScript), then hands the MP4 to the Media Player."""
        vid = qs.get("v", "")
        if not VIDEO_ID.fullmatch(vid):
            return self.bad("Bad video id.")
        state = conversion(vid)
        if state == "ready":
            self.send_response(302)
            self.send_header("Location", f"http://{own_host(self.headers.get('Host'))}/v/{vid}.mp4")
            self.send_header("Content-Length", "0")
            return self.end_headers()
        if state == "failed":
            return self.send(200, page("Error", f'<p class="msg">This video couldn\'t be converted.</p>'
                                       f'<a class="row" href="/play?v={vid}"><b>Try again</b></a>', ""))
        self.send(200, page("Preparing video", f'<p class="msg">Preparing video for your BlackBerry... {PROGRESS.get(vid, 0)}%<br>'
                            '<span class="m">This page refreshes by itself. Long videos take a minute or two.</span></p>',
                            head='<meta http-equiv="refresh" content="3">'))

    def frame(self, qs):
        path = qs.get("p", "")
        try:
            x, y, w, h = map(int, qs.get("c", "").split(","))
        except ValueError:
            return self.bad("Bad crop.")
        # only Invidious storyboard sheets, so this can't be used as a general proxy on the LAN
        if not path.startswith("/sb/") or not (0 < w <= 640 and 0 < h <= 360 and x >= 0 and y >= 0):
            return self.bad("Bad frame.")
        fit = qs.get("h", "")  # the app asks for a fixed height so its grid lines up
        fit = int(fit) if fit.isdigit() and 30 <= int(fit) <= 180 else 0
        vf = f"crop={w}:{h}:{x}:{y}" + (f",scale=-2:{fit}" if fit else "")
        self.send(200, burn_length(to_jpeg(api(path), vf), qs.get("t"), fit or h), "image/jpeg", cache=True)


if __name__ == "__main__":
    print(f"bbtube on http://0.0.0.0:{PORT}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
