"""Focused tests for the fork footprint CLI."""

import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


SCRIPT = Path(__file__).with_name("fork_footprint.py")
spec = importlib.util.spec_from_file_location("fork_footprint", SCRIPT)
fp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fp)


class ForkFootprintTests(unittest.TestCase):
    @staticmethod
    def manifest_rows(out, parts):
        return [row for name in parts for row in json.loads((out / name).read_text())]

    class Response(io.BytesIO):
        def __init__(self, payload, headers=None):
            super().__init__(json.dumps(payload).encode())
            self.headers = headers or {}

        def __enter__(self):
            return self

        def __exit__(self, *args):
            self.close()

    def github_env(self):
        return patch.dict("os.environ", {"GITHUB_TOKEN": "test", "GITHUB_REPOSITORY": "owner/repo",
                                         "PR_NUMBER": "7"})

    def test_sticky_comment_checks_live_shas_and_updates_only_own_bot_comment(self):
        pr = {"head": {"sha": "head-full-sha"}, "base": {"sha": "base-full-sha"}}
        comments = [
            {"id": 1, "body": "<!-- collie-fork-footprint --> old", "user": {"type": "Bot", "login": "other[bot]"}},
            {"id": 2, "body": "<!-- collie-fork-footprint --> old", "user": {"type": "Bot", "login": "github-actions[bot]"}},
        ]
        requests = []
        def open_request(req, timeout):
            requests.append(req)
            if req.get_method() == "GET" and "/pulls/" in req.full_url:
                return self.Response(pr)
            if req.get_method() == "GET":
                return self.Response(comments)
            return self.Response({"id": 2})
        with self.github_env(), patch.object(fp, "urlopen", side_effect=open_request):
            result = fp.sticky_comment("full report", "base-full-sha", "head-full-sha")
        self.assertEqual(result["id"], 2)
        self.assertEqual([req.get_method() for req in requests], ["GET", "GET", "GET", "PATCH"])
        self.assertTrue(requests[-1].full_url.endswith("/issues/comments/2"))

    def test_sticky_comment_rechecks_revisions_immediately_before_write(self):
        current = {"head": {"sha": "head-full-sha"}, "base": {"sha": "base-full-sha"}}
        pull_reads = 0
        requests = []
        def open_request(req, timeout):
            nonlocal pull_reads
            requests.append(req)
            if "/pulls/" in req.full_url:
                pull_reads += 1
                if pull_reads == 2:
                    current["head"]["sha"] = "new-head"
                return self.Response(current)
            return self.Response([])
        with self.github_env(), patch.object(fp, "urlopen", side_effect=open_request):
            self.assertIsNone(fp.sticky_comment("report", "base-full-sha", "head-full-sha"))
        self.assertEqual([req.get_method() for req in requests], ["GET", "GET", "GET"])

    def test_redirect_rejects_destinations_outside_github_api_origin(self):
        req = fp.Request("https://api.github.com/path", headers={"Authorization": "Bearer test"})
        handler = fp.GitHubApiRedirectHandler()
        for destination in ("https://attacker.invalid/collect", "http://api.github.com/path",
                            "https://api.github.com:8443/path", "https://user@api.github.com/path"):
            with self.subTest(destination=destination):
                self.assertIsNone(handler.redirect_request(req, None, 302, "Found", {}, destination))

    def test_redirect_keeps_credentials_for_same_origin(self):
        req = fp.Request("https://api.github.com/path", headers={"Authorization": "Bearer test"})
        redirected = fp.GitHubApiRedirectHandler().redirect_request(
            req, None, 302, "Found", {}, "https://api.github.com/next")
        self.assertEqual(redirected.headers.get("Authorization"), "Bearer test")

    def test_comment_is_bounded_and_paths_render_as_single_safe_code_spans(self):
        rendered = fp.inline_code("`\n<img src=x>" + "x" * 400)
        self.assertNotIn("\n", rendered)
        self.assertIn("<img src=x>", rendered)
        self.assertEqual(fp.inline_code("`path`"), "`` `path` ``")
        self.assertEqual(fp.inline_code("path`"), "`` path` ``")
        content = fp.bounded_comment("line\n" * 100_000)
        self.assertLessEqual(len(content.encode("utf-8")), fp.MAX_COMMENT_BYTES)
        self.assertIn("shortened to fit", content)

    def test_workflow_keeps_pull_request_permissions_and_serializes_per_pr(self):
        workflow = (SCRIPT.parent.parent.parent / ".github/workflows/fork-footprint.yml").read_text()
        self.assertIn("  pull_request:", workflow)
        self.assertIn("  pull-requests: write", workflow)
        self.assertIn("group: fork-footprint-pr-${{ github.event.pull_request.number }}", workflow)
        self.assertIn("cancel-in-progress: false", workflow)

    def test_sticky_comment_skips_when_live_pr_shas_changed(self):
        response = self.Response({"head": {"sha": "new-head"}, "base": {"sha": "base-full-sha"}})
        with self.github_env(), patch.object(fp, "urlopen", return_value=response) as open_request:
            self.assertIsNone(fp.sticky_comment("report", "base-full-sha", "head-full-sha"))
        open_request.assert_called_once()

    def test_sticky_comment_rejects_pagination_outside_github_api_origin(self):
        responses = [self.Response({"head": {"sha": "head-full-sha"}, "base": {"sha": "base-full-sha"}}),
                     self.Response([], {"Link": '<https://attacker.invalid/comments?page=2>; rel="next"'})]
        with self.github_env(), patch.object(fp, "urlopen", side_effect=responses) as open_request:
            with self.assertRaisesRegex(ValueError, "left api.github.com"):
                fp.sticky_comment("report", "base-full-sha", "head-full-sha")
        self.assertEqual(open_request.call_count, 2)

    def test_pr_report_writes_full_summary_before_graceful_403(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp) / "summary.md"
            error = HTTPError("https://api.github.com", 403, "forbidden", {}, None)
            with patch.dict("os.environ", {"GITHUB_STEP_SUMMARY": str(summary)}), \
                    patch.object(fp, "entanglement", return_value={"base": "base-full-sha", "head": "head-full-sha"}), \
                    patch.object(fp, "pr_markdown", return_value="## full report\n"), \
                    patch.object(fp, "sticky_comment", side_effect=error):
                self.assertEqual(fp.main(["pr-report", "--base", "base-full-sha", "--head", "head-full-sha", "--post-comment"]), 0)
            self.assertEqual(summary.read_text(), "## full report\n")

    def test_pr_markdown_reports_full_shas(self):
        report = {"base": "base-full-sha", "head": "head-full-sha", "upgrade_merges": [],
                  "upgrade_conflict_recurrence": [], "drift_intersection": [], "feature_collisions": []}
        with patch.object(fp, "upstream_merges", return_value=[]), patch.object(fp, "paths_between", return_value=[]):
            body = fp.pr_markdown(report)
        self.assertIn("Base: `base-full-sha` · Head: `head-full-sha`", body)

    def test_domain_assignment_is_stable_and_covers_unknown_paths(self):
        self.assertEqual(fp.domain("app/src/main/java/AuthManager.kt")[0], "D2")
        self.assertEqual(fp.domain("app/src/main/java/im/molly/unifiedpush/Receiver.kt")[0], "D1")
        self.assertEqual(fp.domain("app/src/main/res/values/strings.xml")[0], "D6")
        self.assertEqual(fp.domain("tooling/ci/check.py")[0], "D6")

    def test_localized_string_detection_does_not_classify_build_properties(self):
        self.assertTrue(fp.is_string_path("app/src/main/res/values-fr/strings.xml"))
        self.assertFalse(fp.is_string_path("app/src/main/res/values/strings.xml"))
        self.assertFalse(fp.is_string_path("app/src/main/resources/i18n/messages.properties"))
        self.assertFalse(fp.is_string_path("gradle.properties"))

    def test_chunks_respect_byte_budget_except_single_long_line(self):
        old = fp.MAX_PART_BYTES
        try:
            fp.MAX_PART_BYTES = 10
            parts = fp.chunks("abc\ndefgh\nijk\n")
            self.assertEqual("".join(parts), "abc\ndefgh\nijk\n")
            self.assertTrue(all(len(part.encode()) <= 10 for part in parts))
        finally:
            fp.MAX_PART_BYTES = old

    def test_git_diff_never_runs_external_diff_or_textconv(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fp.REPO = root
            def run(*args):
                subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            run("init", "-q")
            run("config", "user.name", "Test")
            run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            marker = root / "external-called"
            external = root / "external-tool"
            external.write_text(f"#!/bin/sh\ntouch '{marker}'\n")
            external.chmod(0o755)
            run("config", "diff.external", str(external))
            run("config", "diff.unsafe.textconv", str(external))
            (root / ".gitattributes").write_text("*.txt diff=unsafe\n")
            (root / "sample.txt").write_text("first\n")
            run("add", ".")
            run("commit", "-qm", "first")
            first = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            (root / "sample.txt").write_text("second\n")
            run("add", ".")
            run("commit", "-qm", "second")
            second = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            fp.paths_between(first, second)
            fp.diff_text(first, second)
            self.assertFalse(marker.exists())

    def test_provenance_uses_upstream_contents_and_merge_evidence(self):
        head = {"local.kt": b"local", "signal.kt": b"signal", "molly.kt": b"molly", "common.kt": b"common",
                "edited.kt": b"collie", "seam.kt": b"resolution"}
        trees = {"signal": {"signal.kt": b"signal", "common.kt": b"common", "edited.kt": b"upstream", "seam.kt": b"signal"},
                 "molly": {"molly.kt": b"molly", "common.kt": b"common", "edited.kt": b"other", "seam.kt": b"molly"}}
        self.assertEqual(fp.provenance("local.kt", head, trees), "collie-original")
        self.assertEqual(fp.provenance("signal.kt", head, trees), "signal-inherited")
        self.assertEqual(fp.provenance("molly.kt", head, trees), "molly-inherited")
        self.assertEqual(fp.provenance("common.kt", head, trees), "signal-inherited")
        self.assertEqual(fp.provenance("edited.kt", head, trees), "collie-modified")
        self.assertEqual(fp.provenance("seam.kt", head, trees, {"seam.kt": {b"resolution"}}), "upgrade-seam")

    def test_security_leads_read_codeql_preflight_and_fuzz_from_requested_head(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            sources = {
                ".github/codeql/reviewed-findings.json": '{"reviews":[{"id":"head-id","rule":"head-rule","disposition":"accepted","location":{"uri":"head.kt"}}]}\n',
                "tooling/ci/preflight.py": "# CollieSocket contract pin from head\n",
                "tooling/fuzz-encrypted-push/src/test/Contract.kt": "// contract version v7\n",
            }
            for name, content in sources.items():
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content)
            run("add", "."); run("commit", "-qm", "head sources")
            requested = run("rev-parse", "HEAD")
            (root / ".github/codeql/reviewed-findings.json").write_text('{"reviews":[{"id":"worktree-id"}]}\n')
            (root / "tooling/ci/preflight.py").write_text("# working tree has no contract pin\n")
            (root / "tooling/fuzz-encrypted-push/src/test/Contract.kt").write_text("// working tree version v9 only\n")
            leads = fp.security_leads(requested, [])
            codeql = [lead for lead in leads if lead["kind"] == "reviewed-codeql"]
            preflight = [lead for lead in leads if lead["kind"] == "colliesocket-pin"]
            fuzz = [lead for lead in leads if lead["kind"] == "contract-version"]
            self.assertEqual([lead["id"] for lead in codeql], ["head-id"])
            self.assertEqual([lead["text"] for lead in preflight], ["# CollieSocket contract pin from head"])
            self.assertEqual([lead["text"] for lead in fuzz], ["// contract version v7"])

    def test_feature_pattern_matching_matches_component_globs(self):
        self.assertTrue(fp.feature_matches("app/src/main/java/a/B.kt".split("/"), "app/**/B.kt"))
        self.assertFalse(fp.feature_matches("app/src/main/java/a/B.kt".split("/"), "app/*/B.kt"))

    def test_upstream_drift_is_limited_to_collie_edited_paths(self):
        signal = {"shared.kt": b"signal", "upstream-only.kt": b"signal"}
        molly = {"shared.kt": b"molly", "upstream-only.kt": b"molly"}
        head = {"shared.kt": b"collie", "upstream-only.kt": b"signal"}
        self.assertEqual(fp.drift_paths({"shared.kt", "upstream-only.kt"}, signal, molly, head), ["shared.kt"])
        self.assertEqual(fp.drift_paths({"shared.kt"}, signal, molly, head, [{"shared.kt": b"collie"}]), [])

    def test_manifest_and_dossier_follow_requested_head(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fp.REPO = root
            def run(*args):
                import subprocess
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q")
            run("config", "user.name", "Test")
            run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            (root / "base.txt").write_text("base\n")
            run("add", "base.txt"); run("commit", "-qm", "base")
            requested = run("rev-parse", "HEAD")
            report = fp.dossiers("HEAD", root / "dossier")
            self.assertEqual(report["head"], requested)
            self.assertEqual(set(report["domains"]), {f"D{i}" for i in range(1, 7)})
            self.assertEqual(set(report["provenance_counts"]), set(fp.PROVENANCE_CLASSES))
            self.assertTrue((root / "dossier/index.json").is_file())
            self.assertTrue((root / "dossier/security-leads.json").is_file())

    def test_entanglement_uses_resolved_refs_and_localized_ratio(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fp.REPO = root
            import subprocess
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); main_branch = run("branch", "--show-current"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid"); run("config", "commit.gpgsign", "false")
            (root / "code.kt").write_text("old\n")
            (root / "app/src/main/res/values-fr/strings.xml").parent.mkdir(parents=True)
            (root / "app/src/main/res/values-fr/strings.xml").write_text("<resources/>\n")
            run("add", "."); run("commit", "-qm", "base"); base = run("rev-parse", "HEAD")
            (root / "code.kt").write_text("new\n")
            (root / "app/src/main/res/values-fr/strings.xml").write_text("<resources>text</resources>\n")
            run("add", "."); run("commit", "-qm", "head"); head = run("rev-parse", "HEAD")
            report = fp.entanglement(base, head)
            self.assertEqual(report["base"], base)
            self.assertEqual(report["head"], head)
            self.assertEqual(report["added_code_to_localized_string_ratio"]["code"], 1)
            self.assertEqual(report["added_code_to_localized_string_ratio"]["localized_strings"], 1)
            self.assertIn("## Fork entanglement", fp.markdown(report))
            self.assertEqual(fp.diff_text(base, head, []), "")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(fp.main(["entanglement", "--base", base, "--head", head, "--json"]), 0)
            self.assertEqual(json.loads(output.getvalue())["head"], head)

    def test_dossier_discovers_signal_merge_and_security_lead_from_ref(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fp.REPO = root
            import subprocess
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); main_branch = run("branch", "--show-current"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid"); run("config", "commit.gpgsign", "false")
            (root / "base.kt").write_text("base\n"); run("add", "."); run("commit", "-qm", "base")
            run("branch", "upstream")
            run("checkout", "-q", "upstream")
            target = root / "app/src/main/java/registration/TrustManager.kt"
            target.parent.mkdir(parents=True)
            target.write_text("class TrustManager { val trust = true }\n")
            signal_import = root / "app/src/main/java/registration/SignalImport.kt"
            signal_import.write_text("class SignalImport\n")
            run("add", "."); run("commit", "-qm", "upstream trust change")
            upstream_sha = run("rev-parse", "HEAD")
            run("checkout", "-q", "-")
            (root / "collie.kt").write_text("collie\n"); run("add", "."); run("commit", "-qm", "collie change")
            run("merge", "--no-ff", "upstream", "-m", "Signal update", "-m", "Layer: upgrade\nUpstream: signalapp/Signal-Android v1.0")
            head = run("rev-parse", "HEAD")
            report = fp.dossiers(head, root / "dossier")
            self.assertEqual(report["upstream_merges"][0]["upstream_parent"], upstream_sha)
            self.assertEqual(report["security_leads"], 1)
            all_rows = [row for domain in report["domains"].values()
                        for row in self.manifest_rows(root / "dossier", domain["manifest_parts"])]
            self.assertFalse(any(row["path"] == "app/src/main/java/registration/SignalImport.kt" for row in all_rows))
            self.assertFalse(any(row["path"] == "app/src/main/java/registration/TrustManager.kt" for row in all_rows))
            self.assertFalse(list((root / "dossier").glob("d6-signal-*.diff")))

    def test_nested_upgrade_merge_is_found_and_dossiers_are_runtime_only_and_bounded(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); main_branch = run("branch", "--show-current"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid"); run("config", "commit.gpgsign", "false")
            runtime = root / "app/src/main/java/registration/LockManager.kt"; runtime.parent.mkdir(parents=True)
            runtime.write_text("class LockManager { val old = true }\n")
            run("add", "."); run("commit", "-qm", "base"); run("branch", "upstream"); run("checkout", "-q", "upstream")
            runtime.write_text("class LockManager { val upstream = true }\n")
            noise = root / "app/src/main/res/values-fr/strings.xml"; noise.parent.mkdir(parents=True); noise.write_text("<resources/>\n")
            run("add", "."); run("commit", "-qm", "upstream"); run("checkout", "-q", main_branch); run("checkout", "-q", "-b", "pr")
            upstream_sha = run("rev-parse", "upstream")
            (root / "app/src/main/java/registration/CollieLock.kt").write_text("class CollieLock\n")
            run("add", "."); run("commit", "-qm", "collie edit")
            run("merge", "--no-ff", "upstream", "-m", "Signal upgrade", "-m", "Layer: upgrade\nUpstream: signalapp/Signal-Android v9")
            run("commit", "--amend", "-m", "Signal upgrade", "-m", "Conflicts at merge time:\n- app/src/main/java/registration/LockManager.kt (both modified)\n\nLayer: upgrade\nUpstream: signalapp/Signal-Android v9")
            upgrade = run("rev-parse", "HEAD")
            run("checkout", "-q", main_branch)
            (root / "pr.txt").write_text("PR\n"); run("add", "."); run("commit", "-qm", "prepare PR")
            run("merge", "--no-ff", "pr", "-m", "Merge pull request")
            runtime.write_text("class LockManager { val collie = true }\n")
            run("add", "."); run("commit", "-qm", "Resolve conflict in LockManager")
            merges = fp.upstream_merges(fp.resolve("HEAD"))
            self.assertIn(upgrade, [m["commit"] for m in merges])
            report = fp.dossiers("HEAD", root / "dossier", max_kb=1)
            incremental = fp.dossiers("HEAD", root / "incremental", since_ref=upgrade, max_kb=1)
            self.assertTrue((root / "dossier/INDEX.md").is_file())
            self.assertEqual(incremental["upstream_merges"], [])
            self.assertTrue((root / "dossier/d1-files.json").is_file())
            all_files = [f for d in report["domains"].values()
                         for f in self.manifest_rows(root / "dossier", d["manifest_parts"])]
            self.assertTrue(any(f["path"] == "app/src/main/java/registration/LockManager.kt" and f["provenance"] == "collie-modified" for f in all_files))
            entangled = fp.entanglement(None, "HEAD")
            conflict_rows = {row["path"]: row["count"] for row in entangled["upgrade_conflict_recurrence"]}
            self.assertEqual(conflict_rows["app/src/main/java/registration/LockManager.kt"], 2)
            self.assertEqual(entangled["base"], upstream_sha)
            self.assertTrue(entangled["upgrade_hunk_scatter"][0]["spans"])
            self.assertFalse(any(f["path"] == "app/src/main/res/values-fr/strings.xml" for f in all_files))
            oversized = [(path.name, path.stat().st_size) for path in (root / "dossier").iterdir()
                         if path.is_file() and path.stat().st_size > 1024]
            self.assertFalse(oversized, f"outputs exceeded --max-kb: {oversized}")
            self.assertGreater(report["domains"]["D2"]["file_count"], 0)
            self.assertTrue(report["domains"]["D2"]["manifest_parts"])
            incremental_files = [f for d in incremental["domains"].values()
                                 for f in self.manifest_rows(root / "incremental", d["manifest_parts"])]
            self.assertTrue(any(f["path"] == "app/src/main/java/registration/LockManager.kt" for f in incremental_files))


if __name__ == "__main__":
    unittest.main()
