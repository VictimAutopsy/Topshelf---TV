#!/usr/bin/env bash
# All the checks a change has to pass before it's published (and on GitHub for every push).
# usage: tools/checks/run-all.sh /path/to/android.jar
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; SDK="${1:?android.jar}"
C="$(mktemp -d)"; trap 'rm -rf "$C"' EXIT
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$SDK" -d "$C" -cp "$ROOT/app/libs/media3-classes.jar" $(find "$ROOT/app/src/main/java" -name '*.java') > "$C/javac.log" 2>&1; then
  grep -v '^Note:\|JAVA_TOOL' "$C/javac.log"; echo "java: doesn't compile"; exit 1
fi
echo "java: compiles"
bash "$ROOT/tools/checks/js_syntax.sh"
python3 "$ROOT/tools/checks/settings_sections.py"
python3 "$ROOT/tools/checks/scrub_test.py"
if node -e "require('playwright')" 2>/dev/null || [ -d "$ROOT/tools/checks/node_modules/playwright" ]; then node "$ROOT/tools/checks/page_smoke.js"; else echo "(page smoke skipped: playwright not installed)"; fi
echo "all checks passed"
