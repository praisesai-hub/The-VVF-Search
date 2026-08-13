#!/usr/bin/env python3
"""Validate Gradle dependency reports produced by dependency compatibility CI.

This script verifies resolution health only. It does not determine whether a
version is secure; alert-to-CVE remediation requires authenticated Dependabot
advisory data and explicit dependency path review.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REQUIRED_CONFIGURATIONS = (
    "releaseRuntimeClasspath",
    "debugRuntimeClasspath",
)
DYNAMIC_CATALOG_VERSION = re.compile(
    r'^\s*[A-Za-z0-9_.-]+\s*=\s*"(?:latest\.|\+|\[|\().*"\s*$',
    re.IGNORECASE,
)
DYNAMIC_COORDINATE = re.compile(
    r'["\'][^"\']+:[^"\']+:(?:latest\.[^"\']*|[^"\']*\+|\[[^"\']*|\([^"\']*)["\']',
    re.IGNORECASE,
)


def fail(message: str) -> None:
    print(f"DEPENDENCY COMPATIBILITY FAILURE: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports-dir", type=Path, required=True)
    parser.add_argument("--expected-revision", required=True)
    parser.add_argument("--manifest", action="append", type=Path, default=[])
    return parser.parse_args()


def validate_metadata(reports_dir: Path, expected_revision: str) -> None:
    metadata = reports_dir / "metadata.txt"
    if not metadata.is_file():
        fail(f"missing metadata file: {metadata}")

    values: dict[str, str] = {}
    for raw_line in metadata.read_text(encoding="utf-8").splitlines():
        key, separator, value = raw_line.partition("=")
        if separator:
            values[key.strip()] = value.strip()

    actual_revision = values.get("revision")
    if actual_revision != expected_revision:
        fail(
            "report revision does not match the checked-out commit "
            f"(expected {expected_revision}, found {actual_revision or 'missing'})"
        )


def validate_report(reports_dir: Path, configuration: str) -> None:
    report = reports_dir / f"{configuration}.txt"
    if not report.is_file() or report.stat().st_size == 0:
        fail(f"missing or empty report for {configuration}")

    contents = report.read_text(encoding="utf-8", errors="replace")
    if configuration not in contents:
        fail(f"{configuration} header is absent from {report}")
    if " FAILED" in contents:
        fail(f"unresolved dependency marker found in {report}")
    if "+---" not in contents and "\\---" not in contents:
        fail(f"no resolved dependency tree entries found in {report}")


def validate_manifests(manifests: list[Path]) -> None:
    for manifest in manifests:
        if not manifest.is_file():
            fail(f"dependency manifest does not exist: {manifest}")
        for number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
            if DYNAMIC_CATALOG_VERSION.search(line) or DYNAMIC_COORDINATE.search(line):
                fail(
                    f"dynamic or changing dependency version in {manifest}:{number}; "
                    "use an explicit, reviewable version instead"
                )


def main() -> None:
    arguments = parse_arguments()
    validate_metadata(arguments.reports_dir, arguments.expected_revision)
    for configuration in REQUIRED_CONFIGURATIONS:
        validate_report(arguments.reports_dir, configuration)
    validate_manifests(arguments.manifest)
    print(
        "Dependency compatibility reports are complete: "
        "resolved debug/release graphs, matching revision, and no direct dynamic versions."
    )


if __name__ == "__main__":
    main()
