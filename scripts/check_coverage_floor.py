#!/usr/bin/env python3
"""Fail closed when a JaCoCo XML report violates the committed coverage policy."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as element_tree
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO


@dataclass(frozen=True)
class InstructionCoverage:
    covered: int
    missed: int

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def percent(self) -> float:
        return 100.0 * self.covered / self.total if self.total else 100.0


@dataclass(frozen=True)
class CoverageScope:
    name: str
    baseline_instruction_percent: float
    minimum_instruction_percent: float
    selectors: tuple[str, ...]


@dataclass(frozen=True)
class CoveragePolicy:
    aggregate_baseline_instruction_percent: float
    aggregate_minimum_instruction_percent: float
    aggregate_selectors: tuple[str, ...]
    scopes: tuple[CoverageScope, ...]


def instruction_counter(node: element_tree.Element) -> InstructionCoverage | None:
    counter = next(
        (item for item in node.findall("counter") if item.attrib.get("type") == "INSTRUCTION"),
        None,
    )
    if counter is None:
        return None
    return InstructionCoverage(
        covered=int(counter.attrib["covered"]),
        missed=int(counter.attrib["missed"]),
    )


def normalize_name(value: str) -> str:
    return value.replace("/", ".")


def parse_percent(value: object, label: str) -> float:
    if not isinstance(value, (int, float)):
        raise ValueError(f"{label} must be numeric.")
    percent = float(value)
    if not 0.0 < percent <= 100.0:
        raise ValueError(f"{label} must be greater than 0 and at most 100.")
    return percent


def validate_selectors(value: object, label: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value or not all(
        isinstance(item, str)
        and item.startswith(("package:", "class:", "exact:"))
        and len(item.partition(":")[2]) > 0
        for item in value
    ):
        raise ValueError(f"{label} needs non-empty package:, class:, or exact: selectors.")
    return tuple(value)


def load_policy(policy_path: Path) -> CoveragePolicy:
    try:
        parsed = json.loads(policy_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Unable to read coverage policy {policy_path}: {error}") from error

    if not isinstance(parsed, dict):
        raise ValueError("Coverage policy root must be an object.")
    allowed_root_keys = {
        "baseline_instruction_percent",
        "minimum_instruction_percent",
        "aggregate_selectors",
        "scopes",
    }
    unexpected_root_keys = set(parsed) - allowed_root_keys
    if unexpected_root_keys:
        raise ValueError(f"Unexpected coverage policy keys: {sorted(unexpected_root_keys)}")
    aggregate = parse_percent(
        parsed.get("minimum_instruction_percent"),
        "minimum_instruction_percent",
    )
    aggregate_baseline = parse_percent(
        parsed.get("baseline_instruction_percent"),
        "baseline_instruction_percent",
    )
    if aggregate < aggregate_baseline + 5.0:
        raise ValueError(
            "minimum_instruction_percent must be at least five percentage points above "
            "baseline_instruction_percent."
        )
    aggregate_selectors = validate_selectors(
        parsed.get("aggregate_selectors"),
        "aggregate_selectors",
    )
    raw_scopes = parsed.get("scopes")
    if not isinstance(raw_scopes, list) or not raw_scopes:
        raise ValueError("Coverage policy must declare at least one non-empty scope.")

    scopes: list[CoverageScope] = []
    names: set[str] = set()
    for index, raw_scope in enumerate(raw_scopes):
        if not isinstance(raw_scope, dict):
            raise ValueError(f"Coverage scope {index} must be an object.")
        allowed_scope_keys = {
            "name",
            "baseline_instruction_percent",
            "minimum_instruction_percent",
            "selectors",
        }
        unexpected_scope_keys = set(raw_scope) - allowed_scope_keys
        if unexpected_scope_keys:
            raise ValueError(
                f"Coverage scope {index} has unexpected keys: {sorted(unexpected_scope_keys)}"
            )
        name = raw_scope.get("name")
        selectors = raw_scope.get("selectors")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"Coverage scope {index} requires a non-empty name.")
        if name in names:
            raise ValueError(f"Coverage scope name is duplicated: {name}")
        validated_selectors = validate_selectors(selectors, f"Coverage scope {name}")
        names.add(name)
        scopes.append(
            CoverageScope(
                name=name,
                baseline_instruction_percent=parse_percent(
                    raw_scope.get("baseline_instruction_percent"),
                    f"baseline_instruction_percent for scope {name}",
                ),
                minimum_instruction_percent=parse_percent(
                    raw_scope.get("minimum_instruction_percent"),
                    f"minimum_instruction_percent for scope {name}",
                ),
                selectors=validated_selectors,
            )
        )
        if scopes[-1].minimum_instruction_percent < scopes[-1].baseline_instruction_percent + 5.0:
            raise ValueError(
                f"minimum_instruction_percent for scope {name} must be at least five "
                "percentage points above its baseline_instruction_percent."
            )
    return CoveragePolicy(
        aggregate_baseline_instruction_percent=aggregate_baseline,
        aggregate_minimum_instruction_percent=aggregate,
        aggregate_selectors=aggregate_selectors,
        scopes=tuple(scopes),
    )


def class_matches_scope(class_name: str, selectors: tuple[str, ...]) -> bool:
    normalized_class_name = normalize_name(class_name)
    package_name = normalized_class_name.rpartition(".")[0]
    for selector in selectors:
        kind, _, prefix = selector.partition(":")
        normalized_prefix = normalize_name(prefix)
        if kind == "exact" and normalized_class_name == normalized_prefix:
            return True
        if kind == "class" and normalized_class_name.startswith(normalized_prefix):
            return True
        if kind == "package" and (
            package_name == normalized_prefix or package_name.startswith(f"{normalized_prefix}.")
        ):
            return True
    return False


def scoped_instruction_coverage(
    report_root: element_tree.Element,
    scope: CoverageScope,
) -> InstructionCoverage | None:
    matched_counters: list[InstructionCoverage] = []
    for class_node in report_root.findall(".//class"):
        if not class_matches_scope(class_node.attrib.get("name", ""), scope.selectors):
            continue
        counter = instruction_counter(class_node)
        if counter is not None:
            matched_counters.append(counter)
    if not matched_counters:
        return None
    return InstructionCoverage(
        covered=sum(counter.covered for counter in matched_counters),
        missed=sum(counter.missed for counter in matched_counters),
    )


def enforce_policy(
    report: Path,
    policy: Path,
    stdout: TextIO,
    stderr: TextIO,
) -> int:
    if not report.is_file():
        print(f"Coverage report not found: {report}", file=stderr)
        return 1
    if not policy.is_file():
        print(f"Coverage policy not found: {policy}", file=stderr)
        return 1
    try:
        coverage_policy = load_policy(policy)
        report_root = element_tree.parse(report).getroot()
    except (element_tree.ParseError, ValueError) as error:
        print(f"Coverage policy validation failed: {error}", file=stderr)
        return 1

    aggregate = scoped_instruction_coverage(
        report_root,
        CoverageScope(
            name="aggregate",
            baseline_instruction_percent=coverage_policy.aggregate_baseline_instruction_percent,
            minimum_instruction_percent=coverage_policy.aggregate_minimum_instruction_percent,
            selectors=coverage_policy.aggregate_selectors,
        ),
    )
    if aggregate is None:
        print("Aggregate JVM coverage selectors matched no instrumented classes.", file=stderr)
        return 1

    failures: list[str] = []
    print(
            "Aggregate JVM instruction coverage: "
            f"{aggregate.percent:.2f}% ({aggregate.covered}/{aggregate.total}), "
            f"baseline {coverage_policy.aggregate_baseline_instruction_percent:.2f}%, "
            f"minimum {coverage_policy.aggregate_minimum_instruction_percent:.2f}%.",
        file=stdout,
    )
    if aggregate.percent < coverage_policy.aggregate_minimum_instruction_percent:
        failures.append(
            f"Aggregate JVM instruction coverage {aggregate.percent:.2f}% is below "
            f"{coverage_policy.aggregate_minimum_instruction_percent:.2f}%."
        )

    for scope in coverage_policy.scopes:
        coverage = scoped_instruction_coverage(report_root, scope)
        if coverage is None:
            failures.append(f"Coverage scope {scope.name} matched no instrumented classes.")
            continue
        print(
            f"Scope {scope.name}: {coverage.percent:.2f}% "
            f"({coverage.covered}/{coverage.total}), "
            f"baseline {scope.baseline_instruction_percent:.2f}%, "
            f"minimum {scope.minimum_instruction_percent:.2f}%.",
            file=stdout,
        )
        if coverage.percent < scope.minimum_instruction_percent:
            failures.append(
                f"Coverage scope {scope.name} is {coverage.percent:.2f}%, below "
                f"{scope.minimum_instruction_percent:.2f}%."
            )

    if failures:
        for failure in failures:
            print(f"Coverage floor failed: {failure}", file=stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    args = parser.parse_args(argv)
    return enforce_policy(args.report, args.policy, sys.stdout, sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
