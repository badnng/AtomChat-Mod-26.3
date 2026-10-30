#!/usr/bin/env python3
"""Offline release gates and attachment preparation; publishing belongs to the workflow."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

from build_metadata import ROOT, PLATFORMS, bundled, digest, targets, verify_stamp, version, write_json


def validate_tag(root, tag):
    current = version(root)
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?(?:\+[A-Za-z0-9][A-Za-z0-9.-]*)?", tag):
        raise ValueError("release tag must be v<major>.<minor>.<patch> with an optional suffix")
    if tag != "v" + current:
        raise ValueError(f"tag {tag} does not match mod_version={current}")
    return {"tag": tag, "version": current, "prerelease": "-" in tag}


def extract_notes(text, current_version):
    # Match an entire heading token, never v0.3.0 against v0.3.01 or -Preview.
    headings = list(re.finditer(r"(?m)^##[ \t]+v([^\s]+)[ \t]*\r?$", text))
    for wanted in dict.fromkeys((current_version, current_version.split("-", 1)[0])):
        for index, match in enumerate(headings):
            if match.group(1) == wanted:
                following = re.search(r"(?m)^##[ \t]+", text[match.end():])
                end = match.end() + following.start() if following else len(text)
                return text[match.start():end].strip() + "\n"
    raise ValueError(f"RELEASE_NOTES.md has no exact section for v{current_version} or its base version")


def safe_name(name):
    return isinstance(name, str) and bool(name) and name not in (".", "..") and not any(c in name for c in "/\\\r\n\0")


def verify_target(root, artifacts_dir, target):
    declaration = targets(root).get(target)
    if not declaration or declaration.get("buildable") is not True:
        raise ValueError("target must be buildable in versions/targets.json")
    found = []
    for path in artifacts_dir.rglob("build-info.json"):
        info = json.loads(path.read_text(encoding="utf-8"))
        if info.get("target") == target:
            found.append((path, info))
    if len(found) != 1:
        raise ValueError("expected exactly one metadata file for target")
    metadata_path, info = found[0]
    commit = info.get("commit", "")
    if (info.get("projectName") != "AtomChat" or info.get("version") != version(root)
            or info.get("java") != declaration["java"] or not re.fullmatch(r"[0-9a-fA-F]{40,64}", commit)):
        raise ValueError("target metadata identity mismatch")
    expected_commit = os.environ.get("ATOMCHAT_BUILD_SHA") or os.environ.get("GITHUB_SHA")
    if expected_commit and expected_commit != commit:
        raise ValueError("artifact commit does not match expected build SHA")
    items = info.get("artifacts")
    if not isinstance(items, list) or len(items) != 2:
        raise ValueError("expected exactly two artifacts")
    paths, names, checksums = {}, set(), []
    for item in items:
        name, platform = item.get("fileName"), item.get("platform")
        if (not safe_name(name) or not bundled(Path(name)) or name in names
                or platform not in PLATFORMS or platform in paths
                or ("-linux-x64" in Path(name).stem) != (platform == "linux-x64")):
            raise ValueError("invalid artifact filename or platform")
        path = metadata_path.parent / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item.get("size") or digest(path) != item.get("sha256"):
            raise ValueError("artifact size/SHA256 mismatch")
        verify_stamp(path, version(root), target, commit)
        paths[platform] = str(path.resolve())
        names.add(name)
        checksums.append(f"{item['sha256']}  {name}\n")
    if {path.name for path in metadata_path.parent.glob("*.jar")} != names:
        raise ValueError("unexpected target JAR files")
    if (metadata_path.parent / "SHA256SUMS").read_text(encoding="utf-8") != "".join(checksums):
        raise ValueError("SHA256SUMS mismatch")
    return {"windows_jar": paths["windows-x64"], "linux_jar": paths["linux-x64"]}


def prepare(root, tag, artifacts_dir, output, notes_path):
    release = validate_tag(root, tag)
    expected = {name: item for name, item in targets(root).items()
                if item.get("buildable") is True and (root / item["project"]).is_dir()}
    if not expected:
        raise ValueError("no buildable projects in target matrix")
    missing_projects = [name for name, item in targets(root).items()
                        if item.get("buildable") is True and not (root / item["project"]).is_dir()]
    if missing_projects:
        raise ValueError(f"buildable projects are missing: {missing_projects}")
    metadata_paths = sorted(artifacts_dir.rglob("build-info.json"))
    seen, names, copies, manifests = set(), set(), [], []
    identity = None
    for metadata_path in metadata_paths:
        info = json.loads(metadata_path.read_text(encoding="utf-8"))
        target = info.get("target")
        if target not in expected or target in seen:
            raise ValueError(f"unexpected or duplicate metadata target: {target}")
        seen.add(target)
        if info.get("projectName") != "AtomChat" or info.get("version") != release["version"] or info.get("java") != expected[target]["java"]:
            raise ValueError(f"{target}: metadata version/project/Java mismatch")
        commit = info.get("commit", "")
        if not re.fullmatch(r"[0-9a-fA-F]{40,64}", commit):
            raise ValueError(f"{target}: invalid commit")
        current_identity = (commit, info.get("repository"), info.get("runUrl"))
        if identity is not None and identity != current_identity:
            raise ValueError("metadata mixes different commits, repositories or workflow runs")
        identity = current_identity
        expected_commit = os.environ.get("ATOMCHAT_BUILD_SHA") or os.environ.get("GITHUB_SHA")
        if expected_commit and commit != expected_commit:
            raise ValueError("artifact commit does not match expected build SHA")
        items = info.get("artifacts")
        if not isinstance(items, list) or len(items) != len(PLATFORMS):
            raise ValueError(f"{target}: expected exactly two artifacts")
        platforms = set()
        sum_lines = []
        local_names = set()
        for item in items:
            name, platform = item.get("fileName"), item.get("platform")
            if not safe_name(name) or not bundled(Path(name)) or name in names:
                raise ValueError(f"{target}: unsafe, excluded or duplicate artifact filename")
            if platform not in PLATFORMS or platform in platforms:
                raise ValueError(f"{target}: invalid or duplicate native platform")
            if ("-linux-x64" in Path(name).stem) != (platform == "linux-x64"):
                raise ValueError(f"{target}: platform filename mismatch")
            platforms.add(platform)
            names.add(name)
            local_names.add(name)
            path = metadata_path.parent / name
            if path.is_symlink() or not path.is_file() or path.stat().st_size != item.get("size") or digest(path) != item.get("sha256"):
                raise ValueError(f"{target}/{name}: missing artifact or size/SHA256 mismatch")
            verify_stamp(path, release["version"], target, commit)
            copies.append((path, name))
            sum_lines.append(f"{item['sha256']}  {name}\n")
        actual_jars = {path.name for path in metadata_path.parent.glob("*.jar")}
        if actual_jars != local_names:
            raise ValueError(f"{target}: JAR files do not exactly match metadata")
        sums_path = metadata_path.parent / "SHA256SUMS"
        expected_sums = "".join(sum_lines)
        if not sums_path.is_file() or sums_path.read_text(encoding="utf-8") != expected_sums:
            raise ValueError(f"{target}: SHA256SUMS mismatch")
        copies += [(metadata_path, f"build-info-{target}.json"), (sums_path, f"SHA256SUMS-{target}")]
        manifests.append(info)
    if seen != set(expected):
        raise ValueError(f"missing metadata targets: {sorted(set(expected) - seen)}")
    listed_jars = {path.resolve() for path, name in copies if name.endswith(".jar")}
    if {path.resolve() for path in artifacts_dir.rglob("*.jar")} != listed_jars:
        raise ValueError("unlisted JAR files in downloaded artifacts")
    notes = extract_notes((root / "RELEASE_NOTES.md").read_text(encoding="utf-8"), release["version"])
    if output.exists() and any(output.iterdir()):
        raise ValueError("release output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    for source, name in copies:
        shutil.copyfile(source, output / name)
    sums = "".join(f"{digest(output / name)}  {name}\n" for _, name in sorted(copies, key=lambda pair: pair[1]))
    (output / "SHA256SUMS").write_text(sums, encoding="utf-8")
    release.update({"targets": sorted(seen), "jarCount": len(listed_jars), "builds": manifests})
    write_json(output / "release-info.json", release)
    notes_path.parent.mkdir(parents=True, exist_ok=True)
    notes_path.write_text(notes, encoding="utf-8")
    return release


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    subs = parser.add_subparsers(dest="command", required=True)
    validate = subs.add_parser("validate-tag")
    validate.add_argument("--tag", default=os.environ.get("GITHUB_REF_NAME"), required=not os.environ.get("GITHUB_REF_NAME"))
    validate.add_argument("--github-output", type=Path)
    verify = subs.add_parser("verify-target")
    verify.add_argument("--artifacts-dir", type=Path, default=Path("artifacts"))
    verify.add_argument("--target", required=True)
    verify.add_argument("--github-output", type=Path)
    prep = subs.add_parser("prepare")
    prep.add_argument("--tag", default=os.environ.get("GITHUB_REF_NAME"), required=not os.environ.get("GITHUB_REF_NAME"))
    prep.add_argument("--artifacts-dir", type=Path, default=Path("artifacts"))
    prep.add_argument("--output", type=Path, default=Path("release-out"))
    prep.add_argument("--notes", type=Path, default=Path("release-notes.md"))
    args = parser.parse_args()
    try:
        if args.command in ("validate-tag", "verify-target"):
            result = (validate_tag(args.root, args.tag) if args.command == "validate-tag"
                      else verify_target(args.root, args.artifacts_dir, args.target))
            if args.github_output:
                with args.github_output.open("a", encoding="utf-8") as stream:
                    for key, value in result.items():
                        stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
        else:
            result = prepare(args.root, args.tag, args.artifacts_dir, args.output, args.notes)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"ERROR: {exc}\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
