from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from collect_release_evidence import main


class CollectReleaseEvidenceTest(unittest.TestCase):
    def test_manifest_records_artifact_digest_and_context(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "evidence"
            artifact = root / "app-release.aab"
            artifact.write_bytes(b"signed-aab-fixture")
            expected_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
            argv = [
                "collect_release_evidence.py",
                "--output-dir",
                str(output),
                "--repository",
                "example/repository",
                "--commit",
                "abc123",
                "--run-id",
                "42",
                "--version-name",
                "1.0.42",
                "--version-code",
                "42",
                "--workflow-status",
                "success",
                "--java-version",
                "openjdk 17",
                "--gradle-version",
                "Gradle 9.7",
                "--artifact",
                str(artifact),
            ]
            with patch.object(sys, "argv", argv):
                self.assertEqual(main(), 0)
            manifest = json.loads((output / "release-evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["schema"], "vvf.release-evidence/v1")
            self.assertEqual(manifest["commit"], "abc123")
            self.assertEqual(manifest["workflow_status"], "success")
            self.assertEqual(manifest["runner"]["java_version"], "openjdk 17")
            self.assertEqual(manifest["artifacts"][0]["sha256"], expected_digest)
            self.assertEqual(manifest["artifacts"][0]["size_bytes"], len(b"signed-aab-fixture"))

    def test_manifest_records_missing_artifacts_without_hiding_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "evidence"
            argv = [
                "collect_release_evidence.py",
                "--output-dir",
                str(output),
                "--repository",
                "example/repository",
                "--commit",
                "abc123",
                "--run-id",
                "42",
                "--version-name",
                "1.0.42",
                "--version-code",
                "42",
                "--workflow-status",
                "failure",
                "--java-version",
                "unknown",
                "--gradle-version",
                "unknown",
                "--artifact",
                str(root / "missing.aab"),
            ]
            with patch.object(sys, "argv", argv):
                self.assertEqual(main(), 0)
            manifest = json.loads((output / "release-evidence.json").read_text(encoding="utf-8"))
            self.assertFalse(manifest["artifacts"][0]["available"])
            self.assertEqual(manifest["workflow_status"], "failure")


if __name__ == "__main__":
    unittest.main()
