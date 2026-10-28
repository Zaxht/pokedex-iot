"""Collect actual JUnit/lint results and hashes without reading private PC data."""
import argparse
import csv
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    suites, cases, metrics = [], [], []
    locations = {"app": root / "app/build/test-results/testDebugUnitTest", "server": root / "server/build/test-results/test"}
    for module, location in locations.items():
        files = sorted(location.glob("TEST-*.xml"))
        if not files:
            raise RuntimeError(f"No JUnit results for {module}; run Gradle first")
        target = output / "junit" / module
        target.mkdir(parents=True, exist_ok=True)
        for path in files:
            suite = ET.parse(path).getroot()
            suites.append({"module": module, "name": suite.attrib["name"], "timestamp": suite.get("timestamp"),
                           **{k: int(suite.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")},
                           "seconds": float(suite.get("time", "0"))})
            for case in suite.findall("testcase"):
                status = "failed" if case.find("failure") is not None or case.find("error") is not None else "skipped" if case.find("skipped") is not None else "passed"
                cases.append({"module": module, "class": case.get("classname"), "test": case.get("name"), "status": status, "seconds": float(case.get("time", "0"))})
            for stream in suite.findall("system-out"):
                for line in (stream.text or "").splitlines():
                    if line.startswith("ACCEPTANCE_METRIC "):
                        metrics.append(json.loads(line[len("ACCEPTANCE_METRIC "):]))
            shutil.copy2(path, target / path.name)
    lint_path = root / "app/build/reports/lint-results-debug.xml"
    if not lint_path.exists():
        raise RuntimeError("Missing Android lint report")
    lint = {}
    for issue in ET.parse(lint_path).getroot().findall("issue"):
        severity = issue.get("severity", "Unknown")
        lint[severity] = lint.get(severity, 0) + 1
    shutil.copy2(lint_path, output / "lint-results-debug.xml")
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    build_text = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    totals = {key: sum(s[key] for s in suites) for key in ("tests", "failures", "errors", "skipped")}
    summary = {"generatedAtUtc": datetime.now(timezone.utc).isoformat(),
               "scope": "JUnit on JVM; AccountApi HTTP loopback and temporary SQLite; production PcClient HTTPS against temporary certificate; virtual Arduino. No Android emulator, physical phone or Arduino execution, no Ollama inference.",
               "version": re.search(r'versionName = "([^"]+)"', build_text).group(1),
               "totals": totals, "suites": suites, "metrics": metrics, "lint": lint,
               "apk": {"path": str(apk.relative_to(root)), "bytes": apk.stat().st_size, "sha256": digest(apk)}}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    with (output / "cases.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=("module", "class", "test", "status", "seconds"))
        writer.writeheader(); writer.writerows(cases)
    files = [root / "build.gradle.kts", root / "settings.gradle.kts", root / "gradle.properties"]
    for folder in ("app/src", "server/src", "hardware"):
        files += sorted(p for p in (root / folder).rglob("*") if p.is_file())
    files += [root / "app/build.gradle.kts", root / "server/build.gradle.kts", apk]
    (output / "sha256.txt").write_text("\n".join(f"{digest(p)}  {p.relative_to(root).as_posix()}" for p in files) + "\n", encoding="utf-8")
    print(json.dumps({"totals": totals, "lint": lint, "metrics": metrics}, ensure_ascii=False))
    if totals["failures"] or totals["errors"] or lint.get("Error", 0) or lint.get("Fatal", 0):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
