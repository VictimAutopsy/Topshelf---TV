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
jget() { python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], sys.argv[3]))" "$REPO/latest.json" "$1" "$2" 2>/dev/null || echo "$2"; }
LAST=$(jget code 0); NEXT=$(jget next $((LAST + 1))); PREV_COMMIT=$(jget commit ""); PREV_NAME=$(jget name "")
[ "$CODE" -ge "$NEXT" ] || stop "version $CODE is too low: the next version is $NEXT (latest.json \"next\"). Rebuild with $NEXT."
HEAD_COMMIT=$(git -C "$ROOT" rev-parse HEAD)

cp "$APK" "$REPO/TopShelf.apk"

# The safety net: the version before this one, rebuilt with a newer version number (Android won't install an older
# one), as TopShelf-fallback.apk. TopShelf goes back to it by itself if this version keeps crashing as it starts,
# and from Settings › Updates › Go back to the previous version. Needs TOPSHELF_SDK (the android.jar for the build).
FB_CODE=""
if [ -n "${TOPSHELF_SDK:-}" ] && [ -n "$PREV_COMMIT" ] && [ "$PREV_COMMIT" != "$HEAD_COMMIT" ]; then
  FB="$ROOT/../topshelf-fallback"
  rm -rf "$FB"; git -C "$ROOT" worktree prune
  if git -C "$ROOT" worktree add -q --detach "$FB" "$PREV_COMMIT" 2>/dev/null && (cd "$FB" && bash build-local.sh "$TOPSHELF_SDK" $((CODE + 1)) >/dev/null 2>&1); then
    cp "$FB/TopShelf.apk" "$REPO/TopShelf-fallback.apk"; FB_CODE=$((CODE + 1))
    echo "Safety net: $PREV_NAME rebuilt as version $FB_CODE"
  else echo "(no safety net this time: the previous version didn't build)" >&2; fi
  git -C "$ROOT" worktree remove --force "$FB" 2>/dev/null || rm -rf "$FB"; git -C "$ROOT" worktree prune
else echo "(no safety net this time: TOPSHELF_SDK not set, or no previous version recorded)" >&2; fi

python3 - "$REPO/latest.json" "$CODE" "$NAME" "$NOTES" "$HEAD_COMMIT" "$FB_CODE" "$PREV_NAME" <<'PY'
import json, sys, datetime
p, code, name, notes, commit, fb, prev = sys.argv[1:]
o = {"code": int(code), "name": name, "apk": "TopShelf.apk", "notes": notes, "commit": commit,
     "next": int(code) + (2 if fb else 1),
     "published": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%MZ")}
if fb: o["fallback"] = {"code": int(fb), "apk": "TopShelf-fallback.apk", "of": prev}
json.dump(o, open(p, "w"), indent=1)
PY
[ -n "$FB_CODE" ] || rm -f "$REPO/TopShelf-fallback.apk"
mkdir -p "$REPO/reports"; [ -f "$REPO/reports/README.md" ] || echo "Diagnostic reports uploaded by TopShelf." > "$REPO/reports/README.md"
[ -f "$REPO/README.md" ] || printf '# TopShelf updates\n\nThe latest TopShelf build (TopShelf.apk + latest.json) and the diagnostic reports TopShelf uploads (reports/).\n' > "$REPO/README.md"
cd "$REPO"
git add -A
git commit -qm "TopShelf $NAME" -m "$NOTES"
git push -q origin HEAD:main
echo "Published $NAME ($CODE)"
