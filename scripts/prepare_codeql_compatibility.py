#!/usr/bin/env python3
"""Prepare a temporary Kotlin version that the hosted CodeQL extractor supports.

The production dependency graph remains authoritative. This script is invoked only
inside the CodeQL workflow after checkout, where the current CodeQL extractor may
lag behind a patched Kotlin compiler release. It deliberately edits a workspace
copy, never a committed production configuration.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

KOTLIN_VERSION_PATTERN = re.compile(r'(?m)^(\s*kotlin\s*=\s*")(\d+(?:\.\d+){2}(?:-[A-Za-z0-9.]+)?)("\s*)$')
DEFAULT_CODEQL_KOTLIN = "2.3.21"


def prepare_codeql_workspace(path: Path, supported_version: str = DEFAULT_CODEQL_KOTLIN) -> tuple[str, str]:
    text = path.read_text(encoding="utf-8")
    match = KOTLIN_VERSION_PATTERN.search(text)
    if not match:
        raise ValueError(f"Could not locate the Kotlin version declaration in {path}")

    production_version = match.group(2)
    if production_version == supported_version:
        return production_version, production_version

    updated = text[:match.start(2)] + supported_version + text[match.end(2):]
    path.write_text(updated, encoding="utf-8")
    return production_version, supported_version


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-file", type=Path, default=Path("gradle/libs.versions.toml"))
    parser.add_argument("--supported-version", default=DEFAULT_CODEQL_KOTLIN)
    args = parser.parse_args()

    production_version, selected_version = prepare_codeql_workspace(
        args.version_file,
        supported_version=args.supported_version,
    )
    print(
        "CodeQL compatibility workspace prepared: "
        f"production Kotlin {production_version} -> CodeQL Kotlin {selected_version}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
