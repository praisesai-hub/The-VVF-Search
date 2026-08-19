"""Generate a CycloneDX 1.5 SBOM from resolved Gradle dependency reports."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from gradle_dependency_parser import coordinate_tuples


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="append", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def to_sbom(coordinates: set[tuple[str, str, str]]) -> dict:
    components = []
    for group, name, version in sorted(coordinates):
        components.append(
            {
                "type": "library",
                "group": group,
                "name": name,
                "version": version,
                "purl": f"pkg:maven/{group}/{name}@{version}",
            }
        )
    return {
        "$schema": "https://cyclonedx.org/schema/bom-1.5.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "timestamp": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "tools": [
                {
                    "vendor": "The-VVF-Search",
                    "name": "build_release_sbom.py",
                    "version": "1",
                }
            ],
        },
        "components": components,
    }


def main() -> int:
    arguments = parse_arguments()
    try:
        sbom = to_sbom(coordinate_tuples(arguments.report))
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(
            json.dumps(sbom, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError) as error:
        print(f"RELEASE SBOM FAILURE: {error}", file=sys.stderr)
        return 1

    print(
        f"Built CycloneDX SBOM with {len(sbom['components'])} components: "
        f"{arguments.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
