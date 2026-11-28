"""Focused tests for the fork footprint CLI."""

import importlib.util
import contextlib
import io
import json
import os
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

    def test_sticky_comment_rejects_repo_and_pr_number_url_injection(self):
        for env, message in (({"GITHUB_REPOSITORY": "owner/repo?x=1"}, "owner/repository"),
                             ({"GITHUB_REPOSITORY": "owner/repo", "PR_NUMBER": "7/comments"}, "positive decimal")):
            values = {"GITHUB_TOKEN": "test", "GITHUB_REPOSITORY": "owner/repo", "PR_NUMBER": "7", **env}
            with patch.dict("os.environ", values):
                with self.assertRaisesRegex(ValueError, message):
                    fp.sticky_comment("body", "base", "head")

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

    def test_workflows_generate_trusted_advisory_and_privileged_job_validates_artifact(self):
        workflow = (SCRIPT.parent.parent.parent / ".github/workflows/fork-footprint.yml").read_text()
        privileged = (SCRIPT.parent.parent.parent / ".github/workflows/fork-footprint-comment.yml").read_text()
        self.assertIn("name: Fork footprint (advisory)", workflow)
        self.assertIn("repository: ${{ github.event.pull_request.base.repo.full_name }}", workflow)
        self.assertIn("ref: ${{ github.event.pull_request.base.sha }}", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", workflow)
        self.assertIn("actions/setup-python@ece7cb06caefa5fff74198d8649806c4678c61a1", workflow)
        self.assertIn("git fetch --no-tags origin \"refs/pull/$PR_NUMBER/head\"", workflow)
        self.assertIn("tooling/ci/fork_footprint.py pr-report --base", workflow)
        self.assertIn("GITHUB_STEP_SUMMARY", workflow)
        self.assertIn("does not contain the advisory generator yet", workflow)
        self.assertNotIn("GITHUB_TOKEN", workflow)
        self.assertIn("actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9", workflow)
        self.assertIn("name: Fork footprint comment", privileged)
        self.assertIn("workflows: [Fork footprint (advisory)]", privileged)
        self.assertIn("persist-credentials: false", privileged)
        self.assertIn("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", privileged)
        self.assertIn("  pull_request:", workflow)
        self.assertIn("  contents: read", workflow)
        self.assertNotIn("pull-requests: write", workflow)
        self.assertIn("group: fork-footprint-pr-${{ github.event.pull_request.number }}", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9", workflow)
        self.assertIn("RUN_HEAD_SHA: ${{ github.event.pull_request.head.sha }}", workflow)
        self.assertIn("MERGE_SHA: ${{ github.sha }}", workflow)
        self.assertIn("actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333", privileged)
        self.assertIn("workflow_run:", privileged)
        self.assertIn("pull-requests: write", privileged)
        self.assertNotIn("pull_request_target", privileged)
        self.assertIn("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", workflow)
        self.assertIn("validate-run --event", privileged)
        self.assertIn("head_branch", (SCRIPT.parent / "fork_footprint.py").read_text())
        self.assertIn("needs: validate", privileged)
        self.assertIn("should_comment == 'true'", privileged)
        self.assertIn("pull-requests: read", privileged)
        self.assertIn("actions: read", privileged)
        self.assertNotIn("pull-requests: write", privileged.split("  comment:", 1)[0])
        self.assertIn("pull-requests: write", privileged.split("  comment:", 1)[1])
        advisory_group = next(line.strip() for line in workflow.splitlines() if line.strip().startswith("group:"))
        comment_group = next(line.strip() for line in privileged.splitlines() if line.strip().startswith("group:"))
        self.assertEqual(advisory_group, "group: fork-footprint-pr-${{ github.event.pull_request.number }}")
        self.assertEqual(comment_group, "group: fork-footprint-comment-pr-${{ needs.validate.outputs.pr_number }}")
        self.assertNotEqual(advisory_group.partition(":")[2].strip(), comment_group.partition(":")[2].strip())
        self.assertIn('git fetch --no-tags origin "refs/pull/$PR_NUMBER/head"', privileged)
        self.assertIn("git cat-file -e", privileged)

    def test_workflow_run_fork_fallback_uses_encoded_head_and_validates_live_pr(self):
        sha = "a" * 40; base = "b" * 40; merge = "c" * 40
        run = {"event": "pull_request", "id": 12, "head_sha": sha, "head_branch": "feature/a b",
               "head_repository": {"full_name": "fork-owner/repo"}}
        pr = {"number": 7, "head": {"sha": sha, "repo": {"full_name": "fork-owner/repo"}},
              "base": {"sha": base, "repo": {"full_name": "owner/repo"}}}
        meta = {"run_id": 12, "run_head_sha": sha, "pr_number": 7, "base_sha": base,
                "head_sha": sha, "merge_sha": merge}
        responses = [self.Response([pr]), self.Response(pr)]
        requests = []
        def open_request(req, timeout):
            requests.append(req)
            return responses.pop(0)
        with patch.object(fp, "REPO", Path(".")), patch.object(fp, "urlopen", side_effect=open_request), \
                patch.object(fp.subprocess, "run") as fetch, \
                patch.object(fp.subprocess, "check_output", side_effect=[merge + "\n", base + " " + sha]):
            result = fp.validate_workflow_run({"workflow_run": run, "pull_requests": []}, meta, "owner/repo", "token")
        self.assertEqual(result, {"pr_number": 7, "base_sha": base, "head_sha": sha})
        self.assertIn("head=fork-owner%3Afeature%2Fa+b", requests[0].full_url)
        self.assertEqual(len(requests), 2)
        fetch.assert_called_once()

    def test_workflow_run_validation_skips_ambiguous_or_mismatched_pr(self):
        sha = "a" * 40
        run = {"event": "pull_request", "id": 12, "head_sha": sha, "head_branch": "feature",
               "head_repository": {"full_name": "fork/repo"}}
        matching = {"number": 7, "head": {"sha": sha, "repo": {"full_name": "fork/repo"}},
                    "base": {"repo": {"full_name": "owner/repo"}}}
        for prs in ([matching, matching], [dict(matching, number=8)], []):
            with patch.object(fp, "urlopen", return_value=self.Response(prs)):
                self.assertIsNone(fp.validate_workflow_run({"workflow_run": run}, {"pr_number": 7},
                                                           "owner/repo", "token"))

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

    def test_git_path_lists_are_nul_delimited_and_per_file_diffs_are_literal(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            names = ["line\nbreak.kt", ":(glob)other*.kt"]
            for i, name in enumerate(names):
                path = root / name; path.write_text(f"before-{i}\n")
            run("add", "."); run("commit", "-qm", "base"); base = run("rev-parse", "HEAD")
            for i, name in enumerate(names):
                (root / name).write_text(f"after-{i}\n")
            run("add", "."); run("commit", "-qm", "head"); head = run("rev-parse", "HEAD")
            self.assertEqual(fp.paths_between(base, head), sorted(names))
            patch = fp.diff_text(base, head, [names[1]])
            self.assertIn("after-1", patch)
            self.assertNotIn("after-0", patch)

    def test_numstat_and_hunks_handle_quoted_unicode_and_non_utf8_paths(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            names = ['quote " and 雪.kt', os.fsdecode(b"invalid-\xff.kt"),
                     "space b/ inside b/name.kt", "control-\x07-and-\x08.kt"]
            for i, name in enumerate(names):
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"before-{i}\n")
            localized = root / "app/src/main/res/values-fr/strings.xml"
            localized.parent.mkdir(parents=True); localized.write_text("<resources/>\n")
            binary = root / "binary.dat"; binary.write_bytes(b"\x00\xffbefore")
            run("add", "."); run("commit", "-qm", "base"); base = run("rev-parse", "HEAD")
            for i, name in enumerate(names):
                (root / name).write_text(f"after-{i}\n")
            localized.write_text("<resources>texte</resources>\n")
            binary.write_bytes(b"\x00\xfeafter")
            run("add", "."); run("commit", "-qm", "head"); head = run("rev-parse", "HEAD")
            stat = fp.diff_numstat(base, head)
            self.assertEqual(stat[names[0]], (1, 1))
            self.assertEqual(stat[names[1]], (1, 1))
            self.assertEqual(stat[names[2]], (1, 1))
            self.assertEqual(stat[names[3]], (1, 1))
            self.assertEqual(stat["app/src/main/res/values-fr/strings.xml"], (1, 1))
            self.assertEqual(stat["binary.dat"], (0, 0))
            count, path_counts, spans = fp.diff_hunk_metadata(base, head, names)
            self.assertEqual(count, 4)
            self.assertEqual(path_counts, {name: 1 for name in names})
            self.assertEqual(set(spans), set(names))
            literal = "literal-\\xff.kt"
            raw_byte = os.fsdecode(b"raw-\xff.kt")
            safe = fp.json_safe({literal: literal, raw_byte: raw_byte})
            self.assertEqual(len(safe), 2)
            self.assertEqual(safe["literal-\\\\xff.kt"], "literal-\\\\xff.kt")
            self.assertEqual(safe[r"raw-\xff.kt"], r"raw-\xff.kt")
            self.assertIn(r"invalid-\xff.kt", fp.inline_code(names[1]))

    def test_tree_diff_respects_diff_attributes_and_attr_source_refs(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            (root / ".gitattributes").write_text("sample.txt diff\n")
            (root / "sample.txt").write_text("before\n")
            run("add", "."); run("commit", "-qm", "text diff enabled")
            base = run("rev-parse", "HEAD").decode().strip()
            (root / ".gitattributes").write_text("sample.txt -diff\n")
            (root / "sample.txt").write_text("after\n")
            run("add", "."); run("commit", "-qm", "text diff disabled")
            head = run("rev-parse", "HEAD").decode().strip()

            def attr(source=None):
                args = ["check-attr", "-z"]
                if source:
                    args.extend(["--source", source])
                args.extend(["diff", "--", "sample.txt"])
                return run(*args).split(b"\0")

            self.assertEqual(attr(base), [b"sample.txt", b"diff", b"set", b""])
            self.assertEqual(attr(head), [b"sample.txt", b"diff", b"unset", b""])
            self.assertEqual(attr(), [b"sample.txt", b"diff", b"unset", b""])
            count, path_counts, spans = fp.diff_hunk_metadata(base, head, ["sample.txt"])
            self.assertEqual((count, path_counts, spans), (0, {}, {}))

    def test_hunks_for_file_replaced_by_directory_count_exact_paths(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            (root / "node").write_text("old file\n")
            run("add", "."); run("commit", "-qm", "base"); base = run("rev-parse", "HEAD")
            (root / "node").unlink()
            (root / "node").mkdir()
            (root / "node/child.kt").write_text("new child\n")
            run("add", "-A"); run("commit", "-qm", "head"); head = run("rev-parse", "HEAD")
            count, path_counts, spans = fp.diff_hunk_metadata(base, head, ["node", "node/child.kt"])
            self.assertEqual(count, 2)
            self.assertEqual(path_counts, {"node": 1, "node/child.kt": 1})
            self.assertEqual(spans["node"][0]["old_count"], 1)
            self.assertEqual(spans["node"][0]["new_count"], 0)

    def test_hunk_metadata_overrides_diff_noprefix_and_keeps_spans(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false"); run("config", "diff.noprefix", "true")
            source = root / "sample.kt"
            source.write_text("".join(f"line {number}\n" for number in range(1, 21)))
            run("add", "."); run("commit", "-qm", "base"); base = run("rev-parse", "HEAD")
            lines = source.read_text().splitlines()
            lines[1] = "changed 2"
            lines[17] = "changed 18"
            source.write_text("\n".join(lines) + "\n")
            run("add", "."); run("commit", "-qm", "head"); head = run("rev-parse", "HEAD")

            count, path_counts, spans = fp.diff_hunk_metadata(base, head, ["sample.kt"])

            self.assertEqual(count, 2)
            self.assertEqual(path_counts, {"sample.kt": 2})
            self.assertEqual(spans, {"sample.kt": [
                {"old_start": 1, "old_count": 5, "new_start": 1, "new_count": 5},
                {"old_start": 15, "old_count": 6, "new_start": 15, "new_count": 6},
            ]})

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
        self.assertEqual(fp.drift_paths({"deleted.kt"}, {"deleted.kt": b"signal"},
                                        {"deleted.kt": b"molly"}, {}, [{"deleted.kt": b"signal"}]),
                         ["deleted.kt"])

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

    def test_pr_delta_uses_merge_base_and_feature_catalog_uses_trusted_base(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); fp.REPO = root
            def run(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).stdout.decode().strip()
            run("init", "-q"); run("config", "user.name", "Test"); run("config", "user.email", "test@example.invalid")
            run("config", "commit.gpgsign", "false")
            source = root / "app/src/main/java/Feature.kt"; source.parent.mkdir(parents=True)
            source.write_text("class Feature { val original = true }\n")
            catalog = root / "tooling/features.toml"; catalog.parent.mkdir(parents=True)
            catalog.write_text('[features.one]\npaths=["app/**"]\n[features.two]\npaths=["app/**"]\n')
            run("add", "."); run("commit", "-qm", "common"); common = run("rev-parse", "HEAD")
            run("checkout", "-qb", "target")
            (root / "target-only.kt").write_text("target\n"); run("add", "."); run("commit", "-qm", "target advance")
            base = run("rev-parse", "HEAD")
            run("checkout", "-q", common); run("checkout", "-qb", "pr")
            source.write_text("class Feature { val changed = true }\n")
            catalog.write_text('[features.one]\npaths=["app/**"]\n[features.two]\npaths=["app/**"]\n[features.three]\npaths=["app/**"]\n')
            run("add", "."); run("commit", "-qm", "PR change"); head = run("rev-parse", "HEAD")
            standalone = fp.entanglement(base, head)
            pr = fp.entanglement(base, head, history_ref=base)
            self.assertEqual(pr["base"], base)
            self.assertEqual(pr["delta_base"], common)
            self.assertEqual(pr["added_code_to_localized_string_ratio"]["code"], 3)
            self.assertEqual(standalone["feature_collisions"][0]["layers"], ["one", "three", "two"])
            self.assertEqual(pr["feature_collisions"], [])
            standalone_output = io.StringIO()
            with contextlib.redirect_stdout(standalone_output):
                self.assertEqual(fp.main(["entanglement", "--base", base, "--head", head, "--json"]), 0)
            self.assertEqual(json.loads(standalone_output.getvalue())["feature_collisions"], standalone["feature_collisions"])
            pr_output = io.StringIO()
            with contextlib.redirect_stdout(pr_output):
                self.assertEqual(fp.main(["pr-report", "--base", base, "--head", head, "--json"]), 0)
            self.assertEqual(json.loads(pr_output.getvalue())["feature_collisions"], [])

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
            self.assertTrue(any("CollieLock" in path.read_text()
                                for path in (root / "dossier").glob("d2-original-*.diff")))
            index_pointer = json.loads((root / "dossier/index.json").read_text())
            self.assertIn("runtime_file_rows", index_pointer)
            self.assertIn("provenance_counts", index_pointer)
            self.assertIn("security_leads", index_pointer)


if __name__ == "__main__":
    unittest.main()
