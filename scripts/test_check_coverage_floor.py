from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_coverage_floor.py")


REPORT = """\
<report name="fixture">
  <counter type="INSTRUCTION" missed="10" covered="90"/>
  <package name="com/example/security">
    <class name="com/example/security/SecureStore">
      <counter type="INSTRUCTION" missed="1" covered="19"/>
    </class>
    <counter type="INSTRUCTION" missed="1" covered="19"/>
  </package>
  <package name="com/example/auth">
    <class name="com/example/auth/AuthManager">
      <counter type="INSTRUCTION" missed="0" covered="20"/>
    </class>
    <counter type="INSTRUCTION" missed="0" covered="20"/>
  </package>
  <package name="com/example/data">
    <class name="com/example/data/VaultRepository">
      <counter type="INSTRUCTION" missed="0" covered="20"/>
    </class>
    <class name="com/example/data/CloudSyncEngine">
      <counter type="INSTRUCTION" missed="2" covered="18"/>
    </class>
    <class name="com/example/data/FileRepository">
      <counter type="INSTRUCTION" missed="2" covered="18"/>
    </class>
    <counter type="INSTRUCTION" missed="4" covered="56"/>
  </package>
</report>
"""


class CoverageFloorCheckerTest(unittest.TestCase):
    def run_checker(self, policy: dict) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "report.xml"
            policy_file = root / "policy.json"
            report.write_text(REPORT, encoding="utf-8")
            policy_file.write_text(json.dumps(policy), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT), "--report", str(report), "--policy", str(policy_file)],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_aggregate_and_scoped_gates_pass(self) -> None:
        result = self.run_checker(
            {
                "aggregate_instruction_percent": 70.0,
                "package_gates": [
                    {
                        "name": "security-critical packages",
                        "prefixes": ["com.example.security", "com.example.auth"],
                        "minimum_instruction_percent": 90.0,
                    },
                    {
                        "name": "repository/data layer",
                        "prefixes": ["com.example.data"],
                        "minimum_instruction_percent": 85.0,
                    },
                ],
                "class_gates": [
                    {
                        "name": "vault",
                        "class_name_regex": r"(^|\.)com\.example\.data\.Vault.*",
                        "minimum_instruction_percent": 95.0,
                    },
                    {
                        "name": "cloud sync",
                        "class_name_regex": r"(^|\.)com\.example\.data\..*CloudSync.*",
                        "minimum_instruction_percent": 90.0,
                    },
                ],
            }
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("aggregate: instruction coverage 90.00%", result.stdout)
        self.assertIn("vault: instruction coverage 100.00%", result.stdout)

    def test_scoped_gate_failure_returns_nonzero(self) -> None:
        result = self.run_checker(
            {
                "aggregate_instruction_percent": 70.0,
                "package_gates": [
                    {
                        "name": "repository/data layer",
                        "prefixes": ["com.example.data"],
                        "minimum_instruction_percent": 95.0,
                    }
                ],
                "class_gates": [],
            }
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("repository/data layer", result.stderr)

    def test_unmatched_scope_returns_nonzero(self) -> None:
        result = self.run_checker(
            {
                "aggregate_instruction_percent": 70.0,
                "package_gates": [
                    {
                        "name": "missing package",
                        "prefixes": ["com.example.missing"],
                        "minimum_instruction_percent": 1.0,
                    }
                ],
                "class_gates": [],
            }
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("matched no instructions", result.stderr)


if __name__ == "__main__":
    unittest.main()
