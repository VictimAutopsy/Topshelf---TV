#!/usr/bin/env bash
# Builds TopShelf.apk without Gradle or the Android SDK, using Ubuntu/Debian packages:
#   sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
# and an android.jar for API 34 (pass its path as the first argument).
set -euo pipefail
ANDROID_JAR="${1:?usage: build-local.sh /path/to/android.jar [versionCode]}"
VCODE="${2:-1}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build/local"
rm -rf "$OUT" && mkdir -p "$OUT/classes"

# 0. The page's scripts must at least parse (a syntax error there stops Settings and the updater)
if command -v node >/dev/null; then
  python3 - "$SRC/assets" "$OUT" <<'PY'
import re, sys, os
src, out = sys.argv[1], sys.argv[2]
for f in os.listdir(src):
    if f.endswith('.html'):
        s = open(os.path.join(src, f), encoding='utf-8').read()
        blocks = re.findall(r'<script>(.*?)</script>', s, re.S)
        if blocks: open(os.path.join(out, 'check-' + f + '.js'), 'w', encoding='utf-8').write('\n;\n'.join(blocks))
PY
  for f in "$SRC"/assets/*.js "$OUT"/check-*.js; do node --check "$f" || { echo "JavaScript syntax error in $f" >&2; exit 1; }; done
fi

# 1. Resources + manifest + assets (aapt needs the package name in the manifest; Gradle doesn't want it there)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.topshelf.launcher">|' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt package -f \
  -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "1.0.$VCODE" \
  -F "$OUT/unsigned.apk"

# 2. Java -> class files (Java 8 bytecode for the dex tool)
JAVAC_OK=1
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$ROOT/app/libs/media3-classes.jar" -d "$OUT/classes" \
  $(find "$SRC/java" -name '*.java') > "$OUT/javac.log" 2>&1 || JAVAC_OK=0
grep -v -E 'JAVA_TOOL_OPTIONS|bootstrap class path|obsolete|-Xlint:-options|^[0-9]+ warnings?$' "$OUT/javac.log" || true
if [ "$JAVAC_OK" = 0 ]; then echo "Java compile failed" >&2; exit 1; fi

# 3. Class files -> classes.dex
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
# the video library (Media3 / ExoPlayer + Guava), compiled once by tools/build-media3.sh, rides along as classes2.dex
cp "$ROOT/app/libs/media3.dex" "$OUT/classes2.dex"
(cd "$OUT" && aapt add unsigned.apk classes.dex classes2.dex >/dev/null)

# 4. Align + sign with the project key
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# The signing key is private and never in this repository: it lives in the private updates repository
# (topshelf-updates/signing/), or wherever TOPSHELF_KEYSTORE points. Every build must use that same key, or
# Android won't install it over the TopShelf already on the TV.
KS="${TOPSHELF_KEYSTORE:-}"
for c in "$ROOT/app/topshelf.keystore" "$ROOT/../topshelf-updates/signing/topshelf.keystore"; do
  [ -z "$KS" ] && [ -f "$c" ] && KS="$c"
done
if [ -z "$KS" ] || [ ! -f "$KS" ]; then
  echo "No signing key found. Clone the private topshelf-updates repository next to this one, or set TOPSHELF_KEYSTORE." >&2; exit 1
fi
KSPASS="${TOPSHELF_KS_PASS:-}"
[ -z "$KSPASS" ] && [ -f "$(dirname "$KS")/password.txt" ] && KSPASS="$(tr -d '\r\n' < "$(dirname "$KS")/password.txt")"
export KSPASS
[ -z "$KSPASS" ] && { echo "No signing key password: set TOPSHELF_KS_PASS or put password.txt next to the key." >&2; exit 1; }
apksigner sign --ks "$KS" --ks-pass env:KSPASS --key-pass env:KSPASS \
  --ks-key-alias topshelf --out "$ROOT/TopShelf.apk" "$OUT/aligned.apk"
apksigner verify "$ROOT/TopShelf.apk"
echo "Built $ROOT/TopShelf.apk"
