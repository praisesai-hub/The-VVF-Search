#!/usr/bin/env python3
"""Validate that known build-time alert families do not enter the app runtime graph.

This is a reachability guard, not a Dependabot replacement. It intentionally does
not dismiss build-time alerts; it prevents a future dependency change from turning
those families into shipped Android runtime dependencies.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from gradle_dependency_parser import load_coordinates


@dataclass(frozen=True)
class Rule:
    kind: str
    coordinate: str
    value: str
    reason: str


def fail(message: str) -> None:
    print(f"RELEASE RUNTIME SECURITY FAILURE: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    return parser.parse_args()


def load_rules(path: Path) -> list[Rule]:
    if not path.is_file():
        fail(f"missing policy file: {path}")
    rules: list[Rule] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|", 3)]
        if len(parts) != 4 or parts[0] not in {"deny-prefix", "min-version"}:
            fail(f"invalid policy syntax at {path}:{line_number}")
        rules.append(Rule(parts[0], parts[1], parts[2], parts[3]))
    if not rules:
        fail(f"policy contains no rules: {path}")
    return rules


def load_runtime_coordinates(report: Path) -> dict[str, list[str]]:
    try:
        coordinates = load_coordinates([report])
    except ValueError as error:
        fail(str(error))
    return {coordinate: sorted(versions) for coordinate, versions in coordinates.items()}


def version_tuple(version: str) -> tuple[int, ...]:
    # The policy uses released numeric versions only. Qualifiers are rejected as
    # lower than the patched numeric floor instead of being silently accepted.
    numbers = re.match(r"^(\d+(?:\.\d+)*)", version)
    if not numbers:
        return (-1,)
    return tuple(int(part) for part in numbers.group(1).split("."))


def find_violations(rules: list[Rule], coordinates: dict[str, list[str]]) -> list[str]:
    violations: list[str] = []
    for rule in rules:
        if rule.kind == "deny-prefix":
            for coordinate in sorted(coordinates):
                if coordinate == rule.coordinate or coordinate.startswith(f"{rule.coordinate}:"):
                    violations.append(f"{coordinate}: {rule.reason}")
        else:
            selected = [
                (coordinate, version)
                for coordinate, versions in coordinates.items()
                if coordinate == rule.coordinate
                for version in versions
            ]
            for coordinate, version in selected:
                if version_tuple(version) < version_tuple(rule.value):
                    violations.append(
                        f"{coordinate}:{version} is below patched minimum {rule.value}: {rule.reason}"
                    )
    return violations


def main() -> None:
    args = parse_args()
    rules = load_rules(args.policy)
    coordinates = load_runtime_coordinates(args.report)
    violations = find_violations(rules, coordinates)

    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        raise SystemExit(1)

    print(
        "Release runtime security policy passed: "
        f"{len(coordinates)} coordinates inspected; no forbidden alert family is shipped."
    )


if __name__ == "__main__":
    main()
