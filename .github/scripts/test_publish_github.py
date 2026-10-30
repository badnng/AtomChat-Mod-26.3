"""Offline GitHub draft publisher tests; every HTTP request is mocked."""
from __future__ import annotations

import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit

import publish_github as publisher
import release_ci


ENV = {
    "GH_TOKEN": "test-secret-token",
    "GITHUB_REPOSITORY": "owner/AtomChat",
    "ATOMCHAT_BUILD_SHA": "a" * 40,
}


class FakeResponse:
    def __init__(self, status: int, body=None):
        self.status = status
        self._body = json.dumps({} if body is None else body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def getcode(self):
        return self.status

    def read(self):
        return self._body


class FakeGitHub:
    def __init__(
        self,
        lookup="missing",
        *,
        upload_failure=None,
        size_mismatch=False,
        list_pages=None,
        list_failure=None,
        existing_assets=None,
    ):
        self.lookup = lookup
        self.upload_failure = upload_failure
        self.size_mismatch = size_mismatch
        self.list_failure = list_failure
        self.calls = []
        self.release = None
        self.next_asset_id = 100
        self.existing_assets = existing_assets or []
        self.list_pages = list_pages if list_pages is not None else self._default_pages()
        for page in self.list_pages:
            for release in page:
                if release.get("tag_name") == "v0.3.0":
                    self.release = release
                    break
            if self.release is not None:
                break

    def _record(self, *, draft, release_id=17, assets=None):
        return {
            "id": release_id,
            "tag_name": "v0.3.0",
            "draft": draft,
            "assets": list(self.existing_assets if assets is None else assets),
            "html_url": "https://github.com/owner/AtomChat/releases/tag/v0.3.0",
        }

    def _default_pages(self):
        if self.lookup == "draft":
            return [[self._record(draft=True)]]
        if self.lookup == "list_published":
            return [[self._record(draft=False)]]
        if self.lookup == "duplicates":
            return [[self._record(draft=True), self._record(draft=False, release_id=18)]]
        return [[]]

    @staticmethod
    def _json(request):
        return json.loads(request.data.decode("utf-8"))

    def open(self, request, timeout=120):
        method = request.get_method()
        parsed = urlsplit(request.full_url)
        query = parse_qs(parsed.query)
        path = parsed.path
        self.calls.append((method, path, query, request))

        if method == "GET" and path.endswith("/releases/tags/v0.3.0"):
            if self.lookup in ("404", "draft", "list_published", "duplicates", "list_failure", "pagination_draft", "missing"):
                raise HTTPError(request.full_url, 404, "not found", {}, io.BytesIO())
            if isinstance(self.lookup, int):
                raise HTTPError(request.full_url, self.lookup, "failure", {}, io.BytesIO())
            if self.lookup == "published":
                return FakeResponse(200, {"id": 17, "tag_name": "v0.3.0", "draft": False, "assets": []})
            raise AssertionError(f"unexpected test lookup mode: {self.lookup}")

        if method == "GET" and path.endswith("/releases"):
            if self.lookup == "list_failure" or self.list_failure:
                raise HTTPError(request.full_url, self.list_failure or 500, "failure", {}, io.BytesIO())
            page = int(query.get("page", ["1"])[0])
            if page > len(self.list_pages):
                return FakeResponse(200, [])
            return FakeResponse(200, self.list_pages[page - 1])

        if method == "POST" and path.endswith("/releases"):
            payload = self._json(request)
            self.release = {
                "id": 17,
                "tag_name": payload["tag_name"],
                "draft": payload["draft"],
                "assets": [],
                "html_url": "https://github.com/owner/AtomChat/releases/tag/v0.3.0",
            }
            return FakeResponse(201, self.release)

        if method == "DELETE" and "/releases/assets/" in path:
            asset_id = int(path.rsplit("/", 1)[1])
            self.release["assets"] = [asset for asset in self.release["assets"] if asset["id"] != asset_id]
            return FakeResponse(204)

        if method == "POST" and "/releases/17/assets" in path:
            name = query.get("name", [""])[0]
            if name == self.upload_failure:
                raise HTTPError(request.full_url, 500, "upload failed", {}, io.BytesIO())
            self.next_asset_id += 1
            size = len(request.data)
            if self.size_mismatch and name == "release-info.json":
                size += 1
            asset = {"id": self.next_asset_id, "name": name, "size": size}
            self.release["assets"].append(asset)
            return FakeResponse(201, asset)

        if method == "GET" and path.endswith("/releases/17"):
            return FakeResponse(200, self.release)

        if method == "PATCH" and path.endswith("/releases/17"):
            payload = self._json(request)
            self.release.update(payload)
            return FakeResponse(200, self.release)

        raise AssertionError(f"unexpected mock request: {method} {path}")


class PublishGitHubTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2])
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "release-out"
        self.directory.mkdir()
        (self.directory / "atomchat.jar").write_bytes(b"jar-bytes")
        (self.directory / "SHA256SUMS").write_text("hash  atomchat.jar\n", encoding="utf-8")
        (self.directory / "build-info-26.3-neoforge.json").write_text("{\"target\":\"26.3-neoforge\"}\n", encoding="utf-8")
        (self.directory / "release-info.json").write_text("{\"version\":\"0.3.0\"}\n", encoding="utf-8")
        self.notes = self.root / "release-notes.md"
        self.notes.write_text("## v0.3.0\nRelease notes\n", encoding="utf-8")
        self.env_patch = patch.dict(os.environ, ENV, clear=True)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)

    def run_publish(self, api, prerelease=False):
        with patch.object(publisher, "open_request", side_effect=api.open):
            return publisher.publish("v0.3.0", self.directory, self.notes, prerelease)

    def test_published_tag_is_rejected_before_any_mutation(self):
        api = FakeGitHub("published")
        with self.assertRaisesRegex(publisher.PublishError, "already published"):
            self.run_publish(api)
        self.assertEqual([call[0] for call in api.calls], ["GET"])

    def test_published_release_found_in_list_is_rejected(self):
        api = FakeGitHub("list_published")
        with self.assertRaisesRegex(publisher.PublishError, "already published"):
            self.run_publish(api)
        self.assertEqual([call[0] for call in api.calls], ["GET", "GET"])
        self.assertFalse(any(call[0] in ("POST", "PATCH", "DELETE") for call in api.calls))

    def test_multiple_releases_for_tag_are_rejected(self):
        api = FakeGitHub("duplicates")
        with self.assertRaisesRegex(publisher.PublishError, "multiple GitHub releases"):
            self.run_publish(api)
        self.assertEqual(len(api.calls), 2)
        self.assertFalse(any(call[0] in ("POST", "PATCH", "DELETE") for call in api.calls))

    def test_only_404_creates_a_draft_with_build_commit(self):
        api = FakeGitHub("404")
        result = self.run_publish(api, prerelease=True)
        creation = next(FakeGitHub._json(call[3]) for call in api.calls if call[0] == "POST" and call[1].endswith("/releases"))
        self.assertEqual(creation["target_commitish"], ENV["ATOMCHAT_BUILD_SHA"])
        self.assertEqual(creation["tag_name"], "v0.3.0")
        self.assertTrue(creation["draft"])
        self.assertTrue(creation["prerelease"])
        self.assertFalse(api.release["draft"])
        self.assertEqual(result["assetCount"], 4)

    def test_non_404_lookup_error_does_not_create_a_release(self):
        api = FakeGitHub(500)
        with self.assertRaisesRegex(publisher.PublishError, "HTTP 500"):
            self.run_publish(api)
        self.assertEqual(len(api.calls), 1)

    def test_release_list_error_is_not_treated_as_missing(self):
        api = FakeGitHub("list_failure")
        with self.assertRaisesRegex(publisher.PublishError, "release list failed"):
            self.run_publish(api)
        self.assertEqual(len(api.calls), 2)
        self.assertFalse(any(call[0] == "POST" for call in api.calls))

    def test_upload_failure_leaves_release_as_draft(self):
        api = FakeGitHub("404", upload_failure="release-info.json")
        with self.assertRaisesRegex(publisher.PublishError, "asset upload failed"):
            self.run_publish(api)
        self.assertTrue(api.release["draft"])
        self.assertFalse(any(call[0] == "PATCH" for call in api.calls))

    def test_existing_draft_replaces_old_assets_and_publishes_after_verification(self):
        api = FakeGitHub(
            "draft",
            existing_assets=[{"id": 5, "name": "stale.jar", "size": 3}],
        )
        self.run_publish(api)
        delete_index = next(i for i, call in enumerate(api.calls) if call[0] == "DELETE")
        upload_indices = [i for i, call in enumerate(api.calls) if call[0] == "POST" and "/assets" in call[1]]
        verify_index = next(i for i, call in enumerate(api.calls) if call[0] == "GET" and call[1].endswith("/releases/17"))
        publish_index = next(i for i, call in enumerate(api.calls) if call[0] == "PATCH")
        self.assertLess(delete_index, min(upload_indices))
        self.assertLess(max(upload_indices), verify_index)
        self.assertLess(verify_index, publish_index)
        self.assertEqual(FakeGitHub._json(api.calls[publish_index][3])["draft"], False)
        self.assertEqual(api.calls[-1][0], "PATCH")
        self.assertEqual(
            {asset["name"]: asset["size"] for asset in api.release["assets"]},
            {path.name: path.stat().st_size for path in self.directory.iterdir()},
        )

    def test_asset_size_mismatch_never_publishes(self):
        api = FakeGitHub("404", size_mismatch=True)
        with self.assertRaisesRegex(publisher.PublishError, "do not match local files"):
            self.run_publish(api)
        self.assertTrue(api.release["draft"])
        self.assertFalse(any(call[0] == "PATCH" for call in api.calls))

    def test_release_draft_is_found_on_later_list_page(self):
        first_page = [
            {"id": index + 1, "tag_name": f"v0.2.{index}", "draft": False, "assets": []}
            for index in range(100)
        ]
        second_page = [FakeGitHub("draft")._record(draft=True)]
        api = FakeGitHub("pagination_draft", list_pages=[first_page, second_page])
        self.run_publish(api)
        list_calls = [call for call in api.calls if call[0] == "GET" and call[1].endswith("/releases")]
        self.assertEqual([call[2]["page"] for call in list_calls], [["1"], ["2"]])
        self.assertEqual(api.calls[-1][0], "PATCH")

    def test_tag_pattern_matches_release_ci_validation(self):
        for tag in (
            "v0.3.0", "v0.3.0-rc1", "v0.3.0+build.7", "v0.3.0-rc1+build-7",
            "v0.3", "v0.3.0-", "v0.3.0+", "v0.3.0/extra", "v0.3.0\n",
        ):
            with self.subTest(tag=tag), patch.object(release_ci, "version", return_value=tag[1:] if tag.startswith("v") else "0.3.0"):
                try:
                    release_ci.validate_tag(Path("."), tag)
                    release_ci_accepts = True
                except ValueError:
                    release_ci_accepts = False
                self.assertEqual(bool(publisher.TAG_PATTERN.fullmatch(tag)), release_ci_accepts)


if __name__ == "__main__":
    unittest.main()
