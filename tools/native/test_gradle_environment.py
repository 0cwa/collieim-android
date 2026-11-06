import tempfile
import unittest
from pathlib import Path
import native_driver

class GradleEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='gradle-home-')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.env = {'GRADLE_USER_HOME': str(self.home)}

    def test_clean_home_allows_existing_dependency_caches(self):
        (self.home / 'caches').mkdir()
        (self.home / 'wrapper').mkdir()
        native_driver.audit_gradle_environment(self.env)

    def test_inherited_jvm_injection_variables_are_rejected(self):
        for key in ('GRADLE_OPTS', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'GRADLE_HOME'):
            with self.subTest(key=key):
                with self.assertRaisesRegex(ValueError, key):
                    native_driver.audit_gradle_environment({**self.env, key:'malicious-option'})

    def test_user_init_scripts_are_rejected(self):
        for name in ('init.gradle', 'init.gradle.kts', 'gradle.properties', 'init.d/evil.gradle', 'init.d/evil.gradle.kts'):
            with self.subTest(name=name):
                path = self.home / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('malicious')
                with self.assertRaises(ValueError): native_driver.audit_gradle_environment(self.env)
                path.unlink()

    def test_symlinked_home_does_not_hide_init_script(self):
        target = self.home / 'actual'
        target.mkdir()
        (target / 'init.gradle').write_text('malicious')
        link = self.home / 'linked-home'
        link.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'init.gradle'):
            native_driver.audit_gradle_environment({'GRADLE_USER_HOME': str(link)})

    def test_symlinked_init_directory_is_rejected(self):
        (self.home / 'init.d').symlink_to(self.home / 'missing', target_is_directory=True)
        with self.assertRaises(ValueError): native_driver.audit_gradle_environment(self.env)
