"""Launch the isolated development client and validate its machine-readable report.

Requires the NeoForge project's runAutomation Gradle run (automation sources only).
No desktop automation, AWT headless flag, or existing user worlds are involved.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="JDK 25 installation")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--history-run", type=Path,
                        help="Diagnose history reload using a saved isolated automation run (keeps its original report)")
    parser.add_argument("--allow-partial", action="store_true",
                        help="Accept explicitly skipped scenarios (still rejects failures)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    project = root / "platforms" / "26.3-neoforge"
    environment = os.environ.copy()
    history_world = None
    if args.history_run:
        run_dir = args.history_run.resolve()
        if not run_dir.is_relative_to((project / "build").resolve()) or not run_dir.name.startswith("automation-"):
            parser.error("History diagnostics require an automation-prefixed directory inside this project's build directory")
        try:
            original = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            parser.error(f"Cannot read original fixture report: {error}")
        history_world = original.get("world", "")
        if not re.fullmatch(r"atomchat-automation-[0-9]+", history_world) or not (run_dir / "saves" / history_world).is_dir():
            parser.error("Original report does not name an existing generated fixture world")
        if not any(c.get("name") == "history-written-after-disconnect" and c.get("status") == "PASS"
                   for c in original.get("checks", [])):
            parser.error("Original run has no successful history-write evidence")
        environment["ATOMCHAT_AUTOMATION_HISTORY_WORLD"] = history_world
        environment["ATOMCHAT_AUTOMATION_HISTORY_TOKEN"] = original.get("messageToken", history_world)
        evidence = run_dir / ("history-diagnostic-" + time.strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}")
    else:
        # Every full run owns fresh data. The diagnostic opt-in never changes the parent environment.
        environment.pop("ATOMCHAT_AUTOMATION_HISTORY_WORLD", None)
        environment.pop("ATOMCHAT_AUTOMATION_HISTORY_TOKEN", None)
        run_dir = project / "build" / ("automation-" + time.strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}")
        evidence = run_dir
    evidence.mkdir(parents=True, exist_ok=False)
    report = evidence / "report.json"
    log = evidence / "process.log"
    if args.java_home:
        environment["JAVA_HOME"] = str(args.java_home.resolve())
        environment["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + environment.get("PATH", "")
    wrapper = str(project / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    command = [wrapper, "runAutomation", "--no-daemon", "--console=plain",
               f"-Patomchat.automation.gameDir={run_dir}",
               f"-Patomchat.automation.report={report}"]
    print(f"Automation directory: {run_dir}")
    if history_world:
        print(f"History-only fixture: {history_world}; original report preserved")
    print(f"Process log: {log}")
    started = time.time()
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=project, env=environment, stdout=output,
                                   stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            exit_code = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            # Gradle starts child JVMs; terminate only this process tree.
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdout=output, stderr=subprocess.STDOUT, check=False,
                               creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                process.kill()
            print("FAIL: outer process timeout; inspect process.log", file=sys.stderr)
            return 2
    if not report.is_file() or report.stat().st_mtime < started - 2:
        print(f"FAIL: no fresh client report (Gradle exit {exit_code}); inspect {log}", file=sys.stderr)
        return 2
    try:
        data = json.loads(report.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"FAIL: unreadable report: {error}", file=sys.stderr)
        return 2
    checks = data.get("checks", [])
    for check in checks:
        print(f"{check['status']:5} {check['name']}: {check['detail']}")
    status = data.get("status", "MISSING")
    print(f"Overall: {status}; Gradle exit: {exit_code}; report: {report}")
    if exit_code or status in ("FAIL", "RUNNING", "MISSING") or not checks:
        return 2
    if any(item.get("status") == "FAIL" for item in checks):
        return 2
    if status == "PASS":
        return 0
    return 0 if args.allow_partial and status == "PARTIAL" else 3


if __name__ == "__main__":
    raise SystemExit(main())
