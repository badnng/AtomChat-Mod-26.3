#!/usr/bin/env python3
"""Offline regression checks for retained sources and the active CI matrix."""
from __future__ import annotations

import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import verify_targets


REPO = pathlib.Path(__file__).resolve().parent.parent


class CiTargetsTest(unittest.TestCase):
    def setUp(self) -> None:
        fixture_parent = REPO / ".work"
        fixture_parent.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="ci-targets-", dir=fixture_parent)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(verify_targets, "ROOT", self.root).start()
        patch.object(verify_targets, "errors", []).start()

    def target(self, name: str, *, exists: bool = True, **fields) -> dict:
        project = "platforms/" + name
        if exists:
            (self.root / project).mkdir(parents=True)
        return {"project": project, "java": 25, "buildable": False, **fields}

    def test_existing_retained_sources_are_excluded(self) -> None:
        targets = {"old": self.target("old", ci_build=False),
                   "current": self.target("current", buildable=True)}
        self.assertTrue((self.root / "platforms/old").is_dir())
        self.assertEqual(["current"], [item["target"] for item in verify_targets.make_ci_matrix(targets)])

    def test_missing_ci_flag_preserves_verified_and_unverified_builds(self) -> None:
        targets = {"verified": self.target("verified", buildable=True),
                   "unverified": self.target("unverified")}
        matrix = {item["target"]: item for item in verify_targets.make_ci_matrix(targets)}
        self.assertEqual({"target": "verified", "project": "platforms/verified", "java": 25,
                          "buildable": True, "suffix": ""}, matrix["verified"])
        self.assertEqual({"target": "unverified", "project": "platforms/unverified", "java": 25,
                          "buildable": False, "suffix": "-未验证"}, matrix["unverified"])

    def test_targets_without_project_directories_are_excluded(self) -> None:
        targets = {"absent": self.target("absent", exists=False, ci_build=True)}
        self.assertEqual([], verify_targets.make_ci_matrix(targets))

    def test_matrix_is_sorted_by_target_name(self) -> None:
        targets = {name: self.target(name) for name in ("z-last", "a-first", "m-middle")}
        self.assertEqual(sorted(targets), [item["target"] for item in verify_targets.make_ci_matrix(targets)])

    def test_ci_build_requires_boolean(self) -> None:
        for value in (None, 0, 1, "false", [], {}):
            with self.subTest(value=value):
                verify_targets.errors.clear()
                verify_targets.check_ci_build("invalid", {"buildable": False, "ci_build": value})
                self.assertTrue(verify_targets.errors)
                self.assertIn("ci_build", verify_targets.errors[0])

    def test_releasable_target_cannot_disable_ci(self) -> None:
        verify_targets.check_ci_build("invalid", {"buildable": True, "ci_build": False})
        self.assertTrue(verify_targets.errors)

    def test_supported_ci_and_release_combinations_are_valid(self) -> None:
        for fields in ({"buildable": True}, {"buildable": False},
                       {"buildable": True, "ci_build": True},
                       {"buildable": False, "ci_build": True},
                       {"buildable": False, "ci_build": False}):
            with self.subTest(fields=fields):
                verify_targets.errors.clear()
                verify_targets.check_ci_build("valid", fields)
                self.assertEqual([], verify_targets.errors)

    def test_repository_ci_matrix_contains_only_26_3_neoforge(self) -> None:
        targets = verify_targets.entries_of(json.loads((REPO / "versions/targets.json").read_text(encoding="utf-8")))
        with patch.object(verify_targets, "ROOT", REPO):
            matrix = verify_targets.make_ci_matrix(targets)
        self.assertEqual(["26.3-neoforge"], [item["target"] for item in matrix])
        self.assertTrue(matrix[0]["buildable"])
        self.assertEqual("", matrix[0]["suffix"])
        for name in ("1.21.1-fabric", "1.20.1-forge"):
            self.assertTrue((REPO / targets[name]["project"]).is_dir())
            self.assertIs(targets[name]["buildable"], False)
            self.assertIs(targets[name]["ci_build"], False)


if __name__ == "__main__":
    unittest.main()
