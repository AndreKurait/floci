"""Run the prefetch script with an owned fake Docker command; never contact a daemon."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(os.environ.get("PREFETCH_SCRIPT_UNDER_TEST", ROOT / ".github/ci/prefetch-images.sh"))
FIXTURE = "RdsTemplateDatabaseRestoreDockerNightlyIntegrationTest"


class PostgisPrefetchTest(unittest.TestCase):
    def run_prefetch(self, selected=True, profile_image=None, missing_profile=False, inline_image=None):
        with tempfile.TemporaryDirectory(prefix="postgis-prefetch-test-") as directory:
            root = Path(directory)
            test_sources = root / "src/test/java/renamed"
            test_sources.mkdir(parents=True)
            fixture = next((ROOT / "src/test/java").rglob(FIXTURE + ".java"))
            shutil.copyfile(fixture, test_sources / fixture.name)
            profile = next((ROOT / "src/test/java").rglob("CloudFormationSettingsProfile.java"))
            text = profile.read_text()
            if profile_image is not None:
                text = text.replace("postgis/postgis:17-3.5-alpine", profile_image)
            if not missing_profile:
                (test_sources / profile.name).write_text(text)
            if inline_image is not None:
                with (test_sources / fixture.name).open("a") as out:
                    out.write('\nMap.entry("floci.services.rds.default-postgres-image", "' + inline_image + '");\n')
            (root / "src/main/resources").mkdir(parents=True)
            (root / "src/main/java").mkdir(parents=True)
            shutil.copyfile(ROOT / "src/main/resources/application.yml", root / "src/main/resources/application.yml")
            binary = root / "bin"
            binary.mkdir()
            docker = binary / "docker"
            docker.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$PREFETCH_DOCKER_CALLS"\n')
            docker.chmod(0o755)
            shard = root / "shard.txt"
            shard.write_text(FIXTURE + "\n" if selected else "UnrelatedUnitTest\n")
            calls = root / "docker-calls"
            calls.touch()
            log = root / "prefetch.log"
            env = {**os.environ, "PATH": str(binary) + os.pathsep + os.environ["PATH"],
                   "PREFETCH_DOCKER_CALLS": str(calls), "PREFETCH_LOG": str(log)}
            result = subprocess.run(["bash", str(SCRIPT), str(shard)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            return calls.read_text().splitlines(), log.read_text()

    def test_current_named_profile_image_is_prefetched(self):
        calls, _ = self.run_prefetch()
        self.assertIn("pull -q postgis/postgis:17-3.5-alpine", calls)

    def test_profile_override_is_read_instead_of_copying_an_image_pin(self):
        calls, _ = self.run_prefetch(profile_image="example.invalid/owned-postgis:test")
        self.assertIn("pull -q example.invalid/owned-postgis:test", calls)
        self.assertFalse(any("postgis/postgis:" in call for call in calls))

    def test_unselected_fixture_does_not_pull_its_image(self):
        calls, _ = self.run_prefetch(selected=False)
        self.assertFalse(any("postgis" in call for call in calls))

    def test_unreadable_profile_keeps_best_effort_skip_diagnostic(self):
        calls, log = self.run_prefetch(missing_profile=True)
        self.assertFalse(any("postgis" in call for call in calls))
        self.assertIn(FIXTURE + " default-postgres-image not readable", log)

    def test_inline_fixture_override_retains_precedence(self):
        calls, _ = self.run_prefetch(inline_image="example.invalid/inline-postgis:test")
        self.assertIn("pull -q example.invalid/inline-postgis:test", calls)
        self.assertFalse(any("postgis/postgis:" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
