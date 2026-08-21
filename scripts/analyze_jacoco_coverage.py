#!/usr/bin/env python3
"""Print the highest missed-instruction classes for each configured JaCoCo gate."""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as element_tree
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ClassCoverage:
    name: str
    covered: int
    missed: int

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def percent(self) -> float:
        return (self.covered / self.total * 100) if self.total else 100.0


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--limit", default=15, type=int)
    return parser.parse_args()


def instruction_counter(class_element: element_tree.Element) -> tuple[int, int]:
    counter = next(
        (item for item in class_element.findall("counter") if item.get("type") == "INSTRUCTION"),
        None,
    )
    if counter is None:
        return 0, 0
    return int(counter.get("covered", "0")), int(counter.get("missed", "0"))


def read_classes(report_path: Path) -> list[ClassCoverage]:
    root = element_tree.parse(report_path).getroot()
    return [
        ClassCoverage(
            name=element.get("name", "").replace("/", "."),
            covered=covered,
            missed=missed,
        )
        for element in root.findall(".//class")
        for covered, missed in [instruction_counter(element)]
        if covered + missed
    ]


def print_scope(name: str, matching: list[ClassCoverage], limit: int) -> None:
    covered = sum(item.covered for item in matching)
    missed = sum(item.missed for item in matching)
    total = covered + missed
    percent = covered / total * 100 if total else 100.0
    print(f"\n{name}: {percent:.2f}% ({covered}/{total}); {len(matching)} classes")
    print("missed\tcovered\tcoverage\tclass")
    for item in sorted(matching, key=lambda value: (-value.missed, value.name))[:limit]:
        print(f"{item.missed}\t{item.covered}\t{item.percent:.2f}%\t{item.name}")


def main() -> None:
    arguments = parse_arguments()
    policy = json.loads(arguments.policy.read_text(encoding="utf-8"))
    classes = read_classes(arguments.report)
    print_scope("aggregate", classes, arguments.limit)

    for gate in policy.get("package_gates", []):
        prefixes = tuple(gate["prefixes"])
        print_scope(
            gate["name"],
            [item for item in classes if item.name.startswith(prefixes)],
            arguments.limit,
        )

    for gate in policy.get("class_gates", []):
        pattern = re.compile(gate["class_name_regex"])
        print_scope(
            gate["name"],
            [item for item in classes if pattern.search(item.name)],
            arguments.limit,
        )


if __name__ == "__main__":
    main()
