"""Keep published helper cache labels compatible with the Java local builder."""
import hashlib
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "helper_recipe", Path(__file__).with_name("helper-recipe.py")
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HelperRecipeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.dockerfile = self.root / "helper.Dockerfile"
        self.dockerfile.write_bytes(b"FROM scratch\n")
        self.manifest = self.root / "helper.Dockerfile.files"

    def test_legacy_dockerfile_only_digest_is_unchanged(self):
        expected = hashlib.sha256(self.dockerfile.read_bytes()).hexdigest()
        self.assertEqual(expected, MODULE.recipe(self.dockerfile))
        self.manifest.write_text("\n \n", encoding="utf-8")
        self.assertEqual(expected, MODULE.recipe(self.dockerfile))

    def test_declared_file_changes_invalidate_cache_and_match_java_vector(self):
        self.manifest.write_text("\n message.txt \n", encoding="utf-8")
        message = self.root / "message.txt"
        message.write_bytes(b"hello\n")
        original = MODULE.recipe(self.dockerfile)
        self.assertEqual("9ebbd75b37d6c7247eb4d72616765a848b1482e313ae89048ed1ccbf30b16b30", original)
        message.write_bytes(b"updated\n")
        self.assertNotEqual(original, MODULE.recipe(self.dockerfile))

    def test_undeclared_files_do_not_change_the_recipe(self):
        original = MODULE.recipe(self.dockerfile)
        (self.root / "unused.txt").write_text("not included", encoding="utf-8")
        self.assertEqual(original, MODULE.recipe(self.dockerfile))

    def test_manifest_order_does_not_change_recipe(self):
        (self.root / "first.txt").write_bytes(b"first")
        (self.root / "second.txt").write_bytes(b"second")
        self.manifest.write_text("first.txt\nsecond.txt\n", encoding="utf-8")
        original = MODULE.recipe(self.dockerfile)
        self.manifest.write_text("second.txt\nfirst.txt\n", encoding="utf-8")
        self.assertEqual(original, MODULE.recipe(self.dockerfile))

    def test_invalid_and_duplicate_filenames_fail(self):
        (self.root / "message.txt").write_bytes(b"hello")
        for manifest in ("../message.txt", "/message.txt", "Dockerfile",
                         "message.txt\nmessage.txt", "nested/message.txt", "message name.txt"):
            with self.subTest(manifest=manifest):
                self.manifest.write_text(manifest, encoding="utf-8")
                with self.assertRaises(ValueError):
                    MODULE.recipe(self.dockerfile)

    def test_missing_declared_file_fails(self):
        self.manifest.write_text("missing.txt", encoding="utf-8")
        with self.assertRaises(FileNotFoundError):
            MODULE.recipe(self.dockerfile)

    def test_published_context_contains_only_declared_files_and_exact_bytes(self):
        self.manifest.write_text("message.txt\n", encoding="utf-8")
        (self.root / "message.txt").write_bytes(b"hello\n")
        (self.root / "unrelated.txt").write_bytes(b"exclude me")
        for mask in (0o022, 0o077):
            with self.subTest(umask=oct(mask)):
                output = self.root / f"context-{mask:o}"
                previous = os.umask(mask)
                try:
                    digest = MODULE.recipe(self.dockerfile, output)
                finally:
                    os.umask(previous)

                self.assertEqual("9ebbd75b37d6c7247eb4d72616765a848b1482e313ae89048ed1ccbf30b16b30", digest)
                self.assertEqual({"Dockerfile", "message.txt"}, {path.name for path in output.iterdir()})
                self.assertEqual(b"FROM scratch\n", (output / "Dockerfile").read_bytes())
                self.assertEqual(b"hello\n", (output / "message.txt").read_bytes())
                self.assertEqual(0o755, stat.S_IMODE(output.stat().st_mode))
                for path in output.iterdir():
                    self.assertEqual(0o644, stat.S_IMODE(path.stat().st_mode))

    def test_staging_refuses_to_reuse_a_directory_with_unowned_files(self):
        output = self.root / "context"
        output.mkdir()
        existing = output / "Dockerfile"
        existing.write_bytes(b"preserve existing context")
        with self.assertRaises(FileExistsError):
            MODULE.recipe(self.dockerfile, output)
        self.assertEqual(b"preserve existing context", existing.read_bytes())


if __name__ == "__main__":
    unittest.main()
