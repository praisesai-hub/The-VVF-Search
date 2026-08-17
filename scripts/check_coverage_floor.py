#!/usr/bin/env python3
"""Enforce a conservative instruction-coverage floor from a JaCoCo XML report."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as element_tree
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--minimum-instruction-percent", type=float, required=True)
    args = parser.parse_args()

    if not args.report.is_file():
        print(f"Coverage report not found: {args.report}", file=sys.stderr)
        return 1

    root = element_tree.parse(args.report).getroot()
    counter = next(
        (item for item in root.findall("counter") if item.attrib.get("type") == "INSTRUCTION"),
        None,
    )
    if counter is None:
        print("Instruction coverage counter is missing from JaCoCo report.", file=sys.stderr)
        return 1

    missed = int(counter.attrib["missed"])
    covered = int(counter.attrib["covered"])
    total = missed + covered
    percent = 100.0 * covered / total if total else 100.0
    print(f"Instruction coverage: {percent:.2f}% ({covered}/{total})")
    if percent < args.minimum_instruction_percent:
        print(
            f"Coverage floor failed: {percent:.2f}% is below "
            f"{args.minimum_instruction_percent:.2f}%.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
