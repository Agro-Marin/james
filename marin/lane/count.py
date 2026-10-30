"""Counts a lane run and holds it to floors.json.

JUnit counts (tests run, skipped) and imaptest's scripted results are exact in both directions, failing tests included:
a test that starts or stops failing moves a floor, in the same commit. Stress violations depend on how clients interleave,
so each kind has a ceiling instead, set by hand; --update leaves those alone. James's own log under imaptest is held the
same way: its errors, and the warnings that report a broken invariant, which imaptest cannot see. Every failing test needs
a reason in floors.json.
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPTED_SUMMARY = {
    "groups": re.compile(r"^(\d+) test groups: (\d+) failed, (\d+) skipped", re.M),
    "base": re.compile(r"^base protocol: (\d+)/(\d+) individual commands failed", re.M),
    "extensions": re.compile(r"^extensions: (\d+)/(\d+) individual commands failed", re.M),
}


ANSI = re.compile(r"\x1b\[[0-9;]*m")
LOG_LINE = re.compile(r"\[(ERROR|WARN) ?\] (\S+) - (.*)")
# A usage below zero means a message was counted out twice; a negative quota lets a mailbox grow past it.
GATED_WARNINGS = ("Invalid quota usage",)


def junit(run):
    counts, failing = {}, {}
    for stage_dir in sorted((run / "junit").iterdir()) if (run / "junit").is_dir() else []:
        stage = stage_dir.name
        counts[stage], failing[stage] = {}, []
        for module_dir in sorted(p for p in stage_dir.rglob("*") if p.is_dir() and any(p.glob("TEST-*.xml"))):
            module = str(module_dir.relative_to(stage_dir))
            total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
            for report in sorted(module_dir.glob("TEST-*.xml")):
                for case in ET.parse(report).getroot().iter("testcase"):
                    total["tests"] += 1
                    if case.find("failure") is not None:
                        total["failures"] += 1
                    elif case.find("error") is not None:
                        total["errors"] += 1
                    elif case.find("skipped") is not None:
                        total["skipped"] += 1
                        continue
                    else:
                        continue
                    failing[stage].append(f"{module}::{case.get('classname')}#{case.get('name')}")
            counts[stage][module] = total
        failing[stage] = sorted(set(failing[stage]))
    return counts, failing


def scripted(log):
    text = log.read_text(errors="replace")
    result = {}
    for key, pattern in SCRIPTED_SUMMARY.items():
        match = pattern.search(text)
        if not match:
            raise SystemExit(f"count.py: {log} has no {key} summary; did imaptest finish?")
        result[key] = [int(n) for n in match.groups()]
    result["failing"] = sorted(set(re.sub(r" \(line \d+\)$", "", line[len("*** Test "):].strip())
                                   for line in text.splitlines() if line.startswith("*** Test ")))
    return result


def stress(log):
    kinds = {}
    for line in log.read_text(errors="replace").splitlines():
        if line.startswith("Error: "):
            message = re.sub(r"^Error: (\S+\[\d+\]: )?", "", line)
            kind = re.sub(r"\d+", "N", message.split(": ", 1)[0])
            kinds[kind] = kinds.get(kind, 0) + 1
    return dict(sorted(kinds.items()))


def james_log(log):
    kinds = {}
    for line in log.read_text(errors="replace").splitlines():
        match = LOG_LINE.search(ANSI.sub("", line))
        if not match:
            continue
        level, logger, message = match.groups()
        if level == "WARN" and not message.startswith(GATED_WARNINGS):
            continue
        message = re.sub(r"\d+", "N", re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27}", "X", message))
        kind = f"{level} {logger} - {message[:70]}"
        kinds[kind] = kinds.get(kind, 0) + 1
    return dict(sorted(kinds.items()))


def measure(run):
    counts, failing = junit(run)
    result = {"junit": counts, "junit_failing": failing}
    if (run / "imaptest-scripted.log").exists():
        result["imaptest_scripted"] = scripted(run / "imaptest-scripted.log")
    if (run / "imaptest-stress.log").exists():
        result["imaptest_stress"] = stress(run / "imaptest-stress.log")
    if (run / "james" / "james.out").exists():
        result["james_log"] = james_log(run / "james" / "james.out")
    return result


def reason_for(failure, reasons):
    for key in (failure, failure.split("#")[0], failure.split(" command ")[0]):
        if key in reasons:
            return reasons[key]
    return None


COUNTED = ("tests", "skipped")


def counted(modules):
    return {module: {key: total[key] for key in COUNTED} for module, total in modules.items()}


def compare(result, floors):
    problems = []
    for stage, modules in result["junit"].items():
        expected = floors.get("junit", {}).get(stage)
        if expected is None:
            problems.append(f"junit {stage}: no floors for this stage")
            continue
        modules = counted(modules)
        for module in sorted(set(modules) | set(expected)):
            if modules.get(module) != expected.get(module):
                problems.append(f"junit {stage} {module}: {expected.get(module)} -> {modules.get(module)}")
        was = set(floors.get("junit_failing", {}).get(stage, []))
        now = set(result["junit_failing"][stage])
        problems += [f"junit {stage}: now fails {t}" for t in sorted(now - was)]
        problems += [f"junit {stage}: now passes {t} (lower the floor)" for t in sorted(was - now)]
    if "imaptest_scripted" in result:
        got, want = result["imaptest_scripted"], floors.get("imaptest_scripted", {})
        for key in SCRIPTED_SUMMARY:
            if got[key] != want.get(key):
                problems.append(f"imaptest scripted {key}: {want.get(key)} -> {got[key]}")
        was, now = set(want.get("failing", [])), set(got["failing"])
        problems += [f"imaptest scripted: now fails {t}" for t in sorted(now - was)]
        problems += [f"imaptest scripted: now passes {t} (lower the floor)" for t in sorted(was - now)]
    if "imaptest_stress" in result:
        ceilings = floors.get("imaptest_stress_ceilings", {})
        for kind, count in result["imaptest_stress"].items():
            if kind not in ceilings:
                problems.append(f"imaptest stress: new kind of violation {kind!r} x{count}")
            elif count > ceilings[kind]:
                problems.append(f"imaptest stress {kind!r}: {count} above its ceiling {ceilings[kind]}")
    if "james_log" in result:
        ceilings = floors.get("james_log_ceilings", {})
        for kind, count in result["james_log"].items():
            if count > ceilings.get(kind, 0):
                problems.append(f"james log {kind!r}: {count} above its ceiling {ceilings.get(kind, 0)}")
    reasons = floors.get("reasons", {})
    failures = [t for stage in result["junit_failing"].values() for t in stage]
    failures += result.get("imaptest_scripted", {}).get("failing", [])
    problems += [f"no reason in floors.json for {t}" for t in sorted(set(failures)) if reason_for(t, reasons) is None]
    return problems


def update(result, floors):
    floors["junit"] = {**floors.get("junit", {}), **{stage: counted(m) for stage, m in result["junit"].items()}}
    floors["junit_failing"] = {**floors.get("junit_failing", {}),
                               **{stage: sorted(set(f)) for stage, f in result["junit_failing"].items()}}
    if "imaptest_scripted" in result:
        floors["imaptest_scripted"] = result["imaptest_scripted"]
    ceilings = floors.setdefault("imaptest_stress_ceilings", {})
    for kind, count in result.get("imaptest_stress", {}).items():
        ceilings.setdefault(kind, count)
    floors.setdefault("reasons", {})
    return floors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    parser.add_argument("floors", type=Path)
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()
    result = measure(args.run)
    (args.run / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    floors = json.loads(args.floors.read_text()) if args.floors.exists() else {}
    if args.update:
        args.floors.write_text(json.dumps(update(result, floors), indent=2, sort_keys=True) + "\n")
        floors = json.loads(args.floors.read_text())
    problems = compare(result, floors)
    for stage, modules in result["junit"].items():
        total = {k: sum(m[k] for m in modules.values()) for k in ("tests", "failures", "errors", "skipped")}
        print(f"junit {stage}: {total['tests']} tests, {total['failures']} failures, {total['errors']} errors, "
              f"{total['skipped']} skipped")
    if "imaptest_scripted" in result:
        s = result["imaptest_scripted"]
        print(f"imaptest scripted: {s['groups'][0]} groups, {s['groups'][1]} failed, {s['groups'][2]} skipped; "
              f"base {s['base'][0]}/{s['base'][1]}, extensions {s['extensions'][0]}/{s['extensions'][1]} failed")
    if "imaptest_stress" in result:
        print(f"imaptest stress: {sum(result['imaptest_stress'].values())} violations {result['imaptest_stress']}")
    if "james_log" in result:
        print(f"james log: {sum(result['james_log'].values())} errors and gated warnings {result['james_log']}")
    for problem in problems:
        print(f"FLOOR {problem}")
    print("lane: green" if not problems else f"lane: red, {len(problems)} off floor")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
