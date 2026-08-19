"""Build an OSV-Scanner custom inventory from resolved Gradle reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from gradle_dependency_parser import coordinate_tuples


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="append", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def to_inventory(coordinates: set[tuple[str, str, str]]) -> dict:
    packages = []
    for group, name, version in sorted(coordinates):
        packages.append(
            {
                "package": {
                    "name": f"{group}:{name}",
                    "version": version,
                    "ecosystem": "Maven",
                    "purl": f"pkg:maven/{group}/{name}@{version}",
                }
            }
        )
    return {
        "results": [
            {
                "source": {
                    "path": "resolved-gradle-dependencies",
                    "type": "lockfile",
                },
                "packages": packages,
            }
        ]
    }


def main() -> int:
    arguments = parse_arguments()
    try:
        inventory = to_inventory(coordinate_tuples(arguments.report))
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(
            json.dumps(inventory, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError) as error:
        print(f"RELEASE INVENTORY FAILURE: {error}", file=sys.stderr)
        return 1

    print(
        f"Built OSV custom inventory with "
        f"{len(inventory['results'][0]['packages'])} Maven packages: {arguments.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
