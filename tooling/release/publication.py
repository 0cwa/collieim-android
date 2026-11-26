#!/usr/bin/env python3
"""Guard the public Collie draft -> prerelease -> full-release transitions.

TrailBlazer has a separate identity and must not use these entry points. This
checks published package history, not the maintainer's private signing ledger.
GitHub does not offer a transaction over tags, assets and release state: the
workflows serialize cooperating writers and this helper checks remote identity
again before and after each publication transition.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

REPOSITORY = "0cwa/collieim-android"
PACKAGE = "im.collie.app"
CERTIFICATE = "f8cc0c0c052e5635c26fcd293c2b0af3e87d61ff7f2df3602a9af6e91f73c826"
APK_NAMES = ("collie-foss.apk", "collie.apk", "molly-continued.apk",
             "molly-foss-continued.apk", "molly-minimal.apk")
RECORD_NAME = "publication.json"
MANIFEST_NAME = "release.json"
# A Stable release may also carry the TrailBlazer Beta APK released alongside it.
BETA_NAME = "collie-beta.apk"
BETA_PACKAGE = "im.collie.app.trailblazer"
BETA_CERTIFICATE = "a515c03498bce8852b835544910b09ccc11a3abbed23e826e9c62c002680fe98"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9]+)?(?:-(?:alpha|beta|rc)[0-9]+)?\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
SHA1 = re.compile(r"[0-9a-f]{40}\Z")
MAX_APK_BYTES = 2_000_000_000
MAX_DOWNLOAD_BYTES = 8_000_000_000


class Failure(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Failure(message)


def positive(value):
    return type(value) is int and value > 0


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON property: " + key)
        result[key] = value
    return result


def read_json(path):
    return json.loads(Path(path).read_text(), object_pairs_hook=unique_object)


def write_json(path, value):
    with Path(path).open("x", encoding="utf-8") as output:
        json.dump(value, output, sort_keys=True, indent=2)
        output.write("\n")


def stable_tag(tag):
    require(isinstance(tag, str) and tag.startswith("v") and
            VERSION.fullmatch(tag[1:]) is not None and ".." not in tag,
            "Only a Collie version tag (v<version>) is accepted; TrailBlazer uses a separate publisher")
    return tag


def version_properties(text, tag):
    values = {}
    for key in ("releaseVersionName", "releaseVersionCode"):
        matches = re.findall(r"^" + key + r"=([^\r\n]+)$", text, re.M)
        require(len(matches) == 1, "Tag source must declare exactly one " + key)
        values[key] = matches[0]
    name, code = values["releaseVersionName"], values["releaseVersionCode"]
    require(stable_tag(tag) == "v" + name, "Tag and source version name differ")
    require(re.fullmatch(r"[1-9][0-9]*", code) is not None and int(code) <= 2_100_000_000,
            "Invalid source versionCode")
    return {"version_name": name, "version_code": int(code)}


def asset_identity(asset):
    require(isinstance(asset, dict), "Invalid release asset")
    name, digest = asset.get("name"), asset.get("digest")
    require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_.+-]+", name) is not None,
            "Unsafe release asset name")
    require(positive(asset.get("id")) and positive(asset.get("size")) and
            asset["size"] <= MAX_APK_BYTES and asset.get("state") == "uploaded",
            "Release asset is missing, unfinished or too large")
    require(isinstance(digest, str) and digest.startswith("sha256:") and
            SHA256.fullmatch(digest[7:]) is not None, "Release asset has no GitHub SHA256 digest")
    return {"id": asset["id"], "name": name, "size": asset["size"], "sha256": digest[7:]}


def release_state(value):
    require(isinstance(value, dict) and positive(value.get("id")) and
            type(value.get("draft")) is bool and type(value.get("prerelease")) is bool and
            isinstance(value.get("tag_name"), str), "Malformed GitHub release state")
    return {key: value[key] for key in ("id", "tag_name", "draft", "prerelease")}


RELEASE_SOURCE_BRANCHES = ("main", "release/stable/8.35")


def publication_source_branch(record):
    branch = record.get("source_branch", "main")
    require(branch in RELEASE_SOURCE_BRANCHES, "Publication record has an unsupported source branch")
    return branch


def publication_route(record):
    branch = publication_source_branch(record)
    trusted_main = record.get("trusted_main")
    if branch != "main":
        require(isinstance(trusted_main, str) and re.fullmatch(r"[0-9a-f]{40}", trusted_main),
                "Maintenance publication requires its trusted main revision")
    else:
        require(trusted_main is None, "Main publication does not select a maintenance policy")
    return branch, trusted_main


def record_for(snapshot, built_on, source_branch="main", trusted_main=None):
    publication_route({"source_branch": source_branch, "trusted_main": trusted_main})
    require(built_on in ("local", "github"), "Unknown build origin")
    route = {"source_branch": source_branch, "trusted_main": trusted_main} if source_branch != "main" else {}
    beta = [asset for asset in snapshot["assets"] if asset["name"] == BETA_NAME]
    return {**route, **({"beta_apk": beta[0]} if beta else {}),
            "schema": 1, "edition": "collie", "repository": REPOSITORY,
            "release_id": snapshot["release"]["id"], "tag_name": snapshot["release"]["tag_name"],
            **{key: snapshot[key] for key in ("tag_object", "source_commit", "package",
                                             "version_name", "version_code", "apk_certificate_sha256")},
            "built_on": built_on,
            "apks": [asset for asset in snapshot["assets"] if asset["name"] in APK_NAMES]}


def validate_record(snapshot):
    record = snapshot["publication"]
    require(isinstance(record, dict), "Prerelease has no structured publication record; legacy releases are not rewritten")
    require(type(record.get("schema")) is int and record["schema"] == 1, "Unsupported publication record schema")
    require({asset["name"] for asset in snapshot["assets"]} - {BETA_NAME, MANIFEST_NAME} == {*APK_NAMES, RECORD_NAME},
            "Published Collie must have all profile copies and its publication record")
    require(record == record_for(snapshot, record.get("built_on"), *publication_route(record)),
            "Publication record differs from the signed tag or exact APK assets")
    return record


def release_manifest(snapshot, canonical):
    return {"schema": 1, "channel": "stable", "tag": snapshot["release"]["tag_name"],
            "source_commit": snapshot["source_commit"], "version_name": snapshot["version_name"],
            "version_code": snapshot["version_code"], "package_id": snapshot["package"],
            "apk_name": canonical["name"], "apk_sha256": canonical["sha256"],
            "native_artifacts_path": "build-inputs/native-artifacts.json"}


class GitHub:
    """The fixed repository is intentional; no arbitrary API hosts or write targets."""

    def api(self, suffix, *, body=None, missing_ok=False):
        command = ["gh", "api", "--hostname", "github.com", "--method", "GET" if body is None else "PATCH",
                   "-H", "Accept: application/vnd.github+json", f"repos/{REPOSITORY}/{suffix}"]
        if body is not None:
            command += ["--input", "-"]
        result = subprocess.run(command, input=None if body is None else json.dumps(body),
                                text=True, capture_output=True)
        if missing_ok and result.returncode and "HTTP 404" in result.stderr:
            return None
        require(result.returncode == 0, "GitHub request failed: " + result.stderr.strip())
        return json.loads(result.stdout, object_pairs_hook=unique_object)

    def collection(self, suffix):
        records, seen = [], set()
        for page in range(1, 1001):
            batch = self.api(f"{suffix}?per_page=100&page={page}")
            require(isinstance(batch, list), "Malformed GitHub collection")
            for item in batch:
                require(isinstance(item, dict) and positive(item.get("id")) and item["id"] not in seen,
                        "Changing or duplicate GitHub collection; retry")
                seen.add(item["id"])
                records.append(item)
            if len(batch) < 100:
                return records
        raise Failure("GitHub collection is too large to verify completely")

    def release(self, tag):
        # The tags endpoint never returns drafts, and publication starts from a draft, so find the tag in
        # the authenticated release list (which includes drafts) and require exactly one release for it.
        tag = stable_tag(tag)
        matches = [release for release in self.releases() if release.get("tag_name") == tag]
        require(len(matches) == 1, f"Expected exactly one GitHub release for {tag}, found {len(matches)}")
        return matches[0]

    def assets(self, release_id):
        return self.collection(f"releases/{release_id}/assets")

    def releases(self):
        return self.collection("releases")

    def latest_id(self):
        release = self.api("releases/latest", missing_ok=True)
        if release is None:
            return None
        state = release_state(release)
        require(not state["draft"] and not state["prerelease"], "Latest is not a full release")
        return state["id"]

    def download(self, asset, output):
        require(positive(asset.get("size")) and asset["size"] <= MAX_APK_BYTES, "Invalid download size")
        command = ["gh", "api", "--hostname", "github.com", "--method", "GET",
                   "-H", "Accept: application/octet-stream",
                   f"repos/{REPOSITORY}/releases/assets/{asset['id']}"]
        with Path(output).open("xb") as stream:
            try:
                with subprocess.Popen(command, stdout=subprocess.PIPE) as request:
                    try:
                        remaining = asset["size"]
                        while chunk := request.stdout.read(min(1_048_576, remaining + 1)):
                            require(len(chunk) <= remaining, "Asset download exceeds declared size")
                            stream.write(chunk)
                            remaining -= len(chunk)
                        require(request.wait() == 0 and remaining == 0, "Asset download failed or was truncated")
                    except BaseException:
                        request.kill()
                        request.wait()
                        raise
            except BaseException:
                Path(output).unlink(missing_ok=True)
                raise

    def upload(self, tag, paths):
        # Never replace an existing asset, including on a resumed draft.
        subprocess.run(["gh", "release", "upload", stable_tag(tag), "--repo", "github.com/" + REPOSITORY,
                        *map(str, paths)], check=True)

    def transition(self, release_id, *, prerelease, body):
        return self.api(f"releases/{release_id}", body={"draft": False, "prerelease": prerelease,
                                                      "make_latest": "false" if prerelease else "true", "body": body})


class Publication:
    def __init__(self, root, scratch, build_tools, github=None):
        self.root, self.scratch, self.build_tools = Path(root).resolve(), Path(scratch).resolve(), Path(build_tools).resolve()
        self.github = github or GitHub()
        self.scratch.mkdir(parents=True, exist_ok=True)
        self.apk_cache = {}
        self.downloaded_bytes = 0

    def run(self, command, *, cwd=None):
        return subprocess.check_output(list(map(str, command)), cwd=cwd or self.root, text=True).strip()

    def signed_tag_identity(self, tag):
        stable_tag(tag)
        self.run(["git", "fetch", "--no-tags", "--depth=1", "origin", "refs/tags/" + tag])
        tag_object = self.run(["git", "rev-parse", "FETCH_HEAD"])
        require(SHA1.fullmatch(tag_object) is not None and self.run(["git", "cat-file", "-t", tag_object]) == "tag",
                "Release must use an annotated signed tag")
        tag_text = self.run(["git", "cat-file", "tag", tag_object])
        header = tag_text.partition("\n\n")[0]
        fields = {}
        for name in ("object", "type", "tag"):
            values = re.findall(r"^" + name + r" (.+)$", header, re.M)
            require(len(values) == 1, "Signed tag must have exactly one " + name + " header")
            fields[name] = values[0]
        require(fields["tag"] == tag, "Signed tag header does not match the requested tag ref")
        require(fields["type"] == "commit" and SHA1.fullmatch(fields["object"]) is not None,
                "Signed release tag must point directly to a commit")
        require(tag_text.count("-----BEGIN SSH SIGNATURE-----") == 1 and
                tag_text.endswith("-----END SSH SIGNATURE-----"), "Release tag must use the approved SSH signing policy")
        self.run(["git", "-c", "gpg.ssh.allowedSignersFile=" + str(self.root / ".github/release-signers"),
                  "verify-tag", tag_object])
        source = self.run(["git", "rev-parse", tag_object + "^{commit}"])
        require(SHA1.fullmatch(source) is not None and fields["object"] == source,
                "Signed tag target differs from the resolved source commit")
        return {"tag_object": tag_object, "source_commit": source}

    def tag_identity(self, tag):
        identity = self.signed_tag_identity(tag)
        source = identity["source_commit"]
        version = version_properties(self.run(["git", "show", source + ":gradle.properties"]), tag)
        self.run(["git", "cat-file", "-e", source + ":fastlane/metadata/android/en-US/changelogs/" +
                  str(version["version_code"]) + ".txt"])
        return {**identity, **version}

    def downloaded(self, asset):
        path = self.scratch / (asset["sha256"] + (".apk" if asset["name"].lower().endswith(".apk") else ".json"))
        if not path.exists():
            require(self.downloaded_bytes + asset["size"] <= MAX_DOWNLOAD_BYTES,
                    "Public history exceeds the download budget; review the history before increasing it")
            self.downloaded_bytes += asset["size"]
            self.github.download(asset, path)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == asset["size"] and
                sha256(path) == asset["sha256"], "Downloaded asset differs from GitHub identity: " + asset["name"])
        return path

    def apk_metadata(self, path, *, signed=True):
        output = self.run([self.build_tools / "aapt2", "dump", "badging", path])
        match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", output, re.M)
        require(match is not None, "Cannot read APK package and version")
        package, code, name = match.groups()
        require(1 <= int(code) <= 2_100_000_000, "Invalid APK versionCode")
        if package == PACKAGE:
            require(re.search(r"^application-debuggable(?:\s|$)", output, re.M) is None,
                    "Collie publication requires a non-debuggable APK")
        if signed:
            certificates = self.run([self.build_tools / "apksigner", "verify", "--print-certs", path])
            digests = set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certificates))
            require(digests, "APK has no verified signing certificate")
            if package == PACKAGE:
                require(digests == {CERTIFICATE}, "Collie APK signing certificate differs")
        return {"package": package, "version_code": int(code), "version_name": name}

    def inspected_apk(self, asset):
        path = self.downloaded(asset)
        if asset["sha256"] not in self.apk_cache:
            self.apk_cache[asset["sha256"]] = self.apk_metadata(path)
        return self.apk_cache[asset["sha256"]]

    def check_beta_apk(self, asset):
        """The Beta APK released alongside Stable: its own package, Beta signer, not debuggable."""
        path = self.downloaded(asset)
        require(self.inspected_apk(asset)["package"] == BETA_PACKAGE,
                "collie-beta.apk must be the TrailBlazer Beta package")
        output = self.run([self.build_tools / "aapt2", "dump", "badging", path])
        require(re.search(r"^application-debuggable(?:\s|$)", output, re.M) is None,
                "collie-beta.apk must not be debuggable")
        certificates = self.run([self.build_tools / "apksigner", "verify", "--print-certs", path])
        require(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certificates)) == {BETA_CERTIFICATE},
                "collie-beta.apk signing certificate differs from the TrailBlazer Beta key")

    def asset_list(self, release_id):
        assets = sorted((asset_identity(value) for value in self.github.assets(release_id)), key=lambda x: x["name"])
        require(len({asset["name"] for asset in assets}) == len(assets), "Duplicate release asset names")
        return assets

    def inspect(self, tag, purpose):
        require(purpose in ("publish", "promote", "full"), "Unknown publication operation")
        stable_tag(tag)
        state = release_state(self.github.release(tag))
        require(state["tag_name"] == tag, "GitHub returned a different tag")
        if purpose == "publish":
            require(state["draft"] or state["prerelease"], "A full release cannot be republished or demoted")
        else:
            require(not state["draft"] and state["prerelease"] is (purpose == "promote"),
                    "Expected a published " + ("prerelease" if purpose == "promote" else "full release"))
        identity = self.tag_identity(tag)
        assets = self.asset_list(state["id"])
        names = {asset["name"] for asset in assets}
        require("collie.apk" in names and names <= {*APK_NAMES, RECORD_NAME, MANIFEST_NAME, BETA_NAME},
                "Only Collie APKs, collie-beta.apk, publication.json and release.json are accepted by this publisher")
        canonical = next(asset for asset in assets if asset["name"] == "collie.apk")
        actual = self.inspected_apk(canonical)
        require(actual == {"package": PACKAGE, "version_name": identity["version_name"],
                           "version_code": identity["version_code"]}, "APK identity differs from signed tag source")
        snapshot = {"schema": 1, "repository": REPOSITORY, "release": state, **identity, "package": PACKAGE,
                    "apk_certificate_sha256": CERTIFICATE, "assets": assets, "publication": None}
        for asset in assets:
            if asset["name"] in APK_NAMES:
                require(asset["sha256"] == canonical["sha256"], "Profile copy differs from collie.apk")
                self.downloaded(asset)
            elif asset["name"] == BETA_NAME:
                self.check_beta_apk(asset)
            elif asset["name"] == RECORD_NAME:
                require(asset["size"] <= 64_000, "Publication record is too large")
                snapshot["publication"] = read_json(self.downloaded(asset))
            elif asset["name"] == MANIFEST_NAME:
                require(asset["size"] <= 64_000, "Release manifest is too large")
                manifest = read_json(self.downloaded(asset))
                require(json.dumps(manifest, sort_keys=True) ==
                        json.dumps(release_manifest(snapshot, canonical), sort_keys=True),
                        "Release manifest differs from the signed tag or canonical APK")
        record = snapshot["publication"]
        if not state["draft"] or record is not None:
            validate_record(snapshot)
        if purpose == "promote":
            require(record["built_on"] == "local", "A GitHub-built APK needs an independent local rebuild")
        return snapshot

    def published_floor(self, candidate, *, promotion):
        """Inspect actual APKs, including legacy profiles; other packages have independent codes."""
        floor = 0
        seen_candidate = False
        for release in self.github.releases():
            state = release_state(release)
            if state["draft"]:
                continue
            if state["id"] == candidate["release"]["id"]:
                require(state["tag_name"] == candidate["release"]["tag_name"], "Candidate release changed in history")
                seen_candidate = True
                continue
            for raw_asset in self.github.assets(state["id"]):
                require(isinstance(raw_asset, dict) and isinstance(raw_asset.get("name"), str),
                        "Malformed historical release asset")
                if not raw_asset["name"].lower().endswith(".apk"):
                    continue
                asset = asset_identity(raw_asset)
                metadata = self.inspected_apk(asset)
                if metadata["package"] != PACKAGE:
                    continue
                code = metadata["version_code"]
                require(code != candidate["version_code"], "VersionCode is already published by another Collie release")
                if not promotion or not state["prerelease"]:
                    floor = max(floor, code)
        require(not promotion or seen_candidate, "Prerelease disappeared from published history")
        require(candidate["version_code"] > floor,
                "VersionCode must exceed " + ("every full Collie release before becoming Latest" if promotion else
                                             "every published Collie release"))
        return floor

    @staticmethod
    def unchanged(expected, actual):
        require(expected == actual, "Release, tag, publication record or APK assets changed; start a fresh verification")

    def publish(self, expected, built_on, notes, source_branch="main", trusted_main=None):
        route = publication_route({"source_branch": source_branch, "trusted_main": trusted_main})
        current = self.inspect(expected["release"]["tag_name"], "publish")
        self.unchanged(expected, current)
        tag = current["release"]["tag_name"]
        if current["publication"] is not None:
            require(publication_route(current["publication"]) == route,
                    "Source branch or trusted policy differs from the existing publication record")
        if not current["release"]["draft"]:
            require(validate_record(current)["built_on"] == built_on, "Published build origin differs")
            return {"status": "ALREADY_PRERELEASE", "identity": current}
        self.published_floor(current, promotion=False)
        latest_before = self.github.latest_id()
        if current["publication"] is not None:
            require(validate_record(current)["built_on"] == built_on, "Draft build origin differs")
        canonical = next(asset for asset in current["assets"] if asset["name"] == "collie.apk")
        upload = []
        for name in APK_NAMES:
            if name not in {asset["name"] for asset in current["assets"]}:
                path = self.scratch / name
                shutil.copyfile(self.downloaded(canonical), path)
                upload.append(path)
        if upload:
            self.github.upload(tag, upload)
        prepared = self.inspect(tag, "publish")
        require({asset["name"] for asset in prepared["assets"] if asset["name"] in APK_NAMES} == set(APK_NAMES),
                "Profile upload did not produce the complete APK set")
        require(prepared["release"] == current["release"] and
                all(asset in prepared["assets"] for asset in current["assets"]), "Draft changed while adding profile copies")
        require(record_for(prepared, built_on, *route) == {**record_for(current, built_on, *route),
                "apks": [asset for asset in prepared["assets"] if asset["name"] in APK_NAMES]},
                "Signed source changed while adding profile copies")
        record = record_for(prepared, built_on, *route)
        upload = []
        if prepared["publication"] is None:
            path = self.scratch / RECORD_NAME
            write_json(path, record)
            upload.append(path)
        if not any(asset["name"] == MANIFEST_NAME for asset in prepared["assets"]):
            path = self.scratch / MANIFEST_NAME
            write_json(path, release_manifest(prepared, canonical))
            upload.append(path)
        if upload:
            self.github.upload(tag, upload)
        before = self.inspect(tag, "publish")
        require(before["release"]["draft"] and before["publication"] == record,
                "Draft changed while recording publication identity")
        require(any(asset["name"] == MANIFEST_NAME for asset in before["assets"]),
                "Draft is missing release.json before publication")
        require(all(asset in before["assets"] for asset in prepared["assets"]), "Release assets changed before publication")
        self.published_floor(before, promotion=False)
        require(self.github.latest_id() == latest_before, "Latest changed during publication; retry")
        body = notes.rstrip() + f"\n\n---\nBuilt on: {built_on}. Reproducibility: not yet verified.\n"
        self.github.transition(before["release"]["id"], prerelease=True, body=body)
        after = self.inspect(tag, "publish")
        self.unchanged({**before, "release": {**before["release"], "draft": False, "prerelease": True}}, after)
        require(self.github.latest_id() == latest_before, "Prerelease unexpectedly changed Latest")
        return {"status": "PRERELEASE_PUBLISHED", "identity": after}

    def promote(self, expected, rebuilt, run_url, source_branch=None):
        require(re.fullmatch(r"https://github\.com/0cwa/collieim-android/actions/runs/[1-9][0-9]*", run_url) is not None,
                "Invalid rebuild workflow URL")
        current = self.inspect(expected["release"]["tag_name"], "promote")
        self.unchanged(expected, current)
        branch = publication_source_branch(current["publication"])
        require(source_branch is None or source_branch == branch,
                "Requested branch differs from the existing publication record")
        rebuilt = Path(rebuilt).absolute()
        require(rebuilt.is_file() and not rebuilt.is_symlink(), "Expected one regular rebuilt APK")
        require(0 < rebuilt.stat().st_size <= MAX_APK_BYTES, "Invalid rebuilt APK size")
        require(self.apk_metadata(rebuilt, signed=False) == {key: current[key] for key in
                ("package", "version_name", "version_code")}, "Rebuilt APK identity differs")
        canonical = next(asset for asset in current["assets"] if asset["name"] == "collie.apk")
        unsigned_digest = sha256(rebuilt)
        self.run([sys.executable, self.root / "tooling/reproducible-builds/apkdiff/apkdiff.py",
                  rebuilt, self.downloaded(canonical)], cwd=self.scratch)
        require(sha256(rebuilt) == unsigned_digest, "Rebuilt APK changed during comparison")
        self.published_floor(current, promotion=True)
        before = self.inspect(current["release"]["tag_name"], "promote")
        self.unchanged(expected, before)
        release = self.github.release(before["release"]["tag_name"])
        require(release_state(release) == before["release"] and isinstance(release.get("body"), str),
                "Release changed before promotion")
        body = release["body"].rstrip() + f"\n\nReproduced on GitHub: {run_url}\n"
        self.github.transition(before["release"]["id"], prerelease=False, body=body)
        after = self.inspect(before["release"]["tag_name"], "full")
        self.unchanged({**before, "release": {**before["release"], "prerelease": False}}, after)
        require(self.github.latest_id() == before["release"]["id"], "Promoted release did not become Latest")
        return {"status": "FULL_RELEASE_PUBLISHED", "identity": after, "rebuild_run": run_url,
                "rebuilt_apk_sha256": unsigned_digest,
                "comparison": "apkdiff ZIP payload comparison; signature-related entries excluded"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "publish", "promote"))
    parser.add_argument("--tag")
    parser.add_argument("--purpose", choices=("publish", "promote"))
    parser.add_argument("--identity", type=Path)
    parser.add_argument("--built-on", choices=("local", "github"))
    parser.add_argument("--source-branch", choices=RELEASE_SOURCE_BRANCHES)
    parser.add_argument("--ci-report", type=Path)
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--rebuilt-directory", type=Path)
    parser.add_argument("--run-url")
    parser.add_argument("--scratch", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        require(not args.output.exists() and not args.output.is_symlink(), "Use a fresh evidence output path")
        helper = Publication(Path(__file__).resolve().parents[2], args.scratch, args.build_tools)
        if args.action == "inspect":
            require(args.tag is not None and args.purpose is not None, "Inspect requires tag and purpose")
            result = helper.inspect(args.tag, args.purpose)
            if result["publication"] is None:
                branch, trusted_main = args.source_branch or "main", None
            else:
                branch, trusted_main = publication_route(result["publication"])
            require(args.source_branch is None or args.source_branch == branch,
                    "Requested branch differs from the existing publication record")
            if args.github_output:
                with args.github_output.open("a") as output:
                    for key in ("source_commit", "version_name", "version_code"):
                        output.write(f"{key}={result[key]}\n")
                    output.write(f"source_branch={branch}\ntrusted_main={trusted_main or ''}\n")
        elif args.action == "publish":
            require(args.identity is not None and args.notes is not None and args.built_on is not None,
                    "Publish requires identity, build origin and release notes")
            expected = read_json(args.identity)
            branch = args.source_branch or "main"
            trusted_main = None
            if args.ci_report:
                approved = read_json(args.ci_report)
                require(approved["source_commit"] == expected["source_commit"] and approved["source_branch"] == branch and
                        approved["status"] == "EXACT_COMMIT_CI_PASSED",
                        "Approved CI identity differs from the draft")
                trusted_main = approved["trusted_main"]
            result = helper.publish(expected, args.built_on, args.notes.read_text(), branch, trusted_main)
        else:
            require(args.identity is not None and args.rebuilt_directory is not None and args.run_url is not None,
                    "Promote requires identity, rebuilt APK directory and run URL")
            apks = list(args.rebuilt_directory.glob("*.apk"))
            require(len(apks) == 1, "Expected exactly one rebuilt APK")
            result = helper.promote(read_json(args.identity), apks[0], args.run_url, args.source_branch)
        write_json(args.output, result)
        print(result.get("status", "RELEASE_IDENTITY_VERIFIED"))
    except (Failure, OSError, KeyError, TypeError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print("Release publication guard failed: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
