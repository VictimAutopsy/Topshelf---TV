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

# One copy of the code: the app published must be built from exactly what's on GitHub (another chat may have
# pushed changes this copy doesn't have, and publishing would ship an app without them).
stop() { echo "Not published: $1" >&2; exit 1; }
git -C "$ROOT" fetch -q origin main || stop "couldn't reach GitHub to check the source"
[ -z "$(git -C "$ROOT" status --porcelain -- app build-local.sh tools)" ] || stop "the source has changes that aren't committed. Commit and push them, then rebuild."
[ "$(git -C "$ROOT" rev-list --count HEAD..origin/main)" = 0 ] || stop "GitHub has newer source than this copy (another chat pushed). Pull (git pull --rebase origin main), rebuild, push, then publish."
[ "$(git -C "$ROOT" rev-list --count origin/main..HEAD)" = 0 ] || stop "this source isn't pushed to GitHub yet. Push it (git push origin HEAD:main), then publish."
git -C "$REPO" pull -q --rebase origin main || stop "couldn't update the updates repository"
LAST=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('code', 0))" "$REPO/latest.json" 2>/dev/null || echo 0)
[ "$CODE" -gt "$LAST" ] || stop "version $CODE isn't newer than the published $LAST. Rebuild with $((LAST + 1))."

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
