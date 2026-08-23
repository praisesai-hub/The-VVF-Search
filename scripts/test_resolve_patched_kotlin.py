#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from resolve_patched_kotlin import select_latest_patched_version


class ResolvePatchedKotlinTest(unittest.TestCase):
    def test_selects_latest_version_at_or_above_patched_floor(self):
        versions = [
            "2.4.10",
            "2.4.20-Beta1",
            "2.4.20-RC",
            "2.4.20",
            "2.5.0-Alpha1",
        ]

        self.assertEqual("2.5.0-Alpha1", select_latest_patched_version(versions))

    def test_patch_floor_includes_first_patched_release(self):
        self.assertEqual(
            "2.4.20-Beta1",
            select_latest_patched_version(["2.4.19", "2.4.20-Beta1"]),
        )

    def test_no_patched_version_fails_closed(self):
        with self.assertRaises(ValueError):
            select_latest_patched_version(["2.4.10", "2.4.20-Alpha1"])

    def test_local_metadata_file_can_be_consumed_by_workflow_helper(self):
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / "maven-metadata.xml"
            metadata.write_text(
                "<metadata><versioning><versions>"
                "<version>2.4.19</version><version>2.4.20-Beta1</version>"
                "</versions></versioning></metadata>",
                encoding="utf-8",
            )

            self.assertTrue(metadata.exists())


if __name__ == "__main__":
    unittest.main()
