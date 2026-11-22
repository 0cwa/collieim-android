"""Exercise the PR #168 regression through real commits, merges and pushes."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPTS = Path(__file__).resolve().parent
CHECKER = SCRIPTS / "collie_check_layer_trailers.py"
INSTALLER = SCRIPTS / "install.py"
GOOD = "Change the website\n\nLayer: website\nCo-authored-by: Name <name@example.com>\n"
BROKEN = GOOD.replace("website\nCo-authored", "website\n\nCo-authored")
REPO_CATALOG = SCRIPTS.parent / "layers.toml"
CATALOG = """
[layers.website]
kind = "infra"
about = "Website"
[layers.relay]
kind = "feature"
about = "Relay"
[layers.setup]
kind = "feature"
about = "Setup"
[layers.unified-push]
alias_of = "relay"
"""
UPSTREAM_CATALOG = CATALOG + '[layers.upgrade]\nkind = "upstream"\nabout = "Upstream ports"\n'
SIGNAL = "signalapp/Signal-Android"
MOLLY = "mollyim/mollyim-android"


def provenance_table(layer="relay", **overrides):
    fields = {
        "repository": "johanw666/Signal-Android",
        "revision": "8f4ad8577344e844945ee8e73d95fd1fcc7208bc",
        "revision_kind": "release-snapshot",
        "relationship": "adaptation",
        "application": "manual",
        "scope": "Selected behavior from the snapshot",
    }
    fields.update(overrides)
    return f"\n[layers.{layer}.provenance]\n" + "".join(
        f"{key} = {json.dumps(value)}\n" for key, value in fields.items()
    )


def load_checker():
    spec = importlib.util.spec_from_file_location("checker", CHECKER)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    return checker


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.checker = load_checker()

    def test_old_catalogs_and_unclassified_features_still_parse(self):
        for text in ("", CATALOG, UPSTREAM_CATALOG):
            with self.subTest(text=text):
                self.checker.Catalog(text)
        catalog = self.checker.Catalog(CATALOG + provenance_table(prerequisites=["setup"]))
        self.assertNotIn("provenance", catalog.layers["setup"])
        self.assertEqual(catalog.kind("relay"), "feature")
        self.assertIsNone(catalog.problem("relay"))

    def test_relationship_revision_and_application_combinations(self):
        for relationship in ("direct-import", "adaptation", "inspiration"):
            for revision_kind in ("feature-commit", "release-snapshot"):
                for application in ("mechanical", "manual"):
                    with self.subTest(relationship=relationship, revision_kind=revision_kind, application=application):
                        text = CATALOG + provenance_table(
                            relationship=relationship, revision_kind=revision_kind, application=application
                        )
                        if application == "mechanical" and (relationship != "direct-import" or revision_kind != "feature-commit"):
                            with self.assertRaisesRegex(ValueError, "mechanical application requires"):
                                self.checker.Catalog(text)
                        else:
                            self.checker.Catalog(text)

    def test_required_fields_cannot_be_missing_or_have_wrong_types(self):
        table = provenance_table()
        for field in self.checker.PROVENANCE_REQUIRED:
            with self.subTest(field=field, missing=True):
                text = "\n".join(line for line in table.splitlines() if not line.startswith(f"{field} ="))
                with self.assertRaisesRegex(ValueError, f"missing fields: {field}"):
                    self.checker.Catalog(CATALOG + text)
            for value in (False, 12, [], {}, "", "   "):
                with self.subTest(field=field, value=value):
                    with self.assertRaisesRegex(ValueError, f"{field} must be a nonblank string"):
                        self.checker.Catalog(CATALOG + provenance_table(**{field: value}))

    def test_invalid_classifications_are_rejected(self):
        for field, value in (
            ("relationship", "import"), ("revision_kind", "commit"),
            ("application", "automatic"), ("relationship", "adaptation "),
        ):
            with self.subTest(field=field, value=value):
                with self.assertRaisesRegex(ValueError, f"{field} must be one of"):
                    self.checker.Catalog(CATALOG + provenance_table(**{field: value}))

    def test_revision_is_an_exact_commit_not_a_discovery_ref(self):
        for revision in ("main", "refs/heads/main", "refs/tags/v8.36.0.0-JW", "8f4ad857", "a" * 39, "b" * 41, "A" * 40, "0" * 40, "g" * 40):
            with self.subTest(revision=revision):
                with self.assertRaisesRegex(ValueError, "revision must be an exact"):
                    self.checker.Catalog(CATALOG + provenance_table(revision=revision))

    def test_discovery_refs_are_optional_and_separate_from_selected_revisions(self):
        for ref in ("refs/heads/main", "refs/heads/feature/guard", "refs/tags/v8.36.0.0-JW"):
            with self.subTest(ref=ref):
                catalog = self.checker.Catalog(CATALOG + provenance_table(discovery_ref=ref))
                self.assertEqual(catalog.layers["relay"]["provenance"]["revision"], "8f4ad8577344e844945ee8e73d95fd1fcc7208bc")
        for ref in (False, [], {}, 42, "", "main", "a" * 40, "refs/tags/*-JW", "refs/heads/", "refs/heads/../main", "refs/pull/1/head", "refs/heads/two words", "refs/heads/main.lock"):
            with self.subTest(ref=ref):
                with self.assertRaisesRegex(ValueError, "discovery_ref must be a full"):
                    self.checker.Catalog(CATALOG + provenance_table(discovery_ref=ref))

    def test_repository_names_and_upstream_integration_boundary(self):
        for repository in ("https://github.com/johanw666/Signal-Android", "johanw666", "johanw666/Signal-Android/extra", "johanw666/Signal-Android.git", "johanw666/Signal-Android.GIT", "owner/..", "-owner/repo", "owner-/repo", "owner/repo name"):
            with self.subTest(repository=repository):
                with self.assertRaisesRegex(ValueError, "repository must be a GitHub owner/repo"):
                    self.checker.Catalog(CATALOG + provenance_table(repository=repository))
        for repository in (SIGNAL, MOLLY, SIGNAL.upper(), MOLLY.upper()):
            with self.subTest(repository=repository):
                with self.assertRaisesRegex(ValueError, "verified upstream merges"):
                    self.checker.Catalog(CATALOG + provenance_table(repository=repository))
        self.assertEqual(set(self.checker.UPSTREAM_URLS), {SIGNAL, MOLLY})

    def test_provenance_belongs_only_to_canonical_features(self):
        for layer in ("unified-push", "website", "upgrade"):
            with self.subTest(layer=layer):
                with self.assertRaisesRegex(ValueError, "only canonical feature"):
                    self.checker.Catalog(UPSTREAM_CATALOG + provenance_table(layer=layer))
        catalog = self.checker.Catalog(CATALOG + provenance_table())
        self.assertIn("use 'Layer: relay'", catalog.problem("unified-push"))

    def test_provenance_must_be_a_nonempty_table_with_known_fields(self):
        for value in (False, [], "unknown", 42):
            with self.subTest(value=value):
                text = CATALOG.replace('about = "Relay"', f'about = "Relay"\nprovenance = {json.dumps(value)}')
                with self.assertRaisesRegex(ValueError, "must be a table"):
                    self.checker.Catalog(text)
        with self.assertRaisesRegex(ValueError, "missing fields"):
            self.checker.Catalog(CATALOG + "[layers.relay.provenance]\n")
        for field in ("authors", "paths", "history", "app_commit", "selected_revision"):
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError, f"unknown fields: {field}"):
                    self.checker.Catalog(CATALOG + provenance_table(**{field: "not part of the contract"}))

    def test_prerequisites_must_be_unique_other_canonical_features(self):
        for prerequisites in ([], ["setup"]):
            self.checker.Catalog(CATALOG + provenance_table(prerequisites=prerequisites))
        for prerequisites in (False, "setup", {}, [False], [42], [[]]):
            with self.subTest(prerequisites=prerequisites):
                with self.assertRaisesRegex(ValueError, "list of canonical feature IDs"):
                    self.checker.Catalog(CATALOG + provenance_table(prerequisites=prerequisites))
        with self.assertRaisesRegex(ValueError, "duplicate feature IDs"):
            self.checker.Catalog(CATALOG + provenance_table(prerequisites=["setup", "setup"]))
        for prerequisite in ("unknown", "unified-push", "website", "upgrade", "relay", ""):
            with self.subTest(prerequisite=prerequisite):
                with self.assertRaisesRegex(ValueError, "must name another canonical feature"):
                    self.checker.Catalog(UPSTREAM_CATALOG + provenance_table(prerequisites=[prerequisite]))

    def test_prerequisite_chains_are_valid_but_cycles_are_rejected(self):
        base = CATALOG + '[layers.stickers]\nkind = "feature"\nabout = "Stickers"\n'
        chain = base + provenance_table(prerequisites=["setup"]) + provenance_table(layer="setup", prerequisites=["stickers"])
        self.checker.Catalog(chain)
        with self.assertRaisesRegex(ValueError, "cyclic provenance prerequisites"):
            self.checker.Catalog(chain + provenance_table(layer="stickers", prerequisites=["relay"]))

    def test_malformed_catalog_types_fail_with_validation_errors(self):
        for text in (
            'layers = []', '[layers]\nrelay = false',
            '[layers.relay]\nkind = []\nabout = "Relay"',
            '[layers.relay]\nalias_of = []',
        ):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    self.checker.Catalog(text)


class RepositoryTest(unittest.TestCase):
    """A real repository with commits, merges and pushes."""

    def setUp(self):
        scratch = Path(os.environ.get(
            "RUNNER_TEMP", Path.home() / "agent-scratch/collieim-android/git-hooks"
        ))
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="layer-trailers-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repo = self.root / "repository with spaces"
        self.repo.mkdir()
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("-c", "init.templateDir=", "init", "--initial-branch=main")
        self.git("config", "user.name", "Test Author")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "commit.gpgsign", "false")
        self.commit("Initial source")  # Historical main commits are outside the PR range.
        self.base = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("update-ref", "refs/remotes/origin/main", self.base)

    def run_command(self, *args, check=True, **kwargs):
        result = subprocess.run(
            args, cwd=kwargs.pop("cwd", self.repo), env=self.env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs
        )
        if check:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def git(self, *args, **kwargs):
        return self.run_command("git", *args, **kwargs)

    def commit(self, message, **kwargs):
        return self.git("commit", "--allow-empty", "--file=-", input=message, **kwargs)

    def install(self, **kwargs):
        return self.run_command(sys.executable, str(INSTALLER), **kwargs)

    def check_range(self, **kwargs):
        return self.run_command(sys.executable, str(CHECKER), "range", self.base, **kwargs)

    def add_catalog(self, text=CATALOG, layer="website"):
        path = self.repo / "tooling/layers.toml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        self.git("add", str(path))
        self.commit(f"Add the layer catalog\n\nLayer: {layer}\n")

    def check_pr(self, number, **kwargs):
        env = dict(self.env, COLLIE_PR_NUMBER=str(number))
        result = subprocess.run(
            [sys.executable, str(CHECKER), "range", self.base], cwd=self.repo, env=env,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs
        )
        return result

    def bare_remote(self):
        remote = self.root / "remote.git"
        self.git("init", "--bare", str(remote))
        self.git("remote", "add", "origin", str(remote))
        return remote


class LayerTrailerTests(RepositoryTest):
    def test_original_blank_line_regression_is_rejected_by_commit_and_range(self):
        self.install()
        rejected = self.commit(BROKEN, check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("no blank line", rejected.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), self.base)
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-F", "-", input=BROKEN)
        rejected = self.check_range(check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("needs exactly one", rejected.stderr)

    def test_joined_trailers_pass_commit_and_range_without_changing_message(self):
        self.install()
        self.commit(GOOD)
        self.assertEqual(self.git("show", "-s", "--format=%B").stdout.strip(), GOOD.strip())
        self.assertIn("checked 1 non-merge commits", self.check_range().stdout)

    def test_base_checker_validates_head_without_checker(self):
        checker_path = self.repo / "tooling/git-hooks/collie_check_layer_trailers.py"
        checker_path.parent.mkdir(parents=True)
        checker_path.write_bytes(CHECKER.read_bytes())
        self.git("add", str(checker_path))
        self.commit("Add checker\n\nLayer: tooling")
        base = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("rm", str(checker_path))
        self.commit(GOOD)
        extracted = self.root / "trusted-checker.py"
        extracted.write_text(self.git("show", f"{base}:tooling/git-hooks/collie_check_layer_trailers.py").stdout)
        self.assertIn("checked 1 non-merge commits", self.run_command(sys.executable, str(extracted), "range", base, "HEAD").stdout)
        self.commit(BROKEN)
        rejected = self.run_command(sys.executable, str(extracted), "range", base, "HEAD", check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("needs exactly one", rejected.stderr)

    def test_missing_duplicate_and_invalid_layers_are_rejected(self):
        self.install()
        for trailers in ("", "Layer: website\nLayer: website", "Layer: Website", "Layer: two words", "Layer: ../site", "Layer:"):
            with self.subTest(trailers=trailers):
                result = self.commit(f"Change\n\n{trailers}\n", check=False)
                self.assertNotEqual(result.returncode, 0)

    def test_editor_comments_are_handled_by_git(self):
        self.install()
        self.git("commit", "--allow-empty", "--cleanup=strip", "-F", "-", input=GOOD + "\n# Editor instructions\n")
        self.check_range()

    def test_amend_is_checked(self):
        self.install()
        self.commit(GOOD)
        before = self.git("rev-parse", "HEAD").stdout
        result = self.git("commit", "--amend", "-F", "-", input=BROKEN, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD").stdout, before)

    def test_merges_are_exempt_but_their_commits_are_checked(self):
        self.git("switch", "-c", "topic")
        self.commit(GOOD)
        self.git("switch", "main")
        self.commit("Change\n\nLayer: tooling")
        self.install()
        self.git("merge", "--no-ff", "topic", "-m", "Merge topic without a Layer")
        self.assertIn("checked 2 non-merge commits", self.check_range().stdout)
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)

    def test_completing_stopped_merge_is_exempt(self):
        self.git("switch", "-c", "topic")
        self.commit(GOOD)
        self.git("switch", "main")
        self.install()
        self.git("merge", "--no-ff", "--no-commit", "topic")
        self.git("commit", "-m", "Merge topic without a Layer")
        self.assertIn("checked 1 non-merge commits", self.check_range().stdout)

    def test_squash_merge_still_requires_a_trailer(self):
        self.git("switch", "-c", "topic")
        self.commit(GOOD)
        self.git("switch", "main")
        self.install()
        self.git("merge", "--squash", "topic")
        self.assertNotEqual(self.commit("Squashed topic", check=False).returncode, 0)

    def test_pre_push_blocks_bad_ancestor_even_when_head_is_valid(self):
        remote = self.bare_remote()
        self.commit(BROKEN)
        self.git("push", "origin", "HEAD:refs/heads/topic")  # Bad commit already published.
        previous = self.git("rev-parse", "HEAD").stdout.strip()
        self.commit(GOOD)
        self.install()
        result = self.git("push", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("needs exactly one", result.stderr)
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/topic").stdout.strip(), previous)

    def test_new_branch_push_passes_and_branch_deletion_is_allowed(self):
        remote = self.bare_remote()
        self.install()
        self.commit(GOOD)
        self.git("push", "origin", "HEAD:refs/heads/topic")
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/topic").stdout, self.git("rev-parse", "HEAD").stdout)
        self.git("push", "origin", ":refs/heads/topic")
        self.assertNotEqual(self.git("--git-dir", str(remote), "show-ref", "--verify", "refs/heads/topic", check=False).returncode, 0)

    def test_new_branch_with_bad_history_is_not_published(self):
        remote = self.bare_remote()
        self.commit(BROKEN)
        self.install()
        result = self.git("push", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("needs exactly one", result.stderr)
        self.assertNotEqual(self.git("--git-dir", str(remote), "show-ref", "--verify", "refs/heads/topic", check=False).returncode, 0)

    def test_missing_base_fails_closed_with_fetch_instruction(self):
        remote = self.bare_remote()
        self.install()
        self.commit(GOOD)
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        result = self.git("push", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("git fetch origin main", result.stderr)
        self.assertNotEqual(self.git("--git-dir", str(remote), "show-ref", "--verify", "refs/heads/topic", check=False).returncode, 0)

    def test_installer_refuses_existing_hooks_without_partial_install(self):
        hook = self.repo / ".git/hooks/pre-push"
        hook.parent.mkdir()
        hook.write_text("#!/bin/sh\n# Existing user hook\n", encoding="utf-8")
        result = self.install(check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing to overwrite", result.stderr)
        self.assertEqual(hook.read_text(), "#!/bin/sh\n# Existing user hook\n")
        self.assertFalse((hook.parent / "commit-msg").exists())

    def test_installer_refuses_empty_or_symlinked_hooks(self):
        hook = self.repo / ".git/hooks/commit-msg"
        hook.parent.mkdir()
        hook.touch()
        self.assertNotEqual(self.install(check=False).returncode, 0)
        hook.unlink()
        hook.symlink_to(self.root / "missing-user-hook")
        self.assertNotEqual(self.install(check=False).returncode, 0)
        self.assertTrue(hook.is_symlink())

    def test_installer_from_linked_worktree_covers_both_worktrees(self):
        worktree = self.root / "linked worktree"
        self.git("worktree", "add", "-b", "topic", str(worktree))
        self.run_command(sys.executable, str(INSTALLER), cwd=worktree)
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)
        result = self.run_command(
            "git", "commit", "--allow-empty", "-F", "-", input=BROKEN,
            cwd=worktree, check=False
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("needs exactly one", result.stderr)

    def test_installer_can_be_rerun_and_supports_custom_hooks_path(self):
        hooks = self.root / "custom hooks"
        self.git("config", "core.hooksPath", str(hooks))
        self.install()
        self.install()
        self.assertTrue((hooks / "commit-msg").is_file())
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)

    def test_installed_hooks_survive_source_files_disappearing(self):
        copied = self.root / "installer checkout"
        copied.mkdir()
        for name in ("install.py", "commit-msg", "pre-push", "collie_check_layer_trailers.py"):
            (copied / name).write_bytes((SCRIPTS / name).read_bytes())
        self.run_command(sys.executable, str(copied / "install.py"))
        for path in copied.iterdir():
            path.unlink()
        self.assertNotEqual(self.commit(BROKEN, check=False).returncode, 0)
        self.commit(GOOD)


    def test_repository_catalog_parses(self):
        checker = load_checker()
        catalog = checker.Catalog(REPO_CATALOG.read_text(encoding="utf-8"))
        self.assertIsNone(catalog.problem("unifiedpush"))
        self.assertIn("use 'Layer: unifiedpush'", catalog.problem("unified-push"))

    def test_catalog_rejects_unknown_and_old_layer_names(self):
        self.add_catalog()
        self.install()
        rejected = self.commit("Change\n\nLayer: relays\n", check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("not in tooling/layers.toml", rejected.stderr)
        rejected = self.commit("Change\n\nLayer: unified-push\n", check=False)
        self.assertIn("use 'Layer: relay'", rejected.stderr)
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-m", "Change", "-m", "Layer: relays")
        self.assertIn("not in tooling/layers.toml", self.check_range(check=False).stderr)

    def test_new_feature_is_accepted_with_its_catalog_entry_in_the_same_range(self):
        self.add_catalog(CATALOG + '[layers.stickers]\nkind = "feature"\nabout = "Stickers"\n')
        self.commit("Add sticker packs\n\nLayer: stickers\n")
        self.assertIn("feature layer: stickers", self.check_pr(202).stdout)

    def test_one_feature_per_pr_is_enforced_from_the_cutoff(self):
        self.add_catalog(CATALOG + provenance_table())
        self.commit("Relay change\n\nLayer: relay\n")
        self.commit("Relay tests\n\nLayer: website\n")
        self.assertEqual(self.check_pr(202).returncode, 0)  # One feature plus infra.
        self.commit("Setup change\n\nLayer: setup\n")
        enforced = self.check_pr(202)
        self.assertNotEqual(enforced.returncode, 0)
        self.assertIn("Error: this range changes 2 feature layers (relay, setup)", enforced.stderr)
        older = self.check_pr(201)
        self.assertEqual(older.returncode, 0)
        self.assertIn("Warning:", older.stderr)
        local = self.check_range()
        self.assertIn("Warning:", local.stderr)

    def test_pr_cannot_relabel_an_existing_feature_or_move_the_cutoff(self):
        self.add_catalog(CATALOG + provenance_table())
        self.base = self.git("rev-parse", "HEAD").stdout.strip()  # The catalog is on the base.
        relabelled = CATALOG.replace('[layers.setup]\nkind = "feature"', '[layers.setup]\nkind = "infra"')
        self.add_catalog(relabelled + provenance_table() + "[rules]\none_feature_per_pr_from = 999\n")
        self.commit("Relay change\n\nLayer: relay\n")
        self.commit("Setup change\n\nLayer: setup\n")
        enforced = self.check_pr(202)
        self.assertNotEqual(enforced.returncode, 0)
        self.assertIn("2 feature layers (relay, setup)", enforced.stderr)

    def test_pr_into_another_branch_skips_commits_already_on_main(self):
        self.add_catalog(CATALOG + provenance_table(prerequisites=["setup"]))
        self.base = self.git("rev-parse", "HEAD").stdout.strip()  # The Beta branch.
        self.git("switch", "--quiet", "-c", "main-tip")
        self.commit("Setup change on main\n\nLayer: setup\n")
        self.commit("Main commit from before the trailer rule\n")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("switch", "--quiet", "--detach", self.base)
        self.commit("Relay change on the Beta branch\n\nLayer: relay\n")
        self.git("merge", "--quiet", "--no-ff", "--no-edit", "main-tip")
        result = self.check_pr(202)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Skipped 2 commits already on main.", result.stdout)
        self.assertIn("feature layer: relay.", result.stdout)
        self.commit("Beta commit without a trailer\n")
        self.assertNotEqual(self.check_pr(202).returncode, 0)

    def test_range_without_catalog_checks_only_the_format(self):
        self.commit("Change\n\nLayer: anything-new\n")
        self.assertEqual(self.check_pr(300).returncode, 0)

    def test_provenance_is_validated_in_worktree_and_pr_head(self):
        self.add_catalog(CATALOG + provenance_table())
        self.install()
        catalog = self.repo / "tooling/layers.toml"
        catalog.write_text(CATALOG + provenance_table(revision="refs/heads/main"), encoding="utf-8")
        self.git("add", str(catalog))
        rejected = self.commit("Update provenance\n\nLayer: relay\n", check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("revision must be an exact", rejected.stderr)
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-m", "Update provenance", "-m", "Layer: relay")
        rejected = self.check_pr(300)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("revision must be an exact", rejected.stderr)
        self.assertNotIn("Traceback", rejected.stderr)


class UpstreamMergeTests(RepositoryTest):
    """Upstream merges, verified against local stand-ins for the upstream repositories."""

    def upstream(self, name, branch):
        """Publish unsigned, trailer-less history on its own root to a bare repository."""
        remote = self.root / f"{name}.git"
        self.git("init", "--bare", str(remote))
        self.git("switch", "--orphan", name)
        self.commit(f"{name} change one")
        self.git("switch", "-c", f"{name}-side")
        self.commit(f"{name} side change")
        self.git("switch", name)
        self.commit(f"{name} change two")
        self.git("merge", "--no-ff", f"{name}-side", "-m", f"{name}'s own merge")
        self.git("push", str(remote), f"HEAD:refs/heads/{branch}")
        return remote, self.git("rev-parse", "HEAD").stdout.strip()

    def setUp(self):
        super().setUp()
        self.add_catalog(UPSTREAM_CATALOG + provenance_table())
        self.base = self.git("rev-parse", "HEAD").stdout.strip()  # The catalog is on the base.
        self.signal, self.signal_commit = self.upstream("signal", "main")
        self.git("tag", "-a", "-m", "Signal release", "v1.0", self.signal_commit)
        self.git("push", str(self.signal), "refs/tags/v1.0")
        self.molly, self.molly_commit = self.upstream("molly", "main")
        self.git("switch", "main")
        self.git("switch", "-c", "topic")
        self.commit(GOOD)

    def merge(self, commit, trailers):
        self.git("merge", "-s", "ours", "--allow-unrelated-histories", "--no-edit", commit)
        self.git("commit", "--amend", "--file=-", input=f"Merge upstream\n\n{trailers}\n")

    def check(self):
        checker = load_checker()
        verifier = checker.Upstream({SIGNAL: str(self.signal), MOLLY: str(self.molly)})
        stdout, stderr = io.StringIO(), io.StringIO()
        cwd = os.getcwd()
        os.chdir(self.repo)
        try:
            with mock.patch.dict(os.environ, self.env, clear=True), \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                valid = checker.check_range(self.base, "HEAD", 202, verifier)
        finally:
            os.chdir(cwd)
        return valid, stdout.getvalue(), stderr.getvalue()

    def assert_rejected(self, message):
        valid, _stdout, stderr = self.check()
        self.assertFalse(valid)
        self.assertIn(message, stderr)

    def test_signal_tag_merge_skips_upstream_commits(self):
        self.merge(self.signal_commit, f"Layer: upgrade\nUpstream: {SIGNAL} v1.0")
        valid, stdout, stderr = self.check()
        self.assertTrue(valid, stderr)
        self.assertIn(f"({SIGNAL} v1.0): skipped 3 upstream commits", stdout)
        self.assertIn("checked 1 non-merge commits, skipped 3 upstream commits", stdout)

    def test_molly_merge_of_an_earlier_branch_commit(self):
        older = self.git("rev-parse", f"{self.molly_commit}^").stdout.strip()
        self.merge(older, f"Layer: upgrade\nUpstream: {MOLLY} main@{older}")
        valid, stdout, stderr = self.check()
        self.assertTrue(valid, stderr)
        self.assertIn("skipped 2 upstream commits", stdout)
        # The scratch repository for the blob-less fetch leaves nothing behind.
        self.assertEqual(self.git("config", "--get-regexp", "promisor|partialclone", check=False).stdout, "")
        self.assertEqual(list(Path(self.git("rev-parse", "--git-common-dir").stdout.strip()).glob("collie-upstream-check-*")), [])

    def test_wrong_second_parent_is_rejected(self):
        older = self.git("rev-parse", f"{self.signal_commit}^").stdout.strip()
        self.merge(older, f"Layer: upgrade\nUpstream: {SIGNAL} v1.0")
        self.assert_rejected(f"is not {SIGNAL} v1.0")
        self.git("reset", "--hard", "HEAD^")
        self.merge(older, f"Layer: upgrade\nUpstream: {MOLLY} main@{self.molly_commit}")
        self.assert_rejected("is not the declared")

    def test_molly_commit_must_be_on_the_declared_branch(self):
        self.merge(self.signal_commit, f"Layer: upgrade\nUpstream: {MOLLY} main@{self.signal_commit}")
        self.assert_rejected(f"which is not on {MOLLY} main")

    def test_undeclared_or_malformed_upstream_is_rejected(self):
        for trailer, message in (
            ("Upstream: someone/Signal-Android v1.0", "only signalapp/Signal-Android and mollyim/mollyim-android"),
            ("Upstream: johanw666/Signal-Android v1.0", "only signalapp/Signal-Android and mollyim/mollyim-android"),
            (f"Upstream: {SIGNAL} v2.0", "which signalapp/Signal-Android does not have"),
            (f"Upstream: {MOLLY} main", "<branch>@<full-sha>"),
            (f"Upstream: {SIGNAL} v1.0\nUpstream: {SIGNAL} v1.0", "exactly one 'Upstream:'"),
        ):
            with self.subTest(trailer=trailer):
                self.merge(self.signal_commit, f"Layer: upgrade\n{trailer}")
                self.assert_rejected(message)
                self.git("reset", "--hard", "HEAD^")

    def test_upstream_merge_needs_one_upstream_layer(self):
        for layers, message in (
            ("", "needs exactly one 'Layer:"),
            ("Layer: website\n", "needs a Layer of kind 'upstream'"),
            ("Layer: relay\n", "needs a Layer of kind 'upstream'"),
            ("Layer: upgrade\nLayer: upgrade\n", "needs exactly one 'Layer:"),
        ):
            with self.subTest(layers=layers):
                self.merge(self.signal_commit, f"{layers}Upstream: {SIGNAL} v1.0")
                self.assert_rejected(message)
                self.git("reset", "--hard", "HEAD^")

    def test_collie_commit_without_trailer_on_the_first_parent_side_still_fails(self):
        self.commit("Collie change hidden before the upstream merge")
        self.merge(self.signal_commit, f"Layer: upgrade\nUpstream: {SIGNAL} v1.0")
        self.assert_rejected("needs exactly one 'Layer:")

    def test_selected_source_provenance_does_not_exempt_its_commit(self):
        self.git("switch", "--orphan", "selected-source")
        self.commit("Selected fork feature without a Layer trailer")
        selected = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("switch", "topic")
        self.add_catalog(UPSTREAM_CATALOG + provenance_table(
            revision=selected, revision_kind="feature-commit", relationship="direct-import", application="mechanical"
        ))
        self.git("merge", "-s", "ours", "--allow-unrelated-histories", "--no-edit", selected)
        self.assert_rejected("needs exactly one 'Layer:")

    def test_upstream_commits_do_not_count_as_features(self):
        self.commit("Relay change\n\nLayer: relay\n")
        self.merge(self.signal_commit, f"Layer: upgrade\nUpstream: {SIGNAL} v1.0")
        valid, stdout, stderr = self.check()
        self.assertTrue(valid, stderr)
        self.assertIn("feature layer: relay.", stdout)

    def test_pre_push_can_skip_only_the_network_check_offline(self):
        self.bare_remote()
        self.git("update-ref", "refs/remotes/origin/main", self.base)
        self.install()
        self.merge(self.signal_commit, f"Layer: upgrade\nUpstream: {SIGNAL} v1.0")
        self.env["COLLIE_UPSTREAM_OFFLINE"] = "1"
        pushed = self.git("push", "origin", "HEAD:refs/heads/topic")
        self.assertIn("offline", pushed.stderr)
        self.assertIn("skipped 3 upstream commits", pushed.stderr + pushed.stdout)
        self.git("commit", "--amend", "-m", "Merge upstream", "-m", f"Layer: website\nUpstream: {SIGNAL} v1.0")
        rejected = self.git("push", "-f", "origin", "HEAD:refs/heads/topic", check=False)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("needs a Layer of kind 'upstream'", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
