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
    parser.add_argument("--performance", action="store_true", help="Sample real frame intervals without a performance pass/fail threshold")
    parser.add_argument("--graphics-api", choices=("opengl", "prefer-vulkan"), default="opengl",
                        help="Startup graphics preference for a fresh isolated client; performance reports verify the active backend")
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--sample-seconds", type=int, default=15)
    parser.add_argument("--warmup-seconds", type=int, default=5)
    parser.add_argument("--history-run", type=Path,
                        help="Diagnose history reload using a saved isolated automation run (keeps its original report)")
    parser.add_argument("--allow-partial", action="store_true",
                        help="Accept explicitly skipped scenarios (still rejects failures)")
    args = parser.parse_args()
    if args.performance and args.history_run:
        parser.error("Performance sampling requires a fresh isolated run")
    if min(args.width, args.height, args.sample_seconds, args.warmup_seconds) <= 0:
        parser.error("Dimensions and sampling durations must be positive")
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
    if not args.history_run:
        # Options loads this JSON string codec before creating the graphics device.
        # PreferredGraphicsApi.VULKAN tries Vulkan first, then OpenGL on failure.
        backend = "vulkan" if args.graphics_api == "prefer-vulkan" else "opengl"
        # 26.3 client version.json declares world_version 5023. Omitting version
        # runs legacy options data fixes, which can replace new graphics options.
        options = ["version:5023", f'preferredGraphicsBackend:"{backend}"',
                   "fullscreen:false", "enableVsync:false"]
        if args.performance:
            options.extend([f"overrideWidth:{args.width}", f"overrideHeight:{args.height}"])
        (run_dir / "options.txt").write_text("\n".join(options) + "\n", encoding="utf-8")
        environment["ATOMCHAT_AUTOMATION_GRAPHICS_API"] = args.graphics_api
    report = evidence / "report.json"
    log = evidence / "process.log"
    if args.java_home:
        environment["JAVA_HOME"] = str(args.java_home.resolve())
        environment["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + environment.get("PATH", "")
    wrapper = str(project / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    command = [wrapper, "runAutomation", "--no-daemon", "--console=plain",
               f"-Patomchat.automation.gameDir={run_dir}",
               f"-Patomchat.automation.report={report}"]
    if args.performance:
        command.extend(["-Patomchat.automation.performance=true",
                        f"-Patomchat.automation.width={args.width}",
                        f"-Patomchat.automation.height={args.height}",
                        f"-Patomchat.automation.sampleSeconds={args.sample_seconds}",
                        f"-Patomchat.automation.warmupSeconds={args.warmup_seconds}"])
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
    for sample in data.get("performance", []):
        print(f"{sample['phase']} {sample['width']}x{sample['height']}: "
              f"frame interval p50={sample['frameIntervalP50Ms']:.3f} ms, "
              f"p95={sample['frameIntervalP95Ms']:.3f} ms; n={sample['intervalCount']}")
        print(sample["frameProfile"])
        print(f"Graphics requested: {sample.get('requestedGraphicsApi')}; "
              f"active: {sample.get('activeGraphicsBackend')}; "
              f"device: {sample.get('graphicsDevice')}")
    print(f"Overall: {status}; Gradle exit: {exit_code}; report: {report}")
    if exit_code or status in ("FAIL", "RUNNING", "MISSING") or not checks:
        return 2
    if any(item.get("status") == "FAIL" for item in checks):
        return 2
    if any(sample.get("backendMatchesRequest") is False for sample in data.get("performance", [])):
        print("FAIL: actual graphics backend did not match the request; fallback measurements are preserved in the report", file=sys.stderr)
        return 2
    if status == "PASS":
        return 0
    return 0 if args.allow_partial and status == "PARTIAL" else 3


if __name__ == "__main__":
    raise SystemExit(main())
