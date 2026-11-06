#!/usr/bin/env bash
# Every page script parses (the inline scripts of each page, and each .js file).
set -euo pipefail
A="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/assets"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
for f in "$A"/*.js; do node --check "$f"; done
for h in "$A"/*.html; do
  python3 - "$h" "$T/$(basename "$h").js" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
open(sys.argv[2], "w").write("\n;\n".join(m for m in re.findall(r"<script>(.*?)</script>", s, re.S)))
PY
  node --check "$T/$(basename "$h").js"
done
echo "js syntax: $(ls "$A"/*.js | wc -l) scripts and $(ls "$A"/*.html | wc -l) pages parse"
