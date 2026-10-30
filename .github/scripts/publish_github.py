#!/usr/bin/env python3
"""Publish a verified release directory through GitHub's official REST API."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


API_ROOT = "https://api.github.com"
UPLOAD_ROOT = "https://uploads.github.com"
API_VERSION = "2022-11-28"
TAG_PATTERN = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?(?:\+[A-Za-z0-9][A-Za-z0-9.-]*)?")
REPOSITORY_PATTERN = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
SHA_PATTERN = re.compile(r"[0-9a-fA-F]{40,64}\Z")


class PublishError(RuntimeError):
    """A sanitized failure safe to print in a workflow log."""


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A release POST/PATCH must never be silently repeated at a redirect target.
        return None


def open_request(request: Request, timeout: int = 120):
    """Open one request without following redirects or retrying mutations."""
    return build_opener(_NoRedirectHandler()).open(request, timeout=timeout)


def _api_call(
    method: str,
    url: str,
    token: str,
    *,
    operation: str,
    payload: dict[str, Any] | None = None,
    raw_body: bytes | None = None,
    content_type: str | None = None,
    accepted: tuple[int, ...] = (200,),
    allow_404: bool = False,
) -> Any:
    if payload is not None and raw_body is not None:
        raise ValueError("request cannot have both JSON and raw bodies")
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request_content_type = "application/json"
    else:
        body = raw_body
        request_content_type = content_type

    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "User-Agent": "AtomChat-release-publisher",
        "X-GitHub-Api-Version": API_VERSION,
    }
    if request_content_type:
        headers["Content-Type"] = request_content_type
    request = Request(url, data=body, headers=headers, method=method)
    try:
        with open_request(request) as response:
            status = response.getcode()
            response_body = response.read()
    except HTTPError as exc:
        status = exc.code
        exc.close()
        if allow_404 and status == 404:
            return None
        raise PublishError(f"GitHub {operation} failed (HTTP {status})") from None
    except (URLError, TimeoutError, OSError):
        raise PublishError(f"GitHub {operation} failed (network error)") from None

    if allow_404 and status == 404:
        return None
    if status not in accepted:
        raise PublishError(f"GitHub {operation} failed (HTTP {status})")
    if not response_body:
        return {}
    try:
        decoded = json.loads(response_body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise PublishError(f"GitHub {operation} returned an invalid response") from None
    if not isinstance(decoded, (dict, list)):
        raise PublishError(f"GitHub {operation} returned an invalid response")
    return decoded


def _list_releases(token: str, repository: str, tag: str) -> list[dict[str, Any]]:
    """Find releases, including drafts, using GitHub's paginated list endpoint."""
    repo_path = quote(repository, safe="/")
    matches = []
    page = 1
    while True:
        query = urlencode({"per_page": 100, "page": page})
        releases = _api_call(
            "GET",
            f"{API_ROOT}/repos/{repo_path}/releases?{query}",
            token,
            operation="release list",
        )
        if not isinstance(releases, list) or len(releases) > 100:
            raise PublishError("GitHub release list returned an invalid response")
        for release in releases:
            if not isinstance(release, dict):
                raise PublishError("GitHub release list returned an invalid release")
            if release.get("tag_name") == tag:
                matches.append(release)
        if len(releases) < 100:
            return matches
        page += 1


def _validated_environment(env: dict[str, str]) -> tuple[str, str, str]:
    token = env.get("GH_TOKEN", "").strip()
    repository = env.get("GITHUB_REPOSITORY", "").strip()
    commit = env.get("ATOMCHAT_BUILD_SHA", "").strip()
    if not token:
        raise PublishError("GH_TOKEN is required")
    if not REPOSITORY_PATTERN.fullmatch(repository):
        raise PublishError("GITHUB_REPOSITORY must be owner/repository")
    if not SHA_PATTERN.fullmatch(commit):
        raise PublishError("ATOMCHAT_BUILD_SHA must be a full Git commit SHA")
    return token, repository, commit


def _release_assets(release: dict[str, Any]) -> list[dict[str, Any]]:
    assets = release.get("assets", [])
    if not isinstance(assets, list):
        raise PublishError("GitHub release returned an invalid asset list")
    checked = []
    for asset in assets:
        if not isinstance(asset, dict) or type(asset.get("id")) is not int or asset["id"] < 1:
            raise PublishError("GitHub release returned an invalid asset")
        checked.append(asset)
    return checked


def _release_id(release: dict[str, Any]) -> int:
    release_id = release.get("id")
    if type(release_id) is not int or release_id < 1:
        raise PublishError("GitHub release response has no valid ID")
    return release_id


def _local_assets(directory: Path) -> list[tuple[Path, str, int]]:
    if directory.is_symlink() or not directory.is_dir():
        raise PublishError("release directory is missing or invalid")
    assets = []
    for path in sorted(directory.iterdir(), key=lambda item: item.name):
        if path.is_symlink() or not path.is_file():
            raise PublishError("release directory may contain only regular files")
        name = path.name
        if (not name or name in (".", "..") or any(char in name for char in "/\\\r\n\0")):
            raise PublishError("release directory contains an unsafe filename")
        assets.append((path, name, path.stat().st_size))
    if not assets:
        raise PublishError("release directory contains no assets")
    if len({name for _, name, _ in assets}) != len(assets):
        raise PublishError("release directory contains duplicate asset names")
    return assets


def _content_type(name: str) -> str:
    if name.lower().endswith(".jar"):
        return "application/java-archive"
    if name.lower().endswith(".json"):
        return "application/json"
    return "text/plain; charset=utf-8"


def _verify_assets(release: dict[str, Any], expected: dict[str, int]) -> None:
    actual: dict[str, int] = {}
    for asset in _release_assets(release):
        name, size = asset.get("name"), asset.get("size")
        if not isinstance(name, str) or type(size) is not int or name in actual:
            raise PublishError("uploaded release assets failed verification")
        actual[name] = size
    if actual != expected:
        raise PublishError("uploaded release asset names or sizes do not match local files")


def publish(
    tag: str,
    directory: Path,
    notes_path: Path,
    prerelease: bool = False,
    *,
    env: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Create or reuse a draft, replace its assets, verify them, then publish."""
    if not TAG_PATTERN.fullmatch(tag):
        raise PublishError("tag must be v<major>.<minor>.<patch> with an optional suffix")
    token, repository, commit = _validated_environment(os.environ if env is None else env)
    repo_path = quote(repository, safe="/")
    tag_path = quote(tag, safe="")
    tag_url = f"{API_ROOT}/repos/{repo_path}/releases/tags/{tag_path}"

    # GitHub's by-tag endpoint is published-only. Check it first so an existing
    # published release is rejected immediately; on 404, enumerate releases because
    # an authenticated List releases call can include drafts.
    published = _api_call("GET", tag_url, token, operation="release lookup", allow_404=True)
    if published is not None:
        raise PublishError("release tag is already published; refusing to overwrite it")

    matching_releases = _list_releases(token, repository, tag)
    if len(matching_releases) > 1:
        raise PublishError("multiple GitHub releases use this tag; refusing to publish")
    release = matching_releases[0] if matching_releases else None
    if release is not None:
        if release.get("draft") is False:
            raise PublishError("release tag is already published; refusing to overwrite it")
        if release.get("draft") is not True:
            raise PublishError("GitHub release list returned an invalid draft state")

    local_assets = _local_assets(Path(directory))
    try:
        notes = Path(notes_path).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        raise PublishError("release notes file is missing or unreadable") from None
    expected_sizes = {name: size for _, name, size in local_assets}

    if release is None:
        release = _api_call(
            "POST",
            f"{API_ROOT}/repos/{repo_path}/releases",
            token,
            operation="draft creation",
            payload={
                "tag_name": tag,
                "target_commitish": commit,
                "name": tag,
                "body": notes,
                "draft": True,
                "prerelease": prerelease,
            },
            accepted=(201,),
        )
        if release is None or release.get("draft") is not True:
            raise PublishError("GitHub did not create a draft release")
        if release.get("tag_name") != tag:
            raise PublishError("GitHub created a release for an unexpected tag")
    else:
        # Only drafts are mutable here. Remove all prior assets so stale files cannot
        # survive a retry, and same-name files can be uploaded again.
        for asset in _release_assets(release):
            _api_call(
                "DELETE",
                f"{API_ROOT}/repos/{repo_path}/releases/assets/{asset['id']}",
                token,
                operation="draft asset removal",
                accepted=(204,),
            )

    release_id = _release_id(release)
    release_url = f"{API_ROOT}/repos/{repo_path}/releases/{release_id}"
    upload_base = f"{UPLOAD_ROOT}/repos/{repo_path}/releases/{release_id}/assets"
    for path, name, expected_size in local_assets:
        try:
            content = path.read_bytes()
        except OSError:
            raise PublishError("release asset became unreadable before upload") from None
        if len(content) != expected_size or path.stat().st_size != expected_size:
            raise PublishError("release asset changed before upload")
        upload_url = f"{upload_base}?{urlencode({'name': name})}"
        _api_call(
            "POST",
            upload_url,
            token,
            operation="asset upload",
            raw_body=content,
            content_type=_content_type(name),
            accepted=(201,),
        )

    verified = _api_call("GET", release_url, token, operation="asset verification")
    if verified is None or verified.get("draft") is not True:
        raise PublishError("draft release changed before asset verification")
    _verify_assets(verified, expected_sizes)
    for path, _, size in local_assets:
        if path.stat().st_size != size:
            raise PublishError("local release assets changed during upload")

    published = _api_call(
        "PATCH",
        release_url,
        token,
        operation="release publication",
        payload={
            "draft": False,
            "body": notes,
            "prerelease": prerelease,
            "target_commitish": commit,
            "name": tag,
        },
    )
    if published is None or published.get("draft") is not False:
        raise PublishError("GitHub did not confirm release publication")
    _verify_assets(published, expected_sizes)
    return {"tag": tag, "releaseUrl": published.get("html_url", ""), "assetCount": len(local_assets)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default=os.environ.get("GITHUB_REF_NAME"), required=not os.environ.get("GITHUB_REF_NAME"))
    parser.add_argument("--directory", type=Path, default=Path("release-out"))
    parser.add_argument("--notes", type=Path, default=Path("release-notes.md"))
    parser.add_argument("--prerelease", action="store_true")
    args = parser.parse_args()
    try:
        result = publish(args.tag, args.directory, args.notes, args.prerelease)
    except PublishError as exc:
        parser.exit(1, f"ERROR: {exc}\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
