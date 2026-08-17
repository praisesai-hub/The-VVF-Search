#!/usr/bin/env python3
"""Fail CI when common live credentials or private-key blocks are tracked in source control."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("Google API key", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
    ("GitHub personal access token", re.compile(r"gh[pousr]_[A-Za-z0-9_]{20,}")),
    ("GitHub fine-grained token", re.compile(r"github_pat_[A-Za-z0-9_]{20,}")),
    ("OpenAI API key", re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{20,}")),
    ("private key material", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |)?PRIVATE KEY-----")),
)
ALLOWED_PATHS = {"scripts/check_no_secrets.py"}


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, check=True, stdout=subprocess.PIPE
    )
    return [ROOT / name for name in result.stdout.decode("utf-8").split("\0") if name]


def main() -> int:
    violations: list[str] = []
    tracked = tracked_files()
    tracked_names = {path.relative_to(ROOT).as_posix() for path in tracked}
    if "app/google-services.json" in tracked_names:
        violations.append("app/google-services.json must not be committed; provide it through CI secrets.")

    for path in tracked:
        relative = path.relative_to(ROOT).as_posix()
        if relative in ALLOWED_PATHS or path.suffix.lower() in {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ttf", ".woff", ".woff2", ".jar", ".keystore"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for name, pattern in RULES:
            if pattern.search(text):
                violations.append(f"{name} detected in tracked file: {relative}")

    if violations:
        print("Secret policy check failed:", file=sys.stderr)
        print("\n".join(f"- {item}" for item in violations), file=sys.stderr)
        return 1
    print("Secret policy check passed for tracked files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
