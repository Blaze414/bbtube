"""Self-check: python3 ~/bbtube/test_bbtube.py"""
from bbtube import JAR, LENGTH, byte_range, progress_pct, clip, duration, jad, own_host, pick_cues, tsv

vtt = "WEBVTT\n\n" + "\n\n".join(
    f"00:{i * 2 // 60:02d}:{i * 2 % 60:02d}.000 --> 00:00:00.000\n"
    f"/sb/i/ID/L2/M{i // 25}.jpg?sigh=a%24b#xywh={i % 5 * 160},{i // 5 % 5 * 90},158,90"
    for i in range(108))

cues = pick_cues(vtt)
assert len(cues) == 6, cues
assert cues[0] == ("0:18", "/sb/i/ID/L2/M0.jpg?sigh=a%24b", "640,90,158,90"), cues[0]  # cue 9
assert cues[-1][0] == "3:18" and cues[-1][1].endswith("M3.jpg?sigh=a%24b"), cues[-1]  # cue 99, fourth sheet
assert pick_cues("WEBVTT\n") == []
assert all(t < "1:40" for t, _, _ in pick_cues(vtt, length=100))  # padding cells past the end are dropped
assert len(pick_cues(vtt, length=100)) == 6
assert len(pick_cues(vtt.split("\n\n00:00:06")[0])) == 3  # fewer cues than frames: keep all

assert duration(214) == "3:34" and duration(3725) == "1:02:05" and duration(None) == "0:00"
assert clip("a" * 70) == "a" * 70 and clip("a" * 80) == "a" * 67 + "..."

assert byte_range(None, 1000) is None and byte_range("bytes=-", 1000) is None and byte_range("items=0-1", 1000) is None
assert byte_range("bytes=0-99", 1000) == (0, 99)
assert byte_range("bytes=500-", 1000) == (500, 999)
assert byte_range("bytes=900-5000", 1000) == (900, 999)  # clamped to the file
assert byte_range("bytes=-100", 1000) == (900, 999)  # suffix: last 100 bytes
s, e = byte_range("bytes=1000-", 1000)
assert s > e  # past the end: unsatisfiable (416)

assert tsv("id", "a\tb\nc", 3) == "id\ta b c\t3"  # the app splits on tabs/newlines
assert own_host("192.168.1.10:8090") == "192.168.1.10:8090"
assert own_host("evil\r\nX: y") == "localhost:8090" and own_host(None) == "localhost:8090"

if JAR.exists():  # after build.sh: descriptor must agree with the jar or the phone refuses to install
    d = dict(l.split(": ", 1) for l in jad("192.168.1.10:8090").splitlines())
    assert d["MIDlet-Jar-Size"] == str(JAR.stat().st_size)
    assert d["MIDlet-Jar-URL"] == "http://192.168.1.10:8090/app/bbtube.jar"
    assert d["MIDlet-Name"] == "bbtube" and d["MIDlet-1"] == "bbtube,/icon.png,BBTube"
    assert d["BBTube-Server"] == "192.168.1.10:8090"
assert progress_pct("out_time_us=30000000\n", 120) == 25
assert progress_pct("out_time_us=999000000", 120) == 99  # never shows 100% before the file is ready
assert progress_pct("out_time_us=N/A", 120) is None and progress_pct("out_time_us=-5", 120) is None
assert progress_pct("out_time_us=5000000", 0) is None and progress_pct("frame=12", 120) is None
assert all(LENGTH.fullmatch(t) for t in ("4:57", "23:30", "1:02:05", "live"))
assert not any(LENGTH.fullmatch(t) for t in ("@/etc/passwd", "4:5", "abc", "1:2:3:45"))  # label: must never read a file
print("server helpers ok")
