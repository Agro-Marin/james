"""Holds every @Disabled and unstable-tagged test to a verdict in marin/excluded-tests.json.

A test of a backend the fork does not deploy matches one of the file's "other_backends" patterns. Every other one
needs its own entry: keep (stays excluded, for the reason given), fix (in the phase named) or delete.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

ANNOTATION = re.compile(r'@Disabled(?:\((.*)\))?|@Tag\((?:Unstable\.TAG|"unstable")\)')
TARGET = re.compile(r"(?:void|class|interface|def)\s+(\w+)")
VERDICTS = {"keep", "fix", "delete"}


def excluded(repo):
    files = subprocess.run(
        ["git", "-C", str(repo), "grep", "-lE", r'@Disabled|Unstable\.TAG|@Tag\("unstable"\)', "--", "*.java", "*.scala"],
        capture_output=True, text=True, check=True,
    ).stdout.split()
    found = {}
    for name in files:
        lines = (repo / name).read_text(errors="replace").split("\n")
        module = name.split("/src/")[0]
        for index, line in enumerate(lines):
            match = ANNOTATION.search(line)
            if not match or line.lstrip().startswith(("//", "import ")):
                continue
            target = next((t.group(1) for t in map(TARGET.search, lines[index:index + 8]) if t), "?")
            test = f"{module}::{Path(name).stem}#{target}"
            found[test] = "disabled" if "@Disabled" in line else "unstable"
    return found


def main():
    repo = Path(sys.argv[1])
    verdicts = json.loads((repo / "marin" / "excluded-tests.json").read_text())
    other = [re.compile(p) for p in verdicts["other_backends"]]
    tests = verdicts["tests"]
    found = excluded(repo)
    ours = {t: kind for t, kind in found.items() if not any(p.search(t.split("::")[0]) for p in other)}
    problems = [f"no verdict for {kind} {t}" for t, kind in sorted(ours.items()) if t not in tests]
    problems += [f"verdict for {t}, which is no longer excluded (remove it)" for t in sorted(set(tests) - set(ours))]
    problems += [f"{t}: verdict must be one of {sorted(VERDICTS)}" for t, v in sorted(tests.items())
                 if v.get("verdict") not in VERDICTS or not v.get("why")]
    counts = {v: sum(1 for t in ours if tests.get(t, {}).get("verdict") == v) for v in sorted(VERDICTS)}
    print(f"excluded tests: {len(found)}, {len(found) - len(ours)} of other backends, {len(ours)} ours {counts}")
    for problem in problems:
        print(f"EXCLUDED {problem}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
