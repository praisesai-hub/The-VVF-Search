"""Collect a reproducible release evidence manifest with SHA-256 digests."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--workflow-status", required=True)
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--gradle-version", required=True)
    parser.add_argument("--artifact", action="append", type=Path, default=[])
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe(path: Path, workspace: Path) -> dict:
    if not path.is_file():
        return {
            "path": str(path),
            "available": False,
        }
    try:
        relative_path = str(path.resolve().relative_to(workspace.resolve()))
    except ValueError:
        relative_path = str(path)
    return {
        "path": relative_path,
        "available": True,
        "size_bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def main() -> int:
    arguments = parse_arguments()
    output_dir = arguments.output_dir.resolve()
    workspace = Path.cwd().resolve()
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        evidence_files = []
        for path in sorted(output_dir.rglob("*")):
            if path.is_file() and path.name != "release-evidence.json":
                evidence_files.append(describe(path, workspace))
        manifest = {
            "schema": "vvf.release-evidence/v1",
            "repository": arguments.repository,
            "commit": arguments.commit,
            "workflow_run_id": arguments.run_id,
            "workflow_status": arguments.workflow_status,
            "version_name": arguments.version_name,
            "version_code": arguments.version_code,
            "generated_at_utc": datetime.now(timezone.utc)
            .replace(microsecond=0)
            .isoformat(),
            "runner": {
                "os": os.environ.get("RUNNER_OS", "unknown"),
                "java_version": arguments.java_version,
                "gradle_version": arguments.gradle_version,
            },
            "artifacts": [describe(path, workspace) for path in arguments.artifact],
            "evidence_files": evidence_files,
        }
        (output_dir / "release-evidence.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError) as error:
        print(f"RELEASE EVIDENCE FAILURE: {error}", file=sys.stderr)
        return 1

    print(
        f"Release evidence manifest created with {len(evidence_files)} evidence files: "
        f"{output_dir / 'release-evidence.json'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
