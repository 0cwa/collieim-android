"""Release boundary tests; GitHub and Android tool seams use synthetic APKs.

The signature tests use real local Git/SSH keys, and payload comparisons execute
the actual apkdiff helper. No test signs an Android release or contacts GitHub.
"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import publication as p

ROOT = Path(__file__).resolve().parents[2]
TAG = "v8.35.1-4-rc1"
NAME = TAG[1:]
CODE = 177706
RUN = "https://github.com/0cwa/collieim-android/actions/runs/123"


def apk(*, package=p.PACKAGE, code=CODE, name=NAME, certificate=p.CERTIFICATE,
        payload=b"app payload", signed=True, debuggable=False):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for path, data in (("fixture.json", json.dumps({"package": package, "code": code,
                                                      "name": name, "debuggable": debuggable}).encode()),
                           ("classes.dex", payload)):
            archive.writestr(zipfile.ZipInfo(path, (2020, 1, 1, 0, 0, 0)), data)
        if signed:
            archive.writestr(zipfile.ZipInfo("META-INF/fixture.RSA", (2020, 1, 1, 0, 0, 0)), certificate)
    return output.getvalue()


class FakeGitHub:
    def __init__(self):
        self.rows, self.files, self.writes = {}, {}, []
        self.next_asset = 1000
        self.latest = None
        self.fail_transition = False
        self.corrupt_download = False

    def add(self, release_id, tag, data, *, draft=False, prerelease=False):
        self.rows[release_id] = {"id": release_id, "tag_name": tag, "draft": draft,
                                 "prerelease": prerelease, "body": "Release notes", "assets": []}
        self.add_asset(release_id, "collie.apk", data)
        if not draft and not prerelease:
            self.latest = release_id

    def add_asset(self, release_id, name, data):
        self.next_asset += 1
        value = {"id": self.next_asset, "name": name, "size": len(data), "state": "uploaded",
                 "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
        self.rows[release_id]["assets"].append(value)
        self.files[value["id"]] = data
        return value

    def release(self, tag):
        return copy.deepcopy(next(row for row in self.rows.values() if row["tag_name"] == tag))

    def assets(self, release_id):
        return copy.deepcopy(self.rows[release_id]["assets"])

    def releases(self):
        return copy.deepcopy(list(self.rows.values()))

    def latest_id(self):
        return self.latest

    def download(self, asset, path):
        Path(path).write_bytes(self.files[asset["id"]] + (b"corruption" if self.corrupt_download else b""))

    def upload(self, tag, paths):
        row = next(row for row in self.rows.values() if row["tag_name"] == tag)
        if not row["draft"]:
            raise AssertionError("attempted an upload to an immutable published release")
        for path in paths:
            if path.name in {asset["name"] for asset in row["assets"]}:
                raise AssertionError("attempted to replace an existing asset")
            self.add_asset(row["id"], path.name, path.read_bytes())
        self.writes.append(("upload", [path.name for path in paths]))

    def transition(self, release_id, *, prerelease, body):
        if self.fail_transition:
            self.fail_transition = False
            raise p.Failure("simulated interrupted transition")
        self.rows[release_id].update(draft=False, prerelease=prerelease, body=body)
        self.writes.append(("transition", release_id, prerelease))
        if not prerelease:
            self.latest = release_id


class Harness(p.Publication):
    def __init__(self, scratch, github):
        super().__init__(ROOT, scratch, Path("/synthetic-tools"), github)
        self.tag = {"tag_object": "a" * 40, "source_commit": "b" * 40,
                    "version_name": NAME, "version_code": CODE}
        self.after_compare = None
        self.android_reads = 0

    def tag_identity(self, tag):
        p.stable_tag(tag)
        return dict(self.tag)

    def run(self, command, *, cwd=None):
        if Path(command[0]).name == "aapt2":
            self.android_reads += 1
            with zipfile.ZipFile(command[-1]) as archive:
                item = json.loads(archive.read("fixture.json"))
            return (f"package: name='{item['package']}' versionCode='{item['code']}' versionName='{item['name']}'" +
                    ("\napplication-debuggable" if item["debuggable"] else ""))
        if Path(command[0]).name == "apksigner":
            with zipfile.ZipFile(command[-1]) as archive:
                certificate = archive.read("META-INF/fixture.RSA").decode()
            return "Signer #1 certificate SHA-256 digest: " + certificate
        result = super().run(command, cwd=cwd)
        if "apkdiff.py" in str(command[1]) and self.after_compare:
            callback, self.after_compare = self.after_compare, None
            callback()
        return result


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.github = FakeGitHub()
        self.github.add(10, "v8.35.1-3-rc2", apk(code=177705, name="8.35.1-3-rc2"))
        self.github.add(20, TAG, apk(), draft=True)
        self.helper = Harness(self.root / "scratch", self.github)

    def inspect(self, purpose="publish"):
        return self.helper.inspect(TAG, purpose)

    def publish(self, built_on="local"):
        return self.helper.publish(self.inspect(), built_on, "Human release notes")

    def rebuilt(self, **kwargs):
        path = self.root / "unsigned.apk"
        path.write_bytes(apk(signed=False, **kwargs))
        return path

    def test_first_release_has_no_circular_history_or_record_requirement(self):
        del self.github.rows[10]
        self.github.latest = None
        result = self.publish()
        self.assertEqual(result["status"], "PRERELEASE_PUBLISHED")
        self.assertIsNone(self.github.latest)

    def test_debuggable_signed_candidate_and_unsigned_rebuild_are_rejected(self):
        self.github.rows[20]["assets"] = []
        self.github.add_asset(20, "collie.apk", apk(debuggable=True))
        with self.assertRaisesRegex(p.Failure, "non-debuggable"):
            self.publish()
        self.assertEqual(self.github.writes, [])
        self.github.rows[20]["assets"] = []
        self.github.add_asset(20, "collie.apk", apk())
        self.publish()
        writes = list(self.github.writes)
        with self.assertRaisesRegex(p.Failure, "non-debuggable"):
            self.helper.promote(self.inspect("promote"), self.rebuilt(debuggable=True), RUN)
        self.assertEqual(self.github.writes, writes)

    def test_download_budget_stops_large_history_before_writes(self):
        limit = len(self.github.files[self.github.rows[20]["assets"][0]["id"]])
        with patch.object(p, "MAX_DOWNLOAD_BYTES", limit):
            with self.assertRaisesRegex(p.Failure, "download budget"):
                self.publish()
        self.assertEqual(self.github.writes, [])

    def test_unrelated_historical_assets_do_not_block_package_floor(self):
        self.github.rows[10]["assets"].append({"id": 900, "name": "old-manifest.json", "digest": None})
        self.publish()
        self.assertTrue(self.github.rows[20]["prerelease"])

    def test_incomplete_profile_upload_never_records_or_publishes(self):
        with patch.object(self.github, "upload"):
            with self.assertRaisesRegex(p.Failure, "complete APK set"):
                self.publish()
        self.assertTrue(self.github.rows[20]["draft"])
        self.assertEqual(self.github.writes, [])

    def test_publish_adds_identical_aliases_and_record_without_changing_latest(self):
        result = self.publish()
        self.assertEqual(self.github.latest, 10)
        self.assertTrue(self.github.rows[20]["prerelease"])
        self.assertEqual({asset["name"] for asset in result["identity"]["assets"]}, {*p.APK_NAMES, p.RECORD_NAME})
        record = result["identity"]["publication"]
        self.assertEqual(record["release_id"], 20)
        self.assertEqual(record["source_commit"], self.helper.tag["source_commit"])
        self.assertEqual(len({asset["sha256"] for asset in record["apks"]}), 1)
        self.assertEqual(record["built_on"], "local")

    def test_published_retry_is_read_only_and_never_demotes_full_release(self):
        self.publish()
        writes = list(self.github.writes)
        self.assertEqual(self.publish()["status"], "ALREADY_PRERELEASE")
        self.assertEqual(self.github.writes, writes)
        self.github.rows[20]["prerelease"] = False
        with self.assertRaisesRegex(p.Failure, "cannot be republished"):
            self.publish()
        self.assertEqual(self.github.writes, writes)

    def test_interrupted_draft_resumes_without_replacing_assets(self):
        self.github.fail_transition = True
        with self.assertRaisesRegex(p.Failure, "interrupted"):
            self.publish()
        identities = self.github.assets(20)
        self.publish()
        self.assertEqual(self.github.assets(20), identities)
        self.assertEqual(sum(write[0] == "upload" for write in self.github.writes), 2)

    def test_changed_draft_origin_cannot_overwrite_existing_record(self):
        self.github.fail_transition = True
        with self.assertRaises(p.Failure):
            self.publish("github")
        writes = list(self.github.writes)
        with self.assertRaisesRegex(p.Failure, "origin differs"):
            self.publish("local")
        self.assertEqual(writes, self.github.writes)

    def test_beta_and_unsafe_tags_are_rejected_before_remote_reads(self):
        for tag in ("trailblazer-v8.35.1", "main", "v8.35.1/other", "v8.35.1..other", "v-flag", "v8.35.1\nother"):
            with self.subTest(tag=tag), patch.object(self.github, "release") as remote:
                with self.assertRaises(p.Failure):
                    self.helper.inspect(tag, "publish")
                remote.assert_not_called()

    def test_foreign_package_and_wrong_certificate_never_reach_writes(self):
        for changes in ({"package": "im.collie.app.trailblazer"}, {"certificate": "c" * 64}):
            with self.subTest(changes=changes):
                self.github.rows[20]["assets"] = []
                self.github.add_asset(20, "collie.apk", apk(**changes))
                with self.assertRaises(p.Failure):
                    self.publish()
                self.assertEqual(self.github.writes, [])

    def test_wrong_apk_version_fails(self):
        self.github.rows[20]["assets"] = []
        self.github.add_asset(20, "collie.apk", apk(code=CODE + 1))
        with self.assertRaisesRegex(p.Failure, "identity differs"):
            self.publish()
        self.assertFalse(self.github.writes)

    def test_beta_asset_or_different_profile_copy_fails(self):
        for name in ("trailblazer-beta.apk", "molly-minimal.apk", p.BETA_NAME):
            with self.subTest(name=name):
                extra = self.github.add_asset(20, name, apk(payload=b"other"))
                with self.assertRaises(p.Failure):
                    self.publish()
                self.github.rows[20]["assets"].remove(extra)
        self.assertFalse(self.github.writes)

    def beta_apk(self, **changes):
        return apk(**{"package": p.BETA_PACKAGE, "certificate": p.BETA_CERTIFICATE,
                      "code": 178201, "name": "8.36.3-1-beta1", "payload": b"beta payload", **changes})

    def test_wrong_beta_certificate_or_debuggable_beta_never_reach_writes(self):
        for changes in ({"certificate": p.CERTIFICATE}, {"debuggable": True}):
            with self.subTest(changes=changes):
                extra = self.github.add_asset(20, p.BETA_NAME, self.beta_apk(**changes))
                with self.assertRaises(p.Failure):
                    self.publish()
                self.github.rows[20]["assets"].remove(extra)
        self.assertFalse(self.github.writes)

    def test_beta_apk_alongside_is_recorded_and_survives_promotion(self):
        self.github.add_asset(20, p.BETA_NAME, self.beta_apk())
        result = self.publish()
        names = {asset["name"] for asset in result["identity"]["assets"]}
        self.assertEqual(names, {*p.APK_NAMES, p.RECORD_NAME, p.BETA_NAME})
        record = result["identity"]["publication"]
        self.assertEqual(record["beta_apk"]["name"], p.BETA_NAME)
        self.assertNotIn(p.BETA_NAME, {asset["name"] for asset in record["apks"]})
        result = self.helper.promote(self.inspect("promote"), self.rebuilt(), RUN)
        self.assertEqual(result["status"], "FULL_RELEASE_PUBLISHED")

    def test_missing_digest_duplicate_name_and_wrong_download_fail(self):
        original = copy.deepcopy(self.github.rows[20]["assets"])
        self.github.rows[20]["assets"][0]["digest"] = None
        with self.assertRaisesRegex(p.Failure, "SHA256"):
            self.inspect()
        self.github.rows[20]["assets"] = copy.deepcopy(original + original)
        with self.assertRaisesRegex(p.Failure, "Duplicate"):
            self.inspect()
        self.github.rows[20]["assets"] = original
        self.github.corrupt_download = True
        with self.assertRaisesRegex(p.Failure, "Downloaded asset differs"):
            self.inspect()
        self.assertFalse(self.github.writes)

    def test_draft_asset_substitution_after_inspection_fails_before_writes(self):
        expected = self.inspect()
        self.github.rows[20]["assets"] = []
        self.github.add_asset(20, "collie.apk", apk(payload=b"substituted"))
        with self.assertRaisesRegex(p.Failure, "changed"):
            self.helper.publish(expected, "local", "notes")
        self.assertFalse(self.github.writes)

    def test_recreated_release_and_moved_tag_fail_before_writes(self):
        expected = self.inspect()
        self.helper.tag["tag_object"] = "c" * 40
        with self.assertRaisesRegex(p.Failure, "changed"):
            self.helper.publish(expected, "local", "notes")
        self.helper.tag["tag_object"] = "a" * 40
        self.github.rows[21] = self.github.rows.pop(20)
        self.github.rows[21]["id"] = 21
        with self.assertRaisesRegex(p.Failure, "changed"):
            self.helper.publish(expected, "local", "notes")
        self.assertFalse(self.github.writes)

    def test_publication_floor_includes_prereleases_and_legacy_profile_apks(self):
        self.github.add(30, "v8.35.2-1-rc1", apk(code=CODE + 1, name="8.35.2-1-rc1"), prerelease=True)
        with self.assertRaisesRegex(p.Failure, "every published"):
            self.publish()
        del self.github.rows[30]
        self.github.add_asset(10, "molly-minimal.apk", apk(code=CODE + 1))
        with self.assertRaisesRegex(p.Failure, "every published"):
            self.publish()
        self.assertFalse(self.github.writes)

    def test_same_code_is_not_reusable_even_if_payload_matches(self):
        self.github.add(30, "vother-legacy", apk(), prerelease=True)
        with self.assertRaisesRegex(p.Failure, "already published"):
            self.publish()
        self.assertFalse(self.github.writes)

    def test_beta_package_has_an_independent_version_history(self):
        self.github.add(30, "trailblazer-v8.36.0", apk(package="im.collie.app.trailblazer",
                        code=999999, name="8.36.0", certificate="c" * 64), prerelease=True)
        self.assertEqual(self.publish()["status"], "PRERELEASE_PUBLISHED")

    def test_maintenance_publication_rejects_switching_to_main(self):
        branch = "release/stable/8.35"
        policy = "a" * 40
        result = self.helper.publish(self.inspect(), "local", "Human release notes", branch, policy)
        self.assertEqual(p.publication_route(result["identity"]["publication"]), (branch, policy))
        snapshot = self.inspect("promote")
        with self.assertRaisesRegex(p.Failure, "Requested branch differs"):
            self.helper.promote(snapshot, self.rebuilt(), RUN, source_branch="main")
        self.assertEqual(self.inspect("promote"), snapshot)
        with self.assertRaisesRegex(p.Failure, "Source branch or trusted policy differs"):
            self.helper.publish(snapshot, "local", "Human release notes", "main")
        with self.assertRaisesRegex(p.Failure, "trusted policy differs"):
            self.helper.publish(snapshot, "local", "Human release notes", branch, "b" * 40)
        result = self.helper.promote(snapshot, self.rebuilt(), RUN, source_branch=branch)
        self.assertEqual(p.publication_route(result["identity"]["publication"]), (branch, policy))

    def test_historical_record_keeps_main_route_without_rewriting_record(self):
        result = self.publish()
        record = result["identity"]["publication"]
        self.assertNotIn("source_branch", record)
        self.assertEqual(p.publication_source_branch(record), "main")
        self.assertEqual(self.inspect("promote")["publication"], record)

    def test_successful_promotion_keeps_every_immutable_asset(self):
        self.publish()
        expected = self.inspect("promote")
        assets, writes = self.github.assets(20), len(self.github.writes)
        result = self.helper.promote(expected, self.rebuilt(), RUN)
        self.assertEqual(result["status"], "FULL_RELEASE_PUBLISHED")
        self.assertEqual(self.github.latest, 20)
        self.assertEqual(self.github.assets(20), assets)
        self.assertEqual(self.github.writes[writes:], [("transition", 20, False)])
        self.assertEqual(result["identity"]["publication"], expected["publication"])

    def test_notes_cannot_change_structured_build_origin(self):
        self.publish("github")
        self.github.rows[20]["body"] = "Built on: local. Reproducibility: not yet verified."
        with self.assertRaisesRegex(p.Failure, "independent local"):
            self.inspect("promote")

    def test_legacy_prerelease_is_not_modified_to_invent_a_publication_record(self):
        self.github.rows[20].update(draft=False, prerelease=True, body="Built on: local.")
        with self.assertRaisesRegex(p.Failure, "legacy releases are not rewritten"):
            self.inspect("promote")
        self.assertFalse(self.github.writes)

    def test_bad_rebuild_and_wrong_identity_never_promote(self):
        self.publish()
        expected = self.inspect("promote")
        writes = list(self.github.writes)
        with self.assertRaises(subprocess.CalledProcessError):
            self.helper.promote(expected, self.rebuilt(payload=b"different"), RUN)
        with self.assertRaisesRegex(p.Failure, "Rebuilt APK identity"):
            self.helper.promote(expected, self.rebuilt(code=CODE + 1), RUN)
        self.assertEqual(self.github.writes, writes)

    def test_signed_tag_and_assets_are_checked_again_after_rebuild(self):
        self.publish()
        expected = self.inspect("promote")
        writes = list(self.github.writes)
        self.helper.after_compare = lambda: self.helper.tag.update(tag_object="d" * 40)
        with self.assertRaisesRegex(p.Failure, "Publication record differs"):
            self.helper.promote(expected, self.rebuilt(), RUN)
        self.assertEqual(self.github.writes, writes)

    def test_same_bytes_reuploaded_under_new_asset_id_cannot_pass_promotion(self):
        self.publish()
        expected = self.inspect("promote")
        writes = list(self.github.writes)

        def replace_asset():
            self.github.rows[20]["assets"] = [a for a in self.github.rows[20]["assets"] if a["name"] != "collie.apk"]
            self.github.add_asset(20, "collie.apk", apk())

        self.helper.after_compare = replace_asset
        with self.assertRaisesRegex(p.Failure, "Publication record differs"):
            self.helper.promote(expected, self.rebuilt(), RUN)
        self.assertEqual(self.github.writes, writes)

    def test_newer_full_release_prevents_older_latest_even_after_rebuild(self):
        self.publish()
        expected = self.inspect("promote")
        writes = list(self.github.writes)
        self.helper.after_compare = lambda: self.github.add(30, "v8.35.2", apk(code=CODE + 1, name="8.35.2"))
        with self.assertRaisesRegex(p.Failure, "every full"):
            self.helper.promote(expected, self.rebuilt(), RUN)
        self.assertEqual(self.github.latest, 30)
        self.assertEqual(self.github.writes, writes)

    def test_newer_prerelease_does_not_force_stable_to_publish_that_candidate(self):
        self.publish()
        self.github.add(30, "v8.35.2-1-rc1", apk(code=CODE + 1, name="8.35.2-1-rc1"), prerelease=True)
        self.helper.promote(self.inspect("promote"), self.rebuilt(), RUN)
        self.assertEqual(self.github.latest, 20)

    def test_substituted_record_cannot_authorize_promotion(self):
        self.publish()
        expected = self.inspect("promote")
        record = next(a for a in self.github.rows[20]["assets"] if a["name"] == p.RECORD_NAME)
        self.github.rows[20]["assets"].remove(record)
        changed = {**expected["publication"], "source_commit": "d" * 40}
        self.github.add_asset(20, p.RECORD_NAME, json.dumps(changed).encode())
        with self.assertRaisesRegex(p.Failure, "Publication record differs"):
            self.helper.promote(expected, self.rebuilt(), RUN)


class ParsingTests(unittest.TestCase):
    def test_stable_tags_match_the_updater_and_feed_version_grammar(self):
        for tag in ("v8.35.2", "v8.35.2-4", "v8.35.2-alpha1", "v8.35.2-beta2", "v8.35.2-4-rc1"):
            self.assertEqual(p.stable_tag(tag), tag)
            props = f"releaseVersionName={tag[1:]}\nreleaseVersionCode={CODE}\n"
            self.assertEqual(p.version_properties(props, tag)["version_name"], tag[1:])
        for tag in ("v8.35.2-dev", "v8.35.2-SNAPSHOT", "v8.35.2-custom", "v8.35.2+build",
                    "v8.35.2-4-rc1+build", "v8.35.2-rc", "v8.35.2-rc.1", "v8.35.2-rc1-extra"):
            with self.subTest(tag=tag):
                github = Mock()
                with tempfile.TemporaryDirectory() as scratch:
                    helper = Harness(Path(scratch), github)
                    with self.assertRaises(p.Failure):
                        helper.inspect(tag, "publish")
                github.release.assert_not_called()

    def test_asset_stream_enforces_declared_length_and_removes_incomplete_output(self):
        github = p.GitHub()
        with tempfile.TemporaryDirectory() as directory:
            for data, accepted in ((b"abcd", True), (b"abc", False), (b"abcde", False)):
                output = Path(directory) / "asset.apk"
                process = Mock()
                process.__enter__ = Mock(return_value=process)
                process.__exit__ = Mock(return_value=False)
                process.stdout = io.BytesIO(data)
                process.wait.return_value = 0
                with patch.object(p.subprocess, "Popen", return_value=process):
                    if accepted:
                        github.download({"id": 1, "size": 4}, output)
                        self.assertEqual(output.read_bytes(), data)
                        output.unlink()
                        process.kill.assert_not_called()
                    else:
                        with self.assertRaises(p.Failure):
                            github.download({"id": 1, "size": 4}, output)
                        self.assertFalse(output.exists())
                        process.kill.assert_called_once()

    def test_source_version_is_exact_and_unique(self):
        valid = f"releaseVersionName={NAME}\nreleaseVersionCode={CODE}\n"
        self.assertEqual(p.version_properties(valid, TAG)["version_code"], CODE)
        for invalid in (valid + f"releaseVersionCode={CODE}\n", valid.replace(str(CODE), "0"),
                        valid.replace(NAME, "8.35.2"), valid.replace(str(CODE), "2147483648")):
            with self.assertRaises(p.Failure):
                p.version_properties(invalid, TAG)

    def test_duplicate_record_keys_are_rejected(self):
        with self.assertRaises(p.Failure):
            json.loads('{"built_on":"github","built_on":"local"}', object_pairs_hook=p.unique_object)

    def test_collection_reads_every_page_and_rejects_duplicate_ids(self):
        github = p.GitHub()
        with patch.object(github, "api", side_effect=[[{"id": i + 1} for i in range(100)], [{"id": 101}]]) as api:
            self.assertEqual(len(github.collection("releases")), 101)
            self.assertIn("page=2", api.call_args.args[0])
        with patch.object(github, "api", side_effect=[[{"id": i + 1} for i in range(100)], [{"id": 1}]]):
            with self.assertRaisesRegex(p.Failure, "duplicate"):
                github.collection("releases")

    def test_transition_writes_exact_release_id_and_explicit_latest_policy(self):
        github = p.GitHub()
        with patch.object(github, "api") as api:
            github.transition(123, prerelease=True, body="notes")
            self.assertEqual(api.call_args.args, ("releases/123",))
            self.assertEqual(api.call_args.kwargs["body"], {"draft": False, "prerelease": True,
                                                          "make_latest": "false", "body": "notes"})
            github.transition(123, prerelease=False, body="verified")
            self.assertEqual(api.call_args.kwargs["body"]["make_latest"], "true")


@unittest.skipUnless(shutil.which("git") and shutil.which("ssh-keygen"), "Git and SSH tools required")
class SignedTagTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        temporary_environment = patch.dict(os.environ, {"TMPDIR": str(self.root)})
        temporary_environment.start()
        self.addCleanup(temporary_environment.stop)
        self.source, self.reader = self.root / "source", self.root / "reader"
        self.source.mkdir()
        self.reader.mkdir()
        self.key = self.root / "fixture-signing-key"
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(self.key)], check=True)
        self.git(self.source, "init", "-q")
        self.git(self.source, "config", "user.name", "Release fixture")
        self.git(self.source, "config", "user.email", "fixture@example.invalid")
        self.git(self.source, "config", "gpg.format", "ssh")
        self.git(self.source, "config", "user.signingKey", str(self.key))
        (self.source / "gradle.properties").write_text(f"releaseVersionName={NAME}\nreleaseVersionCode={CODE}\n")
        changelog = self.source / f"fastlane/metadata/android/en-US/changelogs/{CODE}.txt"
        changelog.parent.mkdir(parents=True)
        changelog.write_text("Fixture change\n")
        self.git(self.source, "add", ".")
        self.git(self.source, "-c", "commit.gpgSign=false", "commit", "-qm", "Local fixture")
        self.git(self.source, "tag", "-s", "-m", "Fixture release", TAG)
        self.git(self.reader, "init", "-q")
        self.git(self.reader, "remote", "add", "origin", str(self.source))
        signers = self.reader / ".github/release-signers"
        signers.parent.mkdir()
        signers.write_text('fixture@example.invalid namespaces="git" ' + self.key.with_suffix(".pub").read_text())
        self.helper = p.Publication(self.reader, self.root / "scratch", self.root / "unused")

    def git(self, directory, *args):
        return subprocess.check_output(["git", "-C", str(directory), *args], stderr=subprocess.PIPE, text=True).strip()

    def test_actual_approved_ssh_tag_binds_exact_source_and_version(self):
        result = self.helper.tag_identity(TAG)
        self.assertEqual(result["source_commit"], self.git(self.source, "rev-parse", "HEAD"))
        self.assertEqual(result["tag_object"], self.git(self.source, "rev-parse", TAG))
        self.assertEqual(result["version_code"], CODE)

    def test_lightweight_and_unsigned_annotated_tags_are_rejected(self):
        for annotated in (False, True):
            self.git(self.source, "tag", "-d", TAG)
            args = ("tag", "-a", "-m", "Unsigned", TAG) if annotated else ("tag", TAG)
            self.git(self.source, "-c", "tag.gpgSign=false", *args)
            with self.assertRaises(p.Failure):
                self.helper.tag_identity(TAG)

    def test_approved_signed_tag_cannot_be_aliased_under_another_release_name(self):
        other_tag = "v8.35.1-4-rc2"
        self.git(self.source, "tag", "-s", "-m", "Signed for a different release name", other_tag)
        other_object = self.git(self.source, "rev-parse", other_tag)
        signers = str(self.reader / ".github/release-signers")
        self.git(self.source, "-c", "gpg.ssh.allowedSignersFile=" + signers, "verify-tag", other_object)
        # Source version still matches TAG, but the signed object's own name does not.
        self.git(self.source, "update-ref", "refs/tags/" + TAG, other_object)
        github = FakeGitHub()
        github.add(20, TAG, apk(), draft=True)
        self.helper.github = github
        with self.assertRaisesRegex(p.Failure, "Signed tag header"):
            self.helper.inspect(TAG, "publish")
        self.assertEqual(github.writes, [])

    def test_signed_release_tag_must_directly_name_the_source_commit(self):
        inner_tag = "v8.35.1-4-rc2"
        self.git(self.source, "tag", "-s", "-m", "Inner tag", inner_tag)
        self.git(self.source, "tag", "-f", "-s", "-m", "Nested tag", TAG, inner_tag)
        with self.assertRaisesRegex(p.Failure, "point directly to a commit"):
            self.helper.tag_identity(TAG)

    def test_other_signer_cannot_use_a_valid_signature(self):
        other = self.root / "other-key"
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(other)], check=True)
        (self.reader / ".github/release-signers").write_text('fixture@example.invalid namespaces="git" ' +
                                                           other.with_suffix(".pub").read_text())
        with self.assertRaises(subprocess.CalledProcessError):
            self.helper.tag_identity(TAG)


class PromotionWorkflowTests(unittest.TestCase):
    def test_failed_job_retry_uses_the_original_identity_artifact_id(self):
        workflow = (ROOT / '.github/workflows/promote.yml').read_text()
        check, final = workflow.split('  compare-and-promote:', 1)
        self.assertIn('identity_artifact_id: ${{ steps.identity-artifact.outputs.artifact-id }}', check)
        self.assertIn('- id: identity-artifact\n        uses: actions/upload-artifact@', check)
        self.assertIn('needs: [check, build]', final)
        self.assertIn('artifact-ids: ${{ needs.check.outputs.identity_artifact_id }}', final)
        self.assertNotIn('name: promotion-identity-${{ github.run_id }}-${{ github.run_attempt }}', final)

    def test_promotion_uses_publication_route_for_both_ci_checks(self):
        workflow = (ROOT / ".github/workflows/promote.yml").read_text()
        check, final = workflow.split("  compare-and-promote:", 1)
        self.assertNotIn("inputs.source_branch", workflow)
        self.assertIn("source_branch: ${{ steps.identity.outputs.source_branch }}", check)
        self.assertIn("SOURCE_BRANCH: ${{ steps.identity.outputs.source_branch }}", check)
        self.assertIn("SOURCE_BRANCH: ${{ needs.check.outputs.source_branch }}", final)
        self.assertIn("TRUSTED_MAIN: ${{ steps.identity.outputs.trusted_main }}", check)
        self.assertIn("TRUSTED_MAIN: ${{ needs.check.outputs.trusted_main }}", final)
        self.assertIn('cmp "$RUNNER_TEMP/promotion-evidence/ci.json" "$RUNNER_TEMP/promotion-evidence/final-ci.json"', final)

    def test_prerelease_compares_draft_bytes_before_mutation(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        publish, verify = workflow.split("  verify:", 1)
        self.assertIn("--allow-draft --tag", publish)
        self.assertIn("--bound-build", publish)
        self.assertIn('result=$(env -u GH_TOKEN bash', publish)
        self.assertLess(publish.index("verify-apk-match.sh"), publish.index("publication.py publish"))
        self.assertIn("inputs.verify_only", verify)
        self.assertIn("--legacy-build-run", verify)

    def test_final_promotion_refreshes_exact_source_ci_after_rebuild(self):
        workflow = (ROOT / '.github/workflows/promote.yml').read_text()
        final = workflow.split('  compare-and-promote:', 1)[1]
        self.assertIn('actions: read', final)
        self.assertIn('SOURCE_COMMIT: ${{ needs.check.outputs.source_commit }}', final)
        self.assertIn('verify_release_readiness.py --repository "$GH_REPO" --sha "$SOURCE_COMMIT"', final)
        self.assertIn('--report "$RUNNER_TEMP/promotion-evidence/final-ci.json"', final)
        self.assertLess(final.index('verify_release_readiness.py'), final.index('publication.py promote'))


class GitHubReleaseLookupTests(unittest.TestCase):
    """The real lookup, with only the gh API transport replaced."""

    def lookup(self, rows):
        github = p.GitHub()
        calls = []

        def api(suffix, **_):
            calls.append(suffix)
            require_page = suffix.startswith("releases?per_page=100&page=")
            self.assertTrue(require_page, suffix)
            return copy.deepcopy(rows) if suffix.endswith("page=1") else []

        github.api = api
        return github, calls

    def test_draft_release_is_found_without_the_tags_endpoint(self):
        github, calls = self.lookup([{"id": 7, "tag_name": TAG, "draft": True, "prerelease": False},
                                     {"id": 6, "tag_name": "v8.35.1-3-rc2", "draft": False, "prerelease": False}])
        self.assertEqual(github.release(TAG)["id"], 7)
        self.assertFalse(any("releases/tags/" in call for call in calls))

    def test_missing_or_duplicate_release_is_rejected(self):
        for rows in ([], [{"id": 7, "tag_name": TAG}, {"id": 8, "tag_name": TAG}]):
            github, _ = self.lookup(rows)
            with self.assertRaisesRegex(p.Failure, "exactly one GitHub release"):
                github.release(TAG)


if __name__ == "__main__":
    unittest.main()
