from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from build_release_osv_inventory import to_inventory
from gradle_dependency_parser import coordinate_tuples
from build_release_sbom import to_sbom
from check_release_dependency_policy import load_coordinates as load_policy_coordinates
from check_release_dependency_policy import violations


REPORT = """\
------------------------------------------------------------
Root project 'VVF Smart Manager'
------------------------------------------------------------
+--- org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21 -> 2.4.20-RC
+--- org.bouncycastle:bcprov-jdk18on:1.79 -> 1.85.2
+--- org.apache.commons:commons-lang3:3.16.0 -> 3.18.0
+--- com.google.android.material:material:1.13.0
"""


class ReleaseDependencyGateTest(unittest.TestCase):
    def write_report(self, root: Path, text: str = REPORT) -> Path:
        report = root / "dependencies.txt"
        report.write_text(text, encoding="utf-8")
        return report

    def test_policy_accepts_patched_resolved_coordinates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = self.write_report(Path(directory))
            coordinates = load_policy_coordinates([report])
            policy = {
                "forbidden_prefixes": [],
                "critical_minimum_versions": [
                    {
                        "coordinate": "org.jetbrains.kotlin:kotlin-gradle-plugin",
                        "minimum_version": "2.4.20-Beta1",
                        "reason": "patched",
                    },
                    {
                        "coordinate": "org.bouncycastle:bcprov-jdk18on",
                        "minimum_version": "1.85.2",
                        "reason": "patched",
                    },
                ],
            }
            self.assertEqual(violations(policy, coordinates), [])

    def test_policy_rejects_forbidden_and_outdated_coordinates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = self.write_report(
                Path(directory),
                REPORT.replace("com.google.android.material:material:1.13.0", "org.yaml:snakeyaml:2.0")
                .replace("2.4.20-RC", "2.3.21"),
            )
            coordinates = load_policy_coordinates([report])
            policy = {
                "forbidden_prefixes": [
                    {"prefix": "org.yaml:snakeyaml", "reason": "not approved"}
                ],
                "critical_minimum_versions": [
                    {
                        "coordinate": "org.jetbrains.kotlin:kotlin-gradle-plugin",
                        "minimum_version": "2.4.20-Beta1",
                        "reason": "patched",
                    }
                ],
            }
            findings = violations(policy, coordinates)
            self.assertEqual(len(findings), 2)
            self.assertTrue(any("forbidden dependency" in finding for finding in findings))
            self.assertTrue(any("outdated critical dependency" in finding for finding in findings))

    def test_osv_inventory_and_sbom_include_selected_versions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = self.write_report(Path(directory))
            coordinates = coordinate_tuples([report])
            inventory = to_inventory(coordinates)
            sbom = to_sbom(coordinates)
            inventory_packages = inventory["results"][0]["packages"]
            self.assertTrue(
                any(
                    item["package"]["name"] == "org.jetbrains.kotlin:kotlin-gradle-plugin"
                    and item["package"]["version"] == "2.4.20-RC"
                    for item in inventory_packages
                )
            )
            self.assertEqual(sbom["bomFormat"], "CycloneDX")
            self.assertTrue(
                any(
                    component["purl"]
                    == "pkg:maven/org.jetbrains.kotlin/kotlin-gradle-plugin@2.4.20-RC"
                    for component in sbom["components"]
                )
            )
            json.dumps(inventory)
            json.dumps(sbom)


if __name__ == "__main__":
    unittest.main()
