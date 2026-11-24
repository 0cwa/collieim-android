"""Real-Git tests for release-only provenance validation."""
import os
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("verify-release-prep.py").resolve()
RECIPE = Path(__file__).with_name("build-release.sh").resolve()
STAMP = Path(__file__).resolve().parents[1] / "collie-log/stamp.py"
VERSION = "8.35.2-1-rc1"
CODE = "177801"


class ReleasePreparationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Release fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        recipe_dir = self.root / "tooling/reproducible-builds"
        recipe_dir.mkdir(parents=True)
        shutil.copy2(SCRIPT, recipe_dir / SCRIPT.name)
        shutil.copy2(RECIPE, recipe_dir / RECIPE.name)
        notes_dir = self.root / "tooling/collie-log"
        notes_dir.mkdir(parents=True)
        shutil.copy2(STAMP, notes_dir / STAMP.name)
        (self.root / "tooling/gradle.properties.docker").write_text("org.gradle.jvmargs=-Xmx2g\n")
        (self.root / "gradle.properties").write_text("ordinary=retained\ncollieBuildRevision=old\ncollieBuildTimestamp=1\n")
        (self.root / "app.txt").write_text("original\n")
        self.git("add", ".")
        self.commit("source")
        self.source = self.git("rev-parse", "HEAD")
        self.timestamp = self.git("show", "-s", "--format=%ct", self.source)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True, stderr=subprocess.PIPE).strip()

    def commit(self, message):
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", message)

    def prepare(self, *, source=None, timestamp=None, extra="", include_code=True, edition="stable",
                add_record=True, add_changelog=True):
        source = source or self.source
        timestamp = timestamp or self.timestamp
        data = f"ordinary=retained\ncollieBuildRevision={source}\ncollieBuildTimestamp={timestamp}\nreleaseVersionName=8.35.2-1-rc1\n"
        if include_code:
            data += "releaseVersionCode=177801\n"
        (self.root / "gradle.properties").write_text(data + extra)
        if add_record:
            record = self.root / f"app/src/main/assets/collie-log/releases/{edition}/{VERSION}.txt"
            record.parent.mkdir(parents=True, exist_ok=True)
            if not record.exists() and not record.is_symlink():
                record.write_text(f"version: {VERSION}\ndate: 2026-11-24\n")
        if add_changelog:
            product = "android" if edition == "stable" else "trailblazer-beta"
            changelog = self.root / f"fastlane/metadata/{product}/en-US/changelogs/{CODE}.txt"
            changelog.parent.mkdir(parents=True, exist_ok=True)
            if not changelog.exists():
                changelog.write_text("")
        self.git("add", "-A")
        self.commit("prepare")

    def check(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.root, text=True, capture_output=True)

    def develop(self, name="app.txt", content="later work\n"):
        """Commit a change on top of the preparation and return the commit before it."""
        before = self.git("rev-parse", "HEAD")
        (self.root / name).write_text(content)
        self.git("add", "-A")
        self.commit("later development")
        return before

    def test_development_after_preparation_fails_without_a_base(self):
        self.prepare()
        self.develop()
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unapproved preparation change: app.txt", result.stderr)

    def test_development_after_preparation_passes_against_its_base(self):
        self.prepare()
        base = self.develop()
        result = self.check("--development-base", base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("development change", result.stdout + result.stderr)

    def test_preparation_is_still_checked_against_its_base(self):
        self.prepare(timestamp="1")
        self.assertNotEqual(self.check("--development-base", self.source).returncode, 0)
        self.git("reset", "--hard", self.source)
        (self.root / "app.txt").write_text("changed\n")
        self.prepare()
        self.assertNotEqual(self.check("--development-base", self.source).returncode, 0)

    def test_changed_release_properties_are_checked_as_preparation(self):
        self.prepare()
        base = self.develop()
        data = (self.root / "gradle.properties").read_text().replace("177801", "177802")
        (self.root / "gradle.properties").write_text(data)
        self.git("add", "-A")
        self.commit("bump without a new source")
        self.assertNotEqual(self.check("--development-base", base).returncode, 0)

    def test_form_feed_cannot_hide_a_release_property_in_a_comment(self):
        self.prepare()
        base = self.git("rev-parse", "HEAD")
        path = self.root / "gradle.properties"
        path.write_bytes(path.read_bytes().replace(b"releaseVersionName=", b"# comment\freleaseVersionName="))
        self.git("add", "gradle.properties")
        self.commit("hide the version name from Java properties")
        result = self.check("--development-base", base)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release requires exactly one of each release property", result.stderr)

    def test_base_cannot_be_the_checked_out_commit(self):
        self.prepare()
        self.develop()
        head = self.git("rev-parse", "HEAD")
        self.assertNotEqual(self.check("--development-base", head).returncode, 0)

    def test_development_base_cannot_be_an_unrelated_branch(self):
        self.prepare()
        base = self.git("rev-parse", "HEAD")
        self.git("switch", "-qc", "other")
        self.develop("other.txt")
        other = self.git("rev-parse", "HEAD")
        self.git("switch", "--detach", base)
        self.develop()
        self.assertNotEqual(self.check("--development-base", other).returncode, 0)

    def test_recipe_passes_the_development_base(self):
        self.prepare()
        base = self.develop()
        recipe = str(self.root / "tooling/reproducible-builds/build-release.sh")
        with tempfile.TemporaryDirectory() as out:
            env = {**os.environ, "CONTAINER": "no-such-container"}
            result = subprocess.run([recipe, out], cwd=self.root, text=True, capture_output=True, env=env)
            self.assertIn("unapproved preparation change", result.stderr)
            env["COLLIE_DEVELOPMENT_BASE"] = base
            result = subprocess.run([recipe, out], cwd=self.root, text=True, capture_output=True, env=env)
        self.assertIn("development change", result.stderr)
        self.assertIn("no-such-container", result.stderr)

    def test_development_build_does_not_require_preparation(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("development build", result.stdout)

    def test_recipe_is_executable(self):
        self.assertTrue(os.access(self.root / "tooling/reproducible-builds/build-release.sh", os.X_OK))

    def test_replacement_commit_cannot_supply_a_false_timestamp(self):
        tree = self.git("rev-parse", self.source + "^{tree}")
        env = {**os.environ, "GIT_AUTHOR_DATE": "1700000000 +0000", "GIT_COMMITTER_DATE": "1700000000 +0000"}
        replacement = subprocess.check_output(["git", "commit-tree", tree, "-m", "replacement"], cwd=self.root,
                                              text=True, env=env).strip()
        self.prepare(timestamp="1700000000")
        self.git("replace", self.source, replacement)
        self.assertNotEqual(self.check().returncode, 0)

    def test_grafted_history_is_rejected(self):
        self.prepare()
        (self.root / ".git/info/grafts").write_text(self.source + "\n")
        self.assertNotEqual(self.check().returncode, 0)

    def test_tracked_system_property_alias_cannot_hide_release(self):
        with (self.root / "gradle.properties").open("a") as stream:
            stream.write("systemProp.org.gradle.project.releaseVersionName=8.35.2-1-rc1\n")
        self.git("add", "gradle.properties")
        self.commit("tracked alias")
        self.assertNotEqual(self.check().returncode, 0)

    def test_tracked_jvm_args_cannot_override_release_properties(self):
        with (self.root / "gradle.properties").open("a") as stream:
            stream.write("org.gradle.jvmargs=-Dorg.gradle.project.releaseVersionCode=99\n")
        self.git("add", "gradle.properties")
        self.commit("tracked JVM argument")
        self.assertNotEqual(self.check().returncode, 0)

    def test_builder_properties_cannot_override_release_properties(self):
        path = self.root / "tooling/gradle.properties.docker"
        path.write_text("org.gradle.jvmargs=-Dorg.gradle.project.collieBuildRevision=other\n")
        self.git("add", "-A")
        self.commit("tracked builder override")
        self.assertNotEqual(self.check().returncode, 0)
        path.write_text("releaseVersionName=unexpected\n")
        self.git("add", "-A")
        self.commit("tracked builder release property")
        self.assertNotEqual(self.check().returncode, 0)

    def test_release_uses_full_source_commit_and_git_time(self):
        self.prepare()
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(self.source, result.stdout)
        self.assertIn(self.git("rev-parse", "HEAD^{tree}"), result.stdout)

    def test_wrong_time_or_abbreviated_revision_is_rejected(self):
        self.prepare(timestamp="1")
        self.assertNotEqual(self.check().returncode, 0)
        self.git("reset", "--hard", self.source)
        self.prepare(source=self.source[:12])
        self.assertNotEqual(self.check().returncode, 0)

    def test_source_must_be_an_ancestor(self):
        self.git("checkout", "--orphan", "other")
        self.git("commit", "--allow-empty", "-qm", "unrelated")
        unrelated = self.git("rev-parse", "HEAD")
        self.git("checkout", "--detach", self.source)
        self.prepare(source=unrelated)
        self.assertNotEqual(self.check().returncode, 0)

    def test_release_environment_override_is_rejected(self):
        self.prepare()
        result = subprocess.run([sys.executable, str(SCRIPT)], cwd=self.root, text=True, capture_output=True,
                                env={**os.environ, "ORG_GRADLE_PROJECT_releaseVersionCode": "177999"})
        self.assertNotEqual(result.returncode, 0)

    def test_environment_cannot_turn_a_development_checkout_into_a_release(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], cwd=self.root, text=True, capture_output=True,
                                env={**os.environ, "ORG_GRADLE_PROJECT_releaseVersionName": "8.35.2-1-rc1"})
        self.assertNotEqual(result.returncode, 0)

    def test_shallow_release_history_fails_until_source_is_fetched(self):
        self.prepare()
        with tempfile.TemporaryDirectory() as destination:
            checkout = Path(destination) / "clone"
            subprocess.run(["git", "clone", "-q", "--depth=1", self.root.as_uri(), str(checkout)], check=True)
            command = [sys.executable, str(SCRIPT)]
            self.assertNotEqual(subprocess.run(command, cwd=checkout, capture_output=True).returncode, 0)
            subprocess.run(["git", "fetch", "-q", "--unshallow"], cwd=checkout, check=True)
            result = subprocess.run(command, cwd=checkout, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_hidden_tracked_file_is_rejected(self):
        self.prepare()
        self.git("update-index", "--assume-unchanged", "app.txt")
        self.assertNotEqual(self.check().returncode, 0)

    def test_recipe_stops_before_container_for_invalid_preparation(self):
        self.prepare(timestamp="1")
        with tempfile.TemporaryDirectory() as out:
            result = subprocess.run([str(self.root / "tooling/reproducible-builds/build-release.sh"), out], cwd=self.root,
                                    text=True, capture_output=True, env={**os.environ, "CONTAINER": "no-such-container"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release timestamp does not match", result.stderr)
        self.assertNotIn("no-such-container", result.stderr)

    def test_recipe_continues_after_valid_preparation(self):
        self.prepare()
        with tempfile.TemporaryDirectory() as external:
            external = Path(external)
            output = external / "output"
            output.mkdir()
            (output / "fixture.apk").write_bytes(b"synthetic output")
            marker = external / "engine-was-called"
            engine = external / "fake-container"
            engine.write_text(f'''#!/bin/sh
test "$GIT_NO_REPLACE_OBJECTS" = 1 || exit 1
if [ "$1" = run ]; then
  case " $* " in *" -e GIT_NO_REPLACE_OBJECTS=1 "*) ;; *) exit 1 ;; esac
fi
touch '{marker}'
''')
            engine.chmod(0o755)
            result = subprocess.run([str(self.root / "tooling/reproducible-builds/build-release.sh"), str(output)], cwd=self.root,
                                    text=True, capture_output=True, env={**os.environ, "CONTAINER": str(engine)})
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(marker.is_file())

    def test_host_recipe_isolates_gradle_init_and_disables_git_replacements(self):
        dockerfile = self.root / 'tooling/reproducible-builds/Dockerfile'
        dockerfile.write_text('ARG NDK_VERSION="ndk"\nARG BUILD_TOOLS_VERSION="tools"\nARG COMPILE_SDK="platform"\n')
        (self.root / '.tool-versions').write_text('java temurin-test\n')
        wrapper = self.root / 'gradlew'
        wrapper.write_text('''#!/bin/sh
set -eu
test "$GIT_NO_REPLACE_OBJECTS" = 1
test ! -e "$GRADLE_USER_HOME/init.gradle"
test ! -e "$GRADLE_USER_HOME/init.d"
test -L "$GRADLE_USER_HOME/caches/build-cache-1"
test -d "$GRADLE_USER_HOME/caches/build-cache-1"
case "$(readlink "$GRADLE_USER_HOME/caches/build-cache-1")" in /*) ;; *) exit 1 ;; esac
test "$1" = --no-daemon
test "$2" = --gradle-user-home
test "$3" = "$GRADLE_USER_HOME"
test "$5" = "-PcollieReleasePrepared=${EXPECTED_PREPARED:-true}"
printf '%s' "$GRADLE_USER_HOME" > "$TEST_CAPTURE"
test "${TEST_FAIL:-0}" != 1
mkdir -p app/build/outputs/apk/prodWebsite/release
printf fixture > app/build/outputs/apk/prodWebsite/release/fixture.apk
''')
        wrapper.chmod(0o755)
        self.git('add', '-A')
        self.commit('add recipe fixture')
        self.source = self.git('rev-parse', 'HEAD')
        self.timestamp = self.git('show', '-s', '--format=%ct', self.source)
        self.prepare()
        with tempfile.TemporaryDirectory() as external:
            external = Path(external)
            home = external / 'home'
            for name in ('.local/opt/jdk-test/bin/java', 'Android/Sdk/ndk/ndk',
                         'Android/Sdk/build-tools/tools', 'Android/Sdk/platforms/platform'):
                target = home / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.touch()
            cache = home / '.cache/collie-build/gradle'
            (cache / 'init.d').mkdir(parents=True)
            (cache / 'init.gradle').write_text('throw new Exception("must not run")\n')
            (cache / 'init.d/override.gradle').write_text('throw new Exception("must not run")\n')
            capture = external / 'capture'
            env = {**os.environ, 'HOME': str(home), 'ANDROID_HOME': str(home / 'Android/Sdk'),
                   'BUILD_MODE': 'host', 'COLLIE_BUILD_CACHE': '1', 'COLLIE_BUILD_HOME': os.path.relpath(cache, self.root),
                   'TEST_CAPTURE': str(capture)}
            result = subprocess.run([str(self.root / 'tooling/reproducible-builds/build-release.sh'), str(external / 'out')],
                                    cwd=self.root, text=True, capture_output=True, env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(Path(capture.read_text()).exists())
            self.assertTrue((cache / 'init.gradle').exists())
            shutil.rmtree(self.root / 'app/build')
            capture.unlink()
            result = subprocess.run([str(self.root / 'tooling/reproducible-builds/build-release.sh'), str(external / 'out')],
                                    cwd=self.root, text=True, capture_output=True, env={**env, 'TEST_FAIL': '1'})
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(Path(capture.read_text()).exists())
            base = self.develop()
            result = subprocess.run([str(self.root / 'tooling/reproducible-builds/build-release.sh'), str(external / 'dev-out')],
                                    cwd=self.root, text=True, capture_output=True,
                                    env={**env, 'COLLIE_DEVELOPMENT_BASE': base, 'EXPECTED_PREPARED': 'false'})
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(Path(capture.read_text()).exists())

    def test_nonrelease_property_change_is_rejected(self):
        self.prepare(extra="unexpected=value\n")
        self.assertNotEqual(self.check().returncode, 0)

    def test_nonmetadata_source_change_is_rejected(self):
        (self.root / "app.txt").write_text("changed\n")
        self.prepare()
        self.assertNotEqual(self.check().returncode, 0)

    def test_unapproved_changelog_name_is_rejected(self):
        path = self.root / "fastlane/metadata/android/en-US/changelogs/177802.txt"
        path.parent.mkdir(parents=True)
        path.write_text("wrong version\n")
        self.prepare()
        self.assertNotEqual(self.check().returncode, 0)

    def test_nonascii_or_oversized_changelog_is_rejected(self):
        path = self.root / "fastlane/metadata/android/en-US/changelogs/177801.txt"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"a" * 501)
        self.prepare()
        self.assertNotEqual(self.check().returncode, 0)
        self.git("reset", "--hard", self.source)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\N{LATIN SMALL LETTER E WITH ACUTE}\n")
        self.prepare()
        self.assertNotEqual(self.check().returncode, 0)

    def test_symlinked_release_note_is_rejected(self):
        path = self.root / "app/src/main/assets/collie-log/releases/stable/8.35.2-1-rc1.txt"
        path.parent.mkdir(parents=True)
        path.symlink_to("/etc/passwd")
        self.prepare()
        self.assertNotEqual(self.check().returncode, 0)

    def test_dirty_untracked_file_is_rejected(self):
        self.prepare()
        (self.root / "untracked.txt").write_text("extra\n")
        self.assertNotEqual(self.check().returncode, 0)

    def test_duplicate_or_incomplete_release_fields_are_rejected(self):
        self.prepare(include_code=False)
        self.assertNotEqual(self.check().returncode, 0)
        self.git("reset", "--hard", self.source)
        self.prepare(extra="releaseVersionCode=177802\n")
        self.assertNotEqual(self.check().returncode, 0)

    def test_allowed_log_and_matching_changelog_are_accepted(self):
        pending = self.root / "tooling/collie-log/unreleased/7.txt"
        pending.parent.mkdir(parents=True)
        pending.write_text("old\n")
        self.git("add", "-A")
        self.commit("source with note")
        self.source = self.git("rev-parse", "HEAD")
        self.timestamp = self.git("show", "-s", "--format=%ct", self.source)
        pending.unlink()
        path = self.root / "app/src/main/assets/collie-log/releases/stable/8.35.2-1-rc1.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("version: 8.35.2-1-rc1\ndate: 2026-11-24\n\nold\n")
        self.prepare()
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_empty_maintenance_release_is_prepared(self):
        self.prepare()
        result = self.check("--build-state")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "true\n")

    def test_development_result_never_claims_preparation(self):
        self.assertEqual(self.check("--build-state").stdout, "false\n")
        self.prepare()
        base = self.develop()
        result = self.check("--development-base", base, "--build-state")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "false\n")

    def test_beta_requires_its_own_record_and_store_changelog(self):
        self.prepare(edition="beta")
        result = self.check("--edition", "beta", "--build-state")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "true\n")
        self.assertNotEqual(self.check("--edition", "stable").returncode, 0)

    def test_preparation_cannot_omit_its_record_or_changelog(self):
        for omitted in ("add_record", "add_changelog"):
            with self.subTest(omitted=omitted):
                self.git("reset", "--hard", self.source)
                self.prepare(**{omitted: False})
                result = self.check()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("must add the selected product", result.stderr)

    def test_current_record_cannot_duplicate_a_differently_named_source_record(self):
        alias = self.root / "app/src/main/assets/collie-log/releases/beta/old-alias.txt"
        alias.parent.mkdir(parents=True)
        alias.write_text(f"version: {VERSION}\ndate: 2026-11-23\n")
        self.git("add", "-A")
        self.commit("source record alias")
        self.source = self.git("rev-parse", "HEAD")
        self.timestamp = self.git("show", "-s", "--format=%ct", self.source)
        self.prepare(edition="beta")
        result = self.check("--edition", "beta", "--build-state")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactly one current release record", result.stderr)
        self.assertEqual(result.stdout, "")

    def add_pending_source(self, name, text, *, legacy=False):
        directory = "app/src/main/assets/collie-log/unreleased" if legacy else "tooling/collie-log/unreleased"
        path = self.root / directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        self.git("add", "-A")
        self.commit("pending source")
        self.source = self.git("rev-parse", "HEAD")
        self.timestamp = self.git("show", "-s", "--format=%ct", self.source)
        return path

    def test_named_and_even_empty_leftovers_block_release(self):
        for content in ("topic: look\nTheme changes appear immediately.\n", ""):
            with self.subTest(content=content):
                self.git("reset", "--hard", self.source)
                self.add_pending_source("theme-everywhere.txt", content)
                self.prepare()
                result = self.check()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("unconsumed pending", result.stderr)

    def test_legacy_packaged_fragments_are_not_silently_ignored(self):
        self.add_pending_source("7.txt", "Already merged.\n", legacy=True)
        self.prepare()
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("legacy pending assets", result.stderr)

    def test_consuming_named_fragments_preserves_every_entry(self):
        path = self.add_pending_source("theme-everywhere.txt", "topic: look\nTheme changes appear immediately.\n")
        path.unlink()
        record = self.root / f"app/src/main/assets/collie-log/releases/stable/{VERSION}.txt"
        record.parent.mkdir(parents=True)
        record.write_text(f"version: {VERSION}\ndate: 2026-11-24\n\ntopic: look\nTheme changes appear immediately.\n")
        self.prepare()
        self.assertEqual(self.check().returncode, 0)
        record.write_text(f"version: {VERSION}\ndate: 2026-11-24\n")
        self.git("add", "-A")
        self.commit("lose the consumed change")
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("preserve every pending source entry", result.stderr)

    def test_record_metadata_must_be_valid_for_product_and_version(self):
        invalid = (
            "version: another\ndate: 2026-11-24\n",
            f"version: {VERSION}\ndate: yesterday\n",
            f"version: {VERSION}\nversion: {VERSION}\ndate: 2026-11-24\n",
            f"version: {VERSION}\ndate: 2026-11-24\ntag: trailblazer-v{VERSION}\n",
        )
        for text in invalid:
            with self.subTest(text=text):
                self.git("reset", "--hard", self.source)
                record = self.root / f"app/src/main/assets/collie-log/releases/stable/{VERSION}.txt"
                record.parent.mkdir(parents=True, exist_ok=True)
                record.write_text(text)
                self.prepare()
                self.assertNotEqual(self.check().returncode, 0)

    def test_prepared_flag_cannot_be_injected_into_the_source_or_environment(self):
        self.prepare(extra="collieReleasePrepared=true\n")
        self.assertNotEqual(self.check().returncode, 0)
        self.git("reset", "--hard", self.source)
        result = subprocess.run([sys.executable, str(SCRIPT), "--build-state"], cwd=self.root,
                                text=True, capture_output=True,
                                env={**os.environ, "ORG_GRADLE_PROJECT_collieReleasePrepared": "true"})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_container_recipe_passes_the_verified_state_and_product(self):
        self.prepare(edition="beta")
        base = self.git("rev-parse", "HEAD")
        for development in (False, True):
            with self.subTest(development=development), tempfile.TemporaryDirectory() as external:
                if development:
                    self.develop()
                external = Path(external)
                output = external / "out"
                output.mkdir()
                (output / "fixture.apk").write_bytes(b"fixture")
                capture = external / "arguments.json"
                engine = external / "engine"
                engine.write_text("#!/usr/bin/env python3\nimport json, os, sys\n"
                                  "if sys.argv[1] == 'run':\n"
                                  "    with open(os.environ['ARGS_CAPTURE'], 'w') as output: json.dump(sys.argv[1:], output)\n")
                engine.chmod(0o755)
                env = {**os.environ, "CONTAINER": str(engine), "COLLIE_BUILD_EDITION": "beta",
                       "ARGS_CAPTURE": str(capture)}
                if development:
                    env["COLLIE_DEVELOPMENT_BASE"] = base
                result = subprocess.run([str(self.root / "tooling/reproducible-builds/build-release.sh"), str(output)],
                                        cwd=self.root, text=True, capture_output=True, env=env)
                self.assertEqual(result.returncode, 0, result.stderr)
                args = json.loads(capture.read_text())
                self.assertEqual(args[-3:], [":app:assembleProdWebsiteBeta", "app/build/outputs/apk/prodWebsite/beta",
                                             "false" if development else "true"])
                self.assertIn('"-PcollieReleasePrepared=$3"', args[args.index("-c") + 1])


if __name__ == "__main__":
    unittest.main()
