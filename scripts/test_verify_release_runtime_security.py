#!/usr/bin/env python3
import unittest

from verify_release_runtime_security import Rule, find_violations


class ReleaseRuntimeSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.rules = [
            Rule("deny-prefix", "io.netty", "any", "Netty must remain build-time only"),
            Rule("min-version", "org.bouncycastle:bcprov-jdk18on", "1.80.2", "patched floor"),
        ]

    def test_clean_runtime_passes(self) -> None:
        self.assertEqual(
            find_violations(
                self.rules,
                {
                    "com.squareup.okhttp3:okhttp": ["4.12.0"],
                    "org.bouncycastle:bcprov-jdk18on": ["1.85.2"],
                },
            ),
            [],
        )

    def test_forbidden_runtime_family_fails(self) -> None:
        violations = find_violations(
            self.rules,
            {"io.netty:netty-handler": ["4.1.100.Final"]},
        )
        self.assertEqual(len(violations), 1)
        self.assertIn("Netty", violations[0])

    def test_below_floor_crypto_version_fails(self) -> None:
        violations = find_violations(
            self.rules,
            {"org.bouncycastle:bcprov-jdk18on": ["1.79"]},
        )
        self.assertEqual(len(violations), 1)
        self.assertIn("1.79", violations[0])


if __name__ == "__main__":
    unittest.main()
