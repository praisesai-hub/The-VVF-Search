#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from prepare_codeql_compatibility import prepare_codeql_workspace


class PrepareCodeqlCompatibilityTest(unittest.TestCase):
    def test_rewrites_only_requested_workspace_file(self):
        with tempfile.TemporaryDirectory() as directory:
            version_file = Path(directory) / "libs.versions.toml"
            version_file.write_text('kotlin = "2.4.20-RC"\nother = "1.0.0"\n', encoding="utf-8")

            production, selected = prepare_codeql_workspace(version_file, "2.3.21")

            self.assertEqual("2.4.20-RC", production)
            self.assertEqual("2.3.21", selected)
            self.assertIn('kotlin = "2.3.21"', version_file.read_text(encoding="utf-8"))
            self.assertIn('other = "1.0.0"', version_file.read_text(encoding="utf-8"))

    def test_is_idempotent_when_already_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            version_file = Path(directory) / "libs.versions.toml"
            original = 'kotlin = "2.3.21"\n'
            version_file.write_text(original, encoding="utf-8")

            production, selected = prepare_codeql_workspace(version_file, "2.3.21")

            self.assertEqual("2.3.21", production)
            self.assertEqual("2.3.21", selected)
            self.assertEqual(original, version_file.read_text(encoding="utf-8"))

    def test_fails_closed_when_declaration_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            version_file = Path(directory) / "libs.versions.toml"
            version_file.write_text('compose = "2026.08.00"\n', encoding="utf-8")

            with self.assertRaises(ValueError):
                prepare_codeql_workspace(version_file, "2.3.21")


if __name__ == "__main__":
    unittest.main()
