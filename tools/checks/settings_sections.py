#!/usr/bin/env python3
"""Every settings row is listed in a Settings section (a row that isn't never shows on the TV)."""
import re, sys, pathlib
s = (pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/index.html").read_text()
i = s.index("const SET_SECTIONS = ["); j = s.index("['Advanced',", i) + 200
sec = s[i:j]
labels = set(re.findall(r"\{ label: '([^']+)'", s))
regs = [re.compile(r) for r in re.findall(r"/([^/,\]]+)/", sec)]
missing = sorted(l for l in labels if ("'" + l + "'") not in sec and not any(r.search(l) for r in regs))
if missing:
    print("Settings rows not in any section (they won't show):", missing); sys.exit(1)
print(f"settings sections: all {len(labels)} rows listed")
