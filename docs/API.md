# HTTP API

Everything is served by `bbtube.py` on port **8090**. The phone app only uses the endpoints under **App**. All of them are `GET`.

**Text format:** responses to `/api/*` are UTF‑8 plain text. Each record is one line, and the fields within it are separated by **tabs**. The server strips tabs and newlines from values (`tsv()`), so a title can never break the format. On bad input the server answers 400 with a short message. If Invidious fails, it answers 502.

---

## App

### `GET /app/bbtube.jad`
The app descriptor, generated per request. It holds the JAR manifest's `MIDlet-*`/`MicroEdition-*` lines, plus:
```
MIDlet-Jar-URL: http://<host the phone used>/app/bbtube.jar
MIDlet-Jar-Size: <bytes>
BBTube-Server: <host:port>
```
Content type: `text/vnd.sun.j2me.app-descriptor`.

### `GET /app/bbtube.jar`
The MIDlet (`application/java-archive`).

### `GET /api/search?q=<query>&page=<1..50>`
One line per video:
```
<videoId> \t <length "4:57" | "1:02:05" | "live"> \t <title> \t <channel>
```
An empty `q` gives an empty body.

### `GET /api/video?v=<id>`
```
line 1: title
line 2: channel
line 3: length ("4:57")
line 4: 1 if a 360p MP4 (itag 18) exists, else 0
line 5+: <time "1:23"> \t <frame path>      (0–6 preview frames)
```
Side effect: starts converting the video if it has a 360p stream, so it's usually ready by the time the user presses Play.

### `GET /api/thumb?v=<id>&size=<80|120|160|192>&t=<length|live>`
A 16:9 JPEG `size` pixels wide. With `t`, the length box (or a red LIVE box) is burned into the bottom-right corner. The response is cacheable.

### `GET /frame?p=/sb/...&c=<x,y,w,h>&h=<30..180>&t=<time>`
One preview frame cropped from an Invidious storyboard sprite sheet, scaled to height `h`, with the time burned in. The app gets the `p` and `c` values from `/api/video`. Only `/sb/` paths and crops up to 640×360 are accepted.

### `GET /api/play?v=<id>`
Conversion state. Starts the conversion if it isn't done or running:
```
ready | converting <0..99> | failed
```
After `failed`, the next call retries.

### `GET /v/<id>.mp4`
The converted MP4: H.264 Baseline 3.0 at up to 480×360, AAC‑LC 128k, with faststart.
- **Range requests** are supported (`Accept-Ranges: bytes`, 206 + `Content-Range`, 416 when out of range).
- If the conversion isn't finished, the request waits for it.

### `GET /log?m=<message>`
The phone's diagnostics channel. The server prints `phone: <message>` (at most 500 characters, newlines removed) to its stderr, which is `bbtube.log`. The response body is empty.

---

## Web version

| Path | Page |
|---|---|
| `/` | Search box |
| `/search?q=&page=` | Results with thumbnails |
| `/watch?v=` | Video page: thumbnail, play buttons, preview frames |
| `/play?v=` | *Preparing video… N%* (refreshes every 3 s), then a redirect to `/v/<id>.mp4` |
| `/vi/<id>/default.jpg`, `/vi/<id>/mqdefault.jpg` | Thumbnail passthrough from Invidious |
| `/icon.png` | App icon |

---

## Example

```bash
curl 'http://localhost:8090/api/search?q=jadoes'
curl 'http://localhost:8090/api/video?v=RZKqmhW2NVc'
curl 'http://localhost:8090/api/play?v=RZKqmhW2NVc'
curl -r 0-99 -o /dev/null -D - 'http://localhost:8090/v/RZKqmhW2NVc.mp4'
```
