"""Enforce aggregate and scoped instruction-coverage floors from a JaCoCo XML report.

The policy is intentionally kept outside the workflow in JSON so that coverage
requirements are reviewable and cannot silently drift between CI jobs.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as element_tree
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class Counter:
    covered: int
    missed: int

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def percent(self) -> float:
        return 100.0 * self.covered / self.total if self.total else 100.0

    def __add__(self, other: "Counter") -> "Counter":
        return Counter(self.covered + other.covered, self.missed + other.missed)


def instruction_counter(element: element_tree.Element) -> Counter | None:
    for counter in element.findall("counter"):
        if counter.attrib.get("type") == "INSTRUCTION":
            return Counter(
                covered=int(counter.attrib["covered"]),
                missed=int(counter.attrib["missed"]),
            )
    return None


def package_name(package: element_tree.Element) -> str:
    return package.attrib.get("name", "").replace("/", ".")


def class_name(clazz: element_tree.Element) -> str:
    return clazz.attrib.get("name", "").replace("/", ".")


def sum_counters(counters: Iterable[Counter]) -> Counter:
    total = Counter(covered=0, missed=0)
    for counter in counters:
        total += counter
    return total


def selected_package_counter(
    packages: list[element_tree.Element], prefixes: list[str]
) -> Counter:
    selected = []
    for package in packages:
        name = package_name(package)
        if any(name == prefix or name.startswith(f"{prefix}.") for prefix in prefixes):
            counter = instruction_counter(package)
            if counter is not None:
                selected.append(counter)
    return sum_counters(selected)


def selected_class_counter(
    packages: list[element_tree.Element], pattern: re.Pattern[str]
) -> tuple[Counter, list[str]]:
    selected: list[Counter] = []
    selected_names: list[str] = []
    for package in packages:
        for clazz in package.findall("class"):
            name = class_name(clazz)
            if pattern.search(name):
                counter = instruction_counter(clazz)
                if counter is not None:
                    selected.append(counter)
                    selected_names.append(name)
    return sum_counters(selected), selected_names


def check_floor(scope: str, counter: Counter, minimum: float) -> bool:
    print(
        f"{scope}: instruction coverage {counter.percent:.2f}% "
        f"({counter.covered}/{counter.total}), minimum {minimum:.2f}%"
    )
    if counter.percent < minimum:
        print(
            f"Coverage floor failed for {scope}: {counter.percent:.2f}% is below "
            f"{minimum:.2f}%.",
            file=sys.stderr,
        )
        return False
    return True


def load_policy(path: Path) -> dict:
    try:
        policy = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"Unable to read coverage policy {path}: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    if not isinstance(policy, dict):
        print("Coverage policy must be a JSON object.", file=sys.stderr)
        raise SystemExit(1)
    return policy


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Enforce aggregate and package/class instruction-coverage floors."
    )
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    args = parser.parse_args()

    if not args.report.is_file():
        print(f"Coverage report not found: {args.report}", file=sys.stderr)
        return 1

    policy = load_policy(args.policy)
    root = element_tree.parse(args.report).getroot()
    aggregate = instruction_counter(root)
    if aggregate is None:
        print("Instruction coverage counter is missing from JaCoCo report.", file=sys.stderr)
        return 1

    packages = root.findall("package")
    passed = check_floor(
        "aggregate",
        aggregate,
        float(policy["aggregate_instruction_percent"]),
    )

    for gate in policy.get("package_gates", []):
        scope = str(gate["name"])
        prefixes = [str(prefix) for prefix in gate["prefixes"]]
        counter = selected_package_counter(packages, prefixes)
        if counter.total == 0:
            print(
                f"Coverage scope {scope!r} matched no instructions in the report.",
                file=sys.stderr,
            )
            passed = False
            continue
        passed = check_floor(
            scope,
            counter,
            float(gate["minimum_instruction_percent"]),
        ) and passed

    for gate in policy.get("class_gates", []):
        scope = str(gate["name"])
        pattern = re.compile(str(gate["class_name_regex"]))
        counter, selected_names = selected_class_counter(packages, pattern)
        if counter.total == 0:
            print(
                f"Coverage scope {scope!r} matched no classes in the report.",
                file=sys.stderr,
            )
            passed = False
            continue
        print(f"{scope}: matched {len(selected_names)} classes")
        passed = check_floor(
            scope,
            counter,
            float(gate["minimum_instruction_percent"]),
        ) and passed

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
