#!/usr/bin/env python3
"""Regression checks for explicit target replacements of inherited Java sources.

Run directly: python tools/test_source_overrides.py
Fixtures are isolated; the repository matrix and source trees are never scanned.
"""
from __future__ import annotations

import pathlib
import tempfile
import unittest
from unittest.mock import patch

import verify_targets


class SourceOverridesTest(unittest.TestCase):
    MAIN = "src/main/java/com/example/Bridge.java"
    TEST = "src/test/java/com/example/BridgeTest.java"

    def setUp(self) -> None:
        # Keep fixtures inside the workspace: Windows sandbox temp directories
        # do not consistently support child-file writes.
        workspace = pathlib.Path(__file__).resolve().parent.parent
        fixture_parent = workspace / "build"
        fixture_parent.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="source-overrides-", dir=fixture_parent)
        self.root = pathlib.Path(self.temp.name).resolve()
        if not self.root.is_relative_to(fixture_parent.resolve()):
            raise RuntimeError("Fixture escaped workspace build directory")
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(patch.stopall)
        patch.object(verify_targets, "ROOT", self.root).start()
        patch.object(verify_targets, "errors", []).start()
        self.project = self.root / "platforms" / "test-target"
        self.layers = {"official": {"mappings": "official", "populated": True}}
        self.target = {"layers": ["official"], "source_overrides": [self.MAIN]}
        self.write(self.root / "shared" / self.MAIN)
        self.write(self.project / self.MAIN)

    @staticmethod
    def write(path: pathlib.Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package com.example; class Bridge {}\n", encoding="utf-8")

    def validate(self) -> list[str]:
        verify_targets.check_source_overrides("test-target", self.project, self.target, self.layers)
        return verify_targets.errors

    def test_explicit_shared_and_layer_test_replacements_are_valid(self) -> None:
        self.write(self.root / "layers/mapping/official" / self.TEST)
        self.write(self.project / self.TEST)
        self.target["source_overrides"].append(self.TEST)
        self.assertEqual([], self.validate())

    def test_undeclared_platform_duplicate_is_rejected(self) -> None:
        self.target["source_overrides"] = []
        self.assertTrue(self.validate())

    def test_missing_inherited_source_is_rejected(self) -> None:
        (self.root / "shared" / self.MAIN).unlink()
        self.assertTrue(self.validate())

    def test_missing_platform_replacement_is_rejected(self) -> None:
        (self.project / self.MAIN).unlink()
        self.assertTrue(self.validate())

    def test_duplicate_declaration_is_rejected(self) -> None:
        self.target["source_overrides"].append(self.MAIN)
        self.assertTrue(self.validate())

    def test_traversal_is_rejected_even_if_destination_exists(self) -> None:
        self.target["source_overrides"] = ["src/main/java/../Bridge.java"]
        self.write(self.project / "src/main/Bridge.java")
        self.assertTrue(self.validate())

    def test_ambiguous_shared_and_layer_sources_are_rejected(self) -> None:
        self.write(self.root / "layers/mapping/official" / self.MAIN)
        self.assertTrue(self.validate())


if __name__ == "__main__":
    unittest.main()
