"""Offline fixtures for collection and release gates (no network or Gradle)."""
import copy
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_metadata as metadata
import release_ci as release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "versions").mkdir()
        self.target = "26.3-neoforge"
        self.project = "platforms/" + self.target
        self.commit = "a" * 40
        (self.root / "gradle.properties").write_text("mod_version=0.3.0\n", encoding="utf-8")
        self.matrix = {self.target: {"project": self.project, "java": 25, "buildable": True},
                       "future": {"project": "platforms/future", "java": 30, "buildable": False}}
        self.save_matrix()
        (self.root / "RELEASE_NOTES.md").write_text("## v0.3.01\nwrong\n## v0.3.0\nrelease\n## v0.2.0\nold\n", encoding="utf-8")
        for platform, directory in metadata.PLATFORMS.items():
            libs = self.root / self.project / directory
            libs.mkdir(parents=True)
            name = "AtomChat-0.3.0" + ("-linux-x64" if platform == "linux-x64" else "") + ".jar"
            self.make_jar(libs / name)
        self.env = {"GITHUB_REPOSITORY": "owner/AtomChat", "GITHUB_RUN_ID": "42", "GITHUB_SHA": self.commit,
                    "GITHUB_REF_NAME": "main", "GITHUB_ACTOR": "builder", "GITHUB_RUN_NUMBER": "12"}
        self.artifacts = self.root / "artifacts"
        self.bundle = self.artifacts / "jar-neoforge"
        self.output = self.root / "release-out"
        self.notes = self.root / "release-notes.md"
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {}, clear=True).start()
        patch.object(metadata, "git", side_effect=lambda root, *args: self.commit if args == ("rev-parse", "HEAD") else "fixture commit").start()

    def save_matrix(self):
        (self.root / "versions/targets.json").write_text(json.dumps(self.matrix), encoding="utf-8")

    def make_jar(self, path, dirty="false", version="0.3.0", commit=None):
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("atomchat-build.properties", f"version={version}\ntarget={self.target}\ngit_commit={commit or self.commit[:7]}\ngit_dirty={dirty}\n")

    def collect(self):
        return metadata.collect(self.root, self.project, self.target, 25, self.bundle, "jar-neoforge", self.env)

    def prepare(self):
        return release.prepare(self.root, "v0.3.0", self.artifacts, self.output, self.notes)

    def change_info(self, edit):
        path = self.bundle / "build-info.json"
        info = json.loads(path.read_text(encoding="utf-8"))
        edit(info)
        metadata.write_json(path, info)

    def test_round_trip_and_interfaces(self):
        info = self.collect()
        self.assertEqual(info["artifactUrl"], "https://github.com/owner/AtomChat/actions/runs/42")
        self.assertEqual({i["platform"] for i in info["artifacts"]}, set(metadata.PLATFORMS))
        self.assertEqual(info["projectName"], "AtomChat")
        result = self.prepare()
        self.assertEqual(result["jarCount"], 2)
        self.assertEqual(self.notes.read_text(), "## v0.3.0\nrelease\n")
        self.assertTrue((self.output / f"build-info-{self.target}.json").is_file())
        self.assertEqual(len(list(self.output.glob("*.jar"))), 2)

    def test_exact_notes_and_prerelease_fallback(self):
        text = "## v0.3.01\nwrong\n## v0.3.0\nbase\n## v0.3.0-rc1\nrc\n"
        self.assertEqual(release.extract_notes(text, "0.3.0-rc1"), "## v0.3.0-rc1\nrc\n")
        self.assertEqual(release.extract_notes(text, "0.3.0-rc2"), "## v0.3.0\nbase\n")
        with self.assertRaises(ValueError):
            release.extract_notes("## v0.3.01\nwrong", "0.3.0")

    def test_tag_must_match_full_version(self):
        self.assertFalse(release.validate_tag(self.root, "v0.3.0")["prerelease"])
        for tag in ("0.3.0", "v0.3.0-rc1", "v0.3.01", "v../0.3.0"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.validate_tag(self.root, tag)
        (self.root / "gradle.properties").write_text("mod_version=0.3.0-Preview\n")
        self.assertTrue(release.validate_tag(self.root, "v0.3.0-Preview")["prerelease"])

    def test_collection_excludes_development_jars(self):
        libs = self.root / self.project / "build/libs"
        for name in ("x-slim.jar", "x-sources.jar", "x-javadoc.jar"):
            (libs / name).write_bytes(b"excluded")
        self.assertEqual(len(self.collect()["artifacts"]), 2)

    def test_ambiguous_or_missing_jar_rejected(self):
        libs = self.root / self.project / "build/libs"
        (libs / "stale.jar").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "expected one"):
            self.collect()
        for path in libs.glob("*.jar"):
            path.unlink()
        with self.assertRaisesRegex(ValueError, "expected one"):
            self.collect()

    def test_dirty_missing_or_wrong_stamp_rejected(self):
        jar = next((self.root / self.project / "build/libs").glob("*.jar"))
        for dirty, version, commit in (("true", "0.3.0", None), ("unknown", "0.3.0", None), ("false", "9.0.0", None), ("false", "0.3.0", "b" * 7)):
            self.make_jar(jar, dirty, version, commit)
            with self.subTest(dirty=dirty, version=version, commit=commit), self.assertRaises(ValueError):
                self.collect()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("anything", "no stamp")
        with self.assertRaises(ValueError):
            self.collect()

    def test_java_and_commit_gate(self):
        with self.assertRaises(ValueError):
            metadata.collect(self.root, self.project, self.target, 21, self.bundle, env=self.env)
        self.env["GITHUB_SHA"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "checked-out"):
            self.collect()

    def test_tampered_jar_rejected(self):
        self.collect()
        next(self.bundle.glob("*.jar")).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            self.prepare()

    def test_metadata_platform_count_path_version_and_commit_gates(self):
        self.collect()
        original = json.loads((self.bundle / "build-info.json").read_text())
        edits = [lambda i: i["artifacts"].pop(),
                 lambda i: i["artifacts"][1].update(platform="windows-x64"),
                 lambda i: i["artifacts"][0].update(fileName="../escape.jar"),
                 lambda i: i.update(version="0.3.01"),
                 lambda i: i.update(commit="invalid")]
        for edit in edits:
            metadata.write_json(self.bundle / "build-info.json", copy.deepcopy(original))
            self.change_info(edit)
            with self.subTest(edit=edit), self.assertRaises(ValueError):
                self.prepare()

    def test_missing_and_duplicate_target_rejected(self):
        self.collect()
        self.matrix["new-target"] = {"project": "platforms/new-target", "java": 21, "buildable": True}
        (self.root / "platforms/new-target").mkdir()
        self.save_matrix()
        with self.assertRaisesRegex(ValueError, "missing metadata"):
            self.prepare()
        del self.matrix["new-target"]
        self.save_matrix()
        other = self.artifacts / "duplicate"
        shutil.copytree(self.bundle, other)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare()

    def test_unlisted_jar_and_checksums_rejected(self):
        self.collect()
        extra = self.artifacts / "extra.jar"
        extra.write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "unlisted"):
            self.prepare()
        extra.unlink()
        (self.bundle / "SHA256SUMS").write_text("wrong")
        with self.assertRaisesRegex(ValueError, "SHA256SUMS mismatch"):
            self.prepare()

    def test_output_reuse_rejected(self):
        self.collect()
        with self.assertRaisesRegex(ValueError, "empty"):
            self.collect()
        self.prepare()
        with self.assertRaisesRegex(ValueError, "empty"):
            self.prepare()

    def test_verify_target_paths_and_integrity(self):
        self.collect()
        paths = release.verify_target(self.root, self.artifacts, self.target)
        self.assertEqual(set(paths), {"windows_jar", "linux_jar"})
        self.assertTrue(all(Path(path).is_file() for path in paths.values()))
        Path(paths["linux_jar"]).write_bytes(b"corrupted")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            release.verify_target(self.root, self.artifacts, self.target)

    def test_verify_target_rejects_disabled_and_missing_target(self):
        self.collect()
        for target in ("future", "missing"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                release.verify_target(self.root, self.artifacts, target)

    def test_release_count_follows_matrix_and_rejects_mixed_runs(self):
        self.collect()
        self.target = "new-loader"
        self.project = "platforms/new-loader"
        self.matrix[self.target] = {"project": self.project, "java": 25, "buildable": True}
        self.save_matrix()
        self.bundle = self.artifacts / "jar-new-loader"
        for platform, directory in metadata.PLATFORMS.items():
            libs = self.root / self.project / directory
            libs.mkdir(parents=True)
            self.make_jar(libs / ("new-loader" + ("-linux-x64" if platform == "linux-x64" else "") + ".jar"))
        self.collect()
        self.change_info(lambda info: info.update(runUrl="https://github.com/another/run"))
        with self.assertRaisesRegex(ValueError, "different commits, repositories or workflow runs"):
            self.prepare()
        self.change_info(lambda info: info.update(runUrl="https://github.com/owner/AtomChat/actions/runs/42"))
        self.assertEqual(self.prepare()["jarCount"], 4)

    def test_manual_dispatch_tag_sha_overrides_dispatch_branch_sha(self):
        self.env.update(GITHUB_SHA="b" * 40, ATOMCHAT_BUILD_SHA=self.commit)
        self.collect()
        with patch.dict(os.environ, {"GITHUB_SHA": "b" * 40, "ATOMCHAT_BUILD_SHA": self.commit}):
            self.assertIn("windows_jar", release.verify_target(self.root, self.artifacts, self.target))
            self.assertEqual(self.prepare()["jarCount"], 2)
        # The explicit selected tag remains the gate even if GITHUB_SHA matches.
        with patch.dict(os.environ, {"GITHUB_SHA": self.commit, "ATOMCHAT_BUILD_SHA": "c" * 40}):
            with self.assertRaisesRegex(ValueError, "expected build SHA"):
                release.verify_target(self.root, self.artifacts, self.target)
            with self.assertRaisesRegex(ValueError, "expected build SHA"):
                self.prepare()


if __name__ == "__main__":
    unittest.main()
