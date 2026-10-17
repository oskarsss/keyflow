import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


class ReleaseBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tools").mkdir()
        shutil.copyfile(Path(__file__).with_name("build_release.py"), self.root / "tools/build_release.py")
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text('versionName = "1.3.0"\n')
        (self.root / ".gitignore").write_text("artifacts/\napp/build/\n")
        wrapper = self.root / "gradlew"
        wrapper.write_text('''#!/usr/bin/env python3
import os
from pathlib import Path
assert Path(os.environ["KEYFLOW_KEYSTORE"]).is_file()
assert os.environ["KEYFLOW_STORE_PASSWORD"] == "test-password"
assert os.environ["KEYFLOW_KEY_PASSWORD"] == "test-password"
outputs = Path("app/build/outputs/apk/release")
outputs.mkdir(parents=True, exist_ok=True)
for abi in ("arm64-v8a", "x86_64"):
    (outputs / f"app-{abi}-release.apk").write_bytes(f"test signed {abi} APK".encode())
''')
        wrapper.chmod(0o755)
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Release tests", "-c", "user.email=tests@example.com",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "Fixture")
        self.git("tag", "v1.3.0")
        artifacts = self.root / "artifacts"
        artifacts.mkdir()
        self.key = artifacts / "keyflow-release.jks"
        self.key.write_bytes(b"test key")
        (artifacts / "release-password").write_text("test-password\n")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True)

    def build(self, *args, environment=None):
        env = {key: value for key, value in os.environ.items() if not key.startswith("KEYFLOW_")}
        env.update(environment or {})
        return subprocess.run([sys.executable, "tools/build_release.py", *args], cwd=self.root,
                              env=env, capture_output=True, text=True)

    def test_local_build_packages_the_committed_source_and_checksums(self):
        result = self.build("--tag", "v1.3.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        artifacts = self.root / "artifacts"
        apk = artifacts / "keyflow-1.3.0.apk"
        emulator = artifacts / "keyflow-1.3.0-x86_64.apk"
        source = artifacts / "keyflow-1.3.0-source.tar.gz"
        # Phones get the plain name; the emulator build is marked.
        self.assertEqual(apk.read_bytes(), b"test signed arm64-v8a APK")
        self.assertEqual(emulator.read_bytes(), b"test signed x86_64 APK")
        with tarfile.open(source) as archive:
            self.assertIn("keyflow-1.3.0/tools/build_release.py", archive.getnames())
            self.assertFalse(any("artifacts/" in name for name in archive.getnames()))
            self.assertEqual(archive.extractfile("keyflow-1.3.0/app/build.gradle.kts").read(),
                             b'versionName = "1.3.0"\n')
        checksums = (artifacts / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(checksums, [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}"
                                     for path in (apk, emulator, source)])

    def test_ci_signing_works_without_a_local_password_file(self):
        (self.root / "artifacts/release-password").unlink()
        result = self.build("--tag", "v1.3.0", environment={
            "KEYFLOW_KEYSTORE": str(self.key), "KEYFLOW_STORE_PASSWORD": "test-password",
            "KEYFLOW_KEY_PASSWORD": "test-password",
        })
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_incomplete_ci_signing_never_falls_back_to_local_credentials(self):
        result = self.build(environment={"KEYFLOW_KEYSTORE": str(self.key)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("together", result.stderr)
        self.assertFalse((self.root / "app/build").exists())

    def test_wrong_tag_or_dirty_source_cannot_be_released(self):
        result = self.build("--tag", "v1.2.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must match versionName", result.stderr)
        (self.root / "app/build.gradle.kts").write_text('versionName = "1.3.1"\n')
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Commit all changes", result.stderr)
        self.assertFalse((self.root / "app/build").exists())

    def test_tag_must_identify_the_checked_out_source(self):
        (self.root / "new-file").write_text("New commit")
        self.git("add", ".")
        self.git("-c", "user.name=Release tests", "-c", "user.email=tests@example.com",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "Later commit")
        result = self.build("--tag", "v1.3.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checked-out commit", result.stderr)
        self.assertFalse((self.root / "app/build").exists())


if __name__ == "__main__":
    unittest.main()
