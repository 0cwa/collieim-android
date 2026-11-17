"""Real-Git tests for release-only provenance validation."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("verify-release-prep.py").resolve()
RECIPE = Path(__file__).with_name("build-release.sh").resolve()


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

    def prepare(self, *, source=None, timestamp=None, extra="", include_code=True):
        source = source or self.source
        timestamp = timestamp or self.timestamp
        data = f"ordinary=retained\ncollieBuildRevision={source}\ncollieBuildTimestamp={timestamp}\nreleaseVersionName=8.35.2-1-rc1\n"
        if include_code:
            data += "releaseVersionCode=177801\n"
        (self.root / "gradle.properties").write_text(data + extra)
        self.git("add", "-A")
        self.commit("prepare")

    def check(self):
        return subprocess.run([sys.executable, str(SCRIPT)], cwd=self.root, text=True, capture_output=True)

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
        path = self.root / "app/src/main/assets/collie-log/releases/8.35.2-1-rc1.txt"
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
        pending = self.root / "app/src/main/assets/collie-log/unreleased/7.txt"
        pending.parent.mkdir(parents=True)
        pending.write_text("old\n")
        self.git("add", "-A")
        self.commit("source with note")
        self.source = self.git("rev-parse", "HEAD")
        self.timestamp = self.git("show", "-s", "--format=%ct", self.source)
        pending.unlink()
        for name in ("app/src/main/assets/collie-log/releases/8.35.2-1-rc1.txt", "fastlane/metadata/android/en-US/changelogs/177801.txt"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("release\n")
        self.prepare()
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
