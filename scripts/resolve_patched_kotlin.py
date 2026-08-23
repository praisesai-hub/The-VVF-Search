#!/usr/bin/env python3
"""Resolve the newest Kotlin Gradle plugin version at or above the patched floor."""
from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Iterable

VERSION_PATTERN = re.compile(
    r"^(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)"
    r"(?:-(?P<qualifier>[A-Za-z]+)(?P<qualifier_number>\d*)?)?$"
)
QUALIFIER_ORDER = {"dev": 0, "alpha": 1, "beta": 2, "rc": 3, "final": 4}
DEFAULT_METADATA_URL = (
    "https://repo1.maven.org/maven2/org/jetbrains/kotlin/"
    "kotlin-gradle-plugin/maven-metadata.xml"
)
DEFAULT_MINIMUM_VERSION = "2.4.20-Beta1"


def parse_version(version: str) -> tuple[int, int, int, int, int]:
    match = VERSION_PATTERN.fullmatch(version)
    if not match:
        raise ValueError(f"Unsupported Kotlin version format: {version}")
    qualifier = match.group("qualifier")
    qualifier_number = int(match.group("qualifier_number") or 0)
    return (
        int(match.group("major")),
        int(match.group("minor")),
        int(match.group("patch")),
        QUALIFIER_ORDER.get(qualifier.lower() if qualifier else "final", -1),
        qualifier_number,
    )


def select_latest_patched_version(
    versions: Iterable[str], minimum_version: str = DEFAULT_MINIMUM_VERSION
) -> str:
    minimum = parse_version(minimum_version)
    candidates: list[tuple[tuple[int, int, int, int, int], str]] = []
    for version in versions:
        try:
            parsed = parse_version(version)
        except ValueError:
            continue
        if parsed >= minimum:
            candidates.append((parsed, version))
    if not candidates:
        raise ValueError(f"No Kotlin version at or above {minimum_version} was found")
    return max(candidates)[1]


def read_metadata(url: str) -> bytes:
    if url.startswith("file:"):
        with urllib.request.urlopen(url, timeout=20) as response:
            return response.read()
    if not url.startswith("https://"):
        raise ValueError("Maven metadata URL must use HTTPS")
    request = urllib.request.Request(url, headers={"User-Agent": "VVF-Kotlin-Recheck/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata-url", default=DEFAULT_METADATA_URL)
    parser.add_argument("--metadata-file", type=Path)
    parser.add_argument("--minimum-version", default=DEFAULT_MINIMUM_VERSION)
    args = parser.parse_args()

    try:
        metadata = (
            args.metadata_file.read_bytes()
            if args.metadata_file is not None
            else read_metadata(args.metadata_url)
        )
        versions = [
            element.text
            for element in ElementTree.fromstring(metadata).iter("version")
            if element.text
        ]
        selected = select_latest_patched_version(versions, args.minimum_version)
    except (OSError, ValueError, ElementTree.ParseError, urllib.error.URLError) as error:
        print(f"Kotlin patched-version resolution failed closed: {error}", file=sys.stderr)
        return 1

    print(f"version={selected}")
    print(f"minimum_version={args.minimum_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
