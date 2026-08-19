"""Parse selected Maven coordinates from Gradle dependency reports."""

from __future__ import annotations

import re
from pathlib import Path


COORDINATE = re.compile(
    r"(?P<group>[A-Za-z0-9_.-]+):(?P<name>[A-Za-z0-9_.-]+):(?P<version>[A-Za-z0-9_.+\-]+)"
)
SELECTED_VERSION = re.compile(r"->\s*(?P<version>[A-Za-z0-9_.+\-]+)")


def load_coordinates(reports: list[Path]) -> dict[str, set[str]]:
    """Return coordinate -> selected versions from Gradle text reports.

    Gradle prints substitutions as `group:name:requested -> selected`; the
    arrow's version must replace the requested version for security decisions.
    """
    coordinates: dict[str, set[str]] = {}
    for report in reports:
        if not report.is_file() or report.stat().st_size == 0:
            raise ValueError(f"missing or empty Gradle report: {report}")
        for line in report.read_text(encoding="utf-8", errors="replace").splitlines():
            matches = list(COORDINATE.finditer(line))
            if not matches:
                continue
            match = matches[-1]
            version = match.group("version")
            selected = SELECTED_VERSION.search(line)
            if selected:
                version = selected.group("version")
            coordinate = f"{match.group('group')}:{match.group('name')}"
            coordinates.setdefault(coordinate, set()).add(version)
    if not coordinates:
        raise ValueError("no Maven coordinates found in Gradle reports")
    return coordinates


def coordinate_tuples(reports: list[Path]) -> set[tuple[str, str, str]]:
    coordinates = load_coordinates(reports)
    return {
        (*coordinate.split(":", 1), version)
        for coordinate, versions in coordinates.items()
        for version in versions
    }
