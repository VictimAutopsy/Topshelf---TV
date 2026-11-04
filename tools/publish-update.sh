#!/usr/bin/env bash
# Puts the built TopShelf.apk into the updates repository (default ../topshelf-updates) with latest.json,
# so TVs and phones with an updates token offer it.  usage: tools/publish-update.sh "what's new"
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${UPDATES_REPO:-$ROOT/../topshelf-updates}"
NOTES="${1:-}"
APK="$ROOT/TopShelf.apk"
BADGE=$(aapt dump badging "$APK" | head -1)
CODE=$(echo "$BADGE" | sed -E "s/.*versionCode='([0-9]+)'.*/\1/")
NAME=$(echo "$BADGE" | sed -E "s/.*versionName='([^']+)'.*/\1/")
cp "$APK" "$REPO/TopShelf.apk"
python3 - "$REPO/latest.json" "$CODE" "$NAME" "$NOTES" <<'PY'
import json, sys, datetime
p, code, name, notes = sys.argv[1:]
json.dump({"code": int(code), "name": name, "apk": "TopShelf.apk", "notes": notes,
           "published": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%MZ")}, open(p, "w"), indent=1)
PY
mkdir -p "$REPO/reports"; [ -f "$REPO/reports/README.md" ] || echo "Diagnostic reports uploaded by TopShelf." > "$REPO/reports/README.md"
[ -f "$REPO/README.md" ] || printf '# TopShelf updates\n\nThe latest TopShelf build (TopShelf.apk + latest.json) and the diagnostic reports TopShelf uploads (reports/).\n' > "$REPO/README.md"
cd "$REPO"
git add -A
git commit -qm "TopShelf $NAME" -m "$NOTES"
git push -q origin HEAD:main
echo "Published $NAME ($CODE)"
