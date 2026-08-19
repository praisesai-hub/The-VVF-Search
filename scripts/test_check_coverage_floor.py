"""Regression tests for fail-closed JaCoCo coverage policy enforcement."""

from __future__ import annotations

import io
import json
import tempfile
import unittest
from pathlib import Path

import check_coverage_floor


def write_report(path: Path, aggregate: tuple[int, int], classes: list[tuple[str, int, int]]) -> None:
    class_xml = "".join(
        "<package name=\"ignored\"><class name=\"%s\"><counter type=\"INSTRUCTION\" "
        "missed=\"%s\" covered=\"%s\"/></class></package>" % (name, missed, covered)
        for name, missed, covered in classes
    )
    missed, covered = aggregate
    path.write_text(
        "<report>%s<counter type=\"INSTRUCTION\" missed=\"%s\" covered=\"%s\"/></report>"
        % (class_xml, missed, covered),
        encoding="utf-8",
    )


def write_policy(
    path: Path,
    aggregate_minimum: float,
    scopes: list[dict[str, object]],
    aggregate_selectors: list[str] | None = None,
) -> None:
    selectors = aggregate_selectors or ["package:com.example.security"]
    path.write_text(
        json.dumps(
            {
                "minimum_instruction_percent": aggregate_minimum,
                "aggregate_selectors": selectors,
                "scopes": scopes,
            }
        ),
        encoding="utf-8",
    )


class CoverageFloorPolicyTest(unittest.TestCase):
    def enforce(self, report: Path, policy: Path) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        result = check_coverage_floor.enforce_policy(report, policy, stdout, stderr)
        return result, stdout.getvalue(), stderr.getvalue()

    def test_passing_policy_requires_aggregate_and_every_scope(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(
                report,
                aggregate=(20, 80),
                classes=[
                    ("com/example/security/KeyStore", 5, 95),
                    ("com/example/data/VaultRepository", 5, 95),
                ],
            )
            write_policy(
                policy,
                75.0,
                [
                    {
                        "name": "security",
                        "minimum_instruction_percent": 90.0,
                        "selectors": ["package:com.example.security"],
                    },
                    {
                        "name": "vault",
                        "minimum_instruction_percent": 90.0,
                        "selectors": ["class:com.example.data.Vault"],
                    },
                ],
            )

            result, stdout, stderr = self.enforce(report, policy)

            self.assertEqual(0, result)
            self.assertIn("Aggregate JVM instruction coverage: 95.00% (95/100)", stdout)
            self.assertIn("Scope security: 95.00%", stdout)
            self.assertIn("Scope vault: 95.00%", stdout)
            self.assertEqual("", stderr)

    def test_scope_failure_fails_even_when_aggregate_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(
                report,
                aggregate=(20, 80),
                classes=[("com/example/security/KeyStore", 20, 80)],
            )
            write_policy(
                policy,
                70.0,
                [
                    {
                        "name": "security",
                        "minimum_instruction_percent": 90.0,
                        "selectors": ["package:com.example.security"],
                    }
                ],
            )

            result, _, stderr = self.enforce(report, policy)

            self.assertEqual(1, result)
            self.assertIn("Coverage scope security is 80.00%, below 90.00%", stderr)

    def test_aggregate_uses_explicit_handwritten_selectors_not_report_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(
                report,
                aggregate=(0, 100),
                classes=[
                    ("com/example/data/HandwrittenRepository", 40, 60),
                    ("com/example/data/GeneratedJsonAdapter", 0, 1_000),
                ],
            )
            write_policy(
                policy,
                70.0,
                [
                    {
                        "name": "repository",
                        "minimum_instruction_percent": 50.0,
                        "selectors": ["class:com.example.data.HandwrittenRepository"],
                    }
                ],
                aggregate_selectors=["class:com.example.data.HandwrittenRepository"],
            )

            result, stdout, stderr = self.enforce(report, policy)

            self.assertEqual(1, result)
            self.assertIn("Aggregate JVM instruction coverage: 60.00% (60/100)", stdout)
            self.assertIn("below 70.00%", stderr)

    def test_missing_aggregate_selectors_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(report, aggregate=(0, 100), classes=[])
            policy.write_text(
                json.dumps({"minimum_instruction_percent": 70, "scopes": []}),
                encoding="utf-8",
            )

            result, _, stderr = self.enforce(report, policy)

            self.assertEqual(1, result)
            self.assertIn("aggregate_selectors needs non-empty", stderr)

    def test_unmatched_scope_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(
                report,
                aggregate=(0, 100),
                classes=[("com/example/security/KeyStore", 0, 100)],
            )
            write_policy(
                policy,
                90.0,
                [
                    {
                        "name": "vault",
                        "minimum_instruction_percent": 95.0,
                        "selectors": ["class:com.example.data.Vault"],
                    }
                ],
            )

            result, _, stderr = self.enforce(report, policy)

            self.assertEqual(1, result)
            self.assertIn("matched no instrumented classes", stderr)

    def test_overlapping_selectors_do_not_double_count_a_class(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(
                report,
                aggregate=(0, 100),
                classes=[
                    ("com/example/data/VaultRepository", 50, 50),
                    ("com/example/data/OtherRepository", 0, 100),
                ],
            )
            write_policy(
                policy,
                70.0,
                [
                    {
                        "name": "data",
                        "minimum_instruction_percent": 70.0,
                        "selectors": [
                            "package:com.example.data",
                            "class:com.example.data.Vault",
                        ],
                    }
                ],
                aggregate_selectors=["package:com.example.data"],
            )

            result, stdout, _ = self.enforce(report, policy)

            self.assertEqual(0, result)
            self.assertIn("Scope data: 75.00% (150/200)", stdout)

    def test_malformed_policy_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "report.xml"
            policy = root / "policy.json"
            write_report(
                report,
                aggregate=(0, 100),
                classes=[("com/example/security/KeyStore", 0, 100)],
            )
            policy.write_text(
                json.dumps(
                    {
                        "minimum_instruction_percent": 70,
                        "aggregate_selectors": ["package:com.example.security"],
                        "scopes": [],
                    }
                ),
                encoding="utf-8",
            )

            result, _, stderr = self.enforce(report, policy)

            self.assertEqual(1, result)
            self.assertIn("must declare at least one non-empty scope", stderr)


if __name__ == "__main__":
    unittest.main()
