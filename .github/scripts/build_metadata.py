#!/usr/bin/env python3
"""Collect one bundled JAR per native platform and describe the exact bytes."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PLATFORMS = {"windows-x64": "build/libs", "linux-x64": "build/linux-x64/libs"}


def properties(text):
    return dict(line.strip().split("=", 1) for line in text.splitlines()
                if "=" in line and not line.lstrip().startswith(("#", "!")))


def version(root):
    return properties((root / "gradle.properties").read_text(encoding="utf-8"))["mod_version"].strip()


def targets(root):
    raw = json.loads((root / "versions/targets.json").read_text(encoding="utf-8"))
    return {name: item for name, item in raw.items()
            if not name.startswith("_") and isinstance(item, dict)}


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def bundled(path):
    return path.suffix == ".jar" and not any(part in path.name.lower()
                                              for part in ("-slim", "sources", "javadoc"))


def verify_stamp(path, expected_version, target, commit):
    with zipfile.ZipFile(path) as jar:
        if jar.namelist().count("atomchat-build.properties") != 1:
            raise ValueError(f"{path.name}: expected exactly one build stamp")
        stamp = properties(jar.read("atomchat-build.properties").decode("utf-8"))
    if stamp.get("git_dirty") != "false":
        raise ValueError(f"{path.name}: missing or dirty build stamp")
    if stamp.get("version") != expected_version or stamp.get("target") != target:
        raise ValueError(f"{path.name}: build stamp version/target mismatch")
    recorded = stamp.get("git_commit", "")
    if len(recorded) < 7 or not commit.startswith(recorded):
        raise ValueError(f"{path.name}: build stamp commit mismatch")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True,
                                   encoding="utf-8").strip()


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def collect(root, project, target, java, output, artifact_name=None, env=None):
    env = os.environ if env is None else env
    declaration = targets(root).get(target)
    if declaration is None or declaration.get("project") != project or declaration.get("java") != java:
        raise ValueError("project, target and Java must match versions/targets.json")
    project_path = (root / project).resolve()
    if not project_path.is_relative_to(root.resolve()) or not project_path.is_dir():
        raise ValueError("project must be an existing directory inside the repository")
    current_version = version(root)
    commit = git(root, "rev-parse", "HEAD")
    expected_commit = env.get("ATOMCHAT_BUILD_SHA") or env.get("GITHUB_SHA")
    if expected_commit and expected_commit != commit:
        raise ValueError("expected build SHA does not match the checked-out commit")
    selected = []
    for platform, directory in PLATFORMS.items():
        candidates = sorted(p for p in (project_path / directory).glob("*.jar") if bundled(p))
        if len(candidates) != 1:
            raise ValueError(f"{target}/{platform}: expected one bundled JAR, found {len(candidates)}")
        path = candidates[0]
        if ("-linux-x64" in path.stem) != (platform == "linux-x64"):
            raise ValueError(f"{path.name}: platform filename mismatch")
        verify_stamp(path, current_version, target, commit)
        selected.append((platform, path))
    if output.exists() and any(output.iterdir()):
        raise ValueError("output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for platform, path in selected:
        dest = output / path.name
        shutil.copyfile(path, dest)
        artifacts.append({"fileName": dest.name, "size": dest.stat().st_size,
                          "sha256": digest(dest), "platform": platform})
    repository = env.get("GITHUB_REPOSITORY", "")
    server = env.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
    repository_url = f"{server}/{repository}" if repository else ""
    run_url = f"{repository_url}/actions/runs/{env['GITHUB_RUN_ID']}" if repository and env.get("GITHUB_RUN_ID") else ""
    info = {"projectName": "AtomChat", "version": current_version, "target": target, "java": java,
            "commit": commit, "branch": env.get("GITHUB_REF_NAME") or git(root, "rev-parse", "--abbrev-ref", "HEAD"),
            "buildTime": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "repository": repository, "runUrl": run_url,
            "artifactUrl": run_url, "artifacts": artifacts,
            "artifactName": artifact_name or f"jar-{target}",
            "commitSubject": git(root, "log", "-1", "--format=%s"),
            "commitUrl": f"{repository_url}/commit/{commit}" if repository_url else "",
            "actor": env.get("GITHUB_ACTOR", ""), "runNumber": env.get("GITHUB_RUN_NUMBER", "")}
    write_json(output / "build-info.json", info)
    (output / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['fileName']}\n" for item in artifacts), encoding="utf-8")
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--project", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--java", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifact-name")
    args = parser.parse_args()
    try:
        info = collect(args.root, args.project, args.target, args.java, args.output, args.artifact_name)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"ERROR: {exc}\n")
    print(json.dumps(info, ensure_ascii=False))


if __name__ == "__main__":
    main()
