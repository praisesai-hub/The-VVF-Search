"""Enforce forbidden-dependency and critical-version release policy rules."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from gradle_dependency_parser import load_coordinates


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="append", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    return parser.parse_args()


def numeric_version(version: str) -> tuple[int, ...]:
    match = re.match(r"^(\d+(?:\.\d+)*)", version)
    if not match:
        return (-1,)
    return tuple(int(part) for part in match.group(1).split("."))


def violations(policy: dict, coordinates: dict[str, set[str]]) -> list[str]:
    findings: list[str] = []
    for rule in policy.get("forbidden_prefixes", []):
        prefix = str(rule["prefix"])
        for coordinate in sorted(coordinates):
            if coordinate == prefix or coordinate.startswith(f"{prefix}:"):
                findings.append(f"forbidden dependency {coordinate}: {rule['reason']}")

    for rule in policy.get("critical_minimum_versions", []):
        coordinate = str(rule["coordinate"])
        minimum = numeric_version(str(rule["minimum_version"]))
        for version in sorted(coordinates.get(coordinate, set())):
            if numeric_version(version) < minimum:
                findings.append(
                    f"outdated critical dependency {coordinate}:{version}; "
                    f"minimum {rule['minimum_version']}: {rule['reason']}"
                )
    return findings


def main() -> int:
    arguments = parse_arguments()
    try:
        policy = json.loads(arguments.policy.read_text(encoding="utf-8"))
        coordinates = load_coordinates(arguments.report)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"RELEASE DEPENDENCY POLICY FAILURE: {error}", file=sys.stderr)
        return 1

    findings = violations(policy, coordinates)
    if findings:
        for finding in findings:
            print(finding, file=sys.stderr)
        return 1

    print(
        "Release dependency policy passed: "
        f"{len(coordinates)} resolved Maven coordinates inspected."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
