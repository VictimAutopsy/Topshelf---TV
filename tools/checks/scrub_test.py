#!/usr/bin/env python3
"""Reports never carry keys or logins: Diag.scrub (and NativePlayer.safe it uses) against example lines.
The two methods are lifted out of the source into a plain Java program (they use nothing of Android's)."""
import pathlib, subprocess, sys, tempfile
root = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/java/com/topshelf/launcher"
d = (root / "Diag.java").read_text(); n = (root / "NativePlayer.java").read_text()
scrub = d[d.index("    private static final java.util.regex.Pattern URLS"):d.index("    static void ev(String what)")]
a = n.index("    static String safe(String url)"); b = n.index("\n    }\n", a) + 7
safe = n[a:b]
cases = [
    ("https://torrentio.strem.fun/realdebrid=ABCDEFGHIJKLMNOPQRSTUVWXYZ123456/stream/movie/tt1.json", ["ABCDEFGHIJKLMNOPQRSTUVWXYZ123456"]),
    ("Source error http://iptv.example.com:8080/live/someuser/secretpass/12345.ts", ["someuser", "secretpass"]),
    ("http://iptv.example.com/get.php?username=bob&password=hunter2&type=m3u", ["hunter2", "username=bob"]),
    ("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def", ["eyJhbGciOiJIUzI1NiJ9"]),
    ('{"access_token":"abcd1234efgh","refresh_token":"zzzyyy"}', ["abcd1234efgh", "zzzyyy"]),
    ("https://aio.example/stremio/eyJ1c2VyIjoiYWJjZGVmZ2hpamtsbW5vcCJ9/manifest.json", ["eyJ1c2VyIjoiYWJjZGVmZ2hpamtsbW5vcCJ9"]),
    ("assistant: failed (key sk-ant-api03-AbCdEf_12345-xyz rejected)", ["AbCdEf_12345"]),
]
keep = [("title: cast 12 (Trakt, 10 with photos)", "title: cast 12 (Trakt, 10 with photos)")]
java = "class NativePlayer {\n" + safe + "}\nclass T {\n" + scrub + """
  public static void main(String[] a) { for (String s : a) System.out.println(scrub(s)); } }
"""
with tempfile.TemporaryDirectory() as t:
    pathlib.Path(t, "T.java").write_text(java)
    subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "T.java"], cwd=t, check=True)
    out = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", t, "T"] + [c for c, _ in cases] + [k for k, _ in keep],
                         capture_output=True, text=True, check=True).stdout.splitlines()
bad = []
for (line, secrets), got in zip(cases, out):
    for sct in secrets:
        if sct in got: bad.append(f"{sct!r} still in: {got}")
for (line, want), got in zip(keep, out[len(cases):]):
    if got != want: bad.append(f"an ordinary line was changed: {got}")
if bad: print("scrub test FAILED:\n  " + "\n  ".join(bad)); sys.exit(1)
print(f"scrub test: {len(cases)} kinds of key or login removed, ordinary lines untouched")
