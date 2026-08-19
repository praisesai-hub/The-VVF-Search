#!/usr/bin/env python3
"""Static architecture guard for the staged UI -> domain -> data migration."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java"

errors: list[str] = []
compat_files = {
    MAIN / "com/example/ui/MainViewModelCompat.kt",
    MAIN / "com/example/data/SmartManagerRepositoryCompat.kt",
}

# Compatibility extensions are allowed only in their dedicated files.
for path in MAIN.rglob("*.kt"):
    if path in compat_files:
        continue
    text = path.read_text(encoding="utf-8")
    if "SmartManagerRepositoryCompat" in text or "MainViewModelCompat" in text:
        errors.append(f"{path.relative_to(ROOT)} references a compatibility implementation directly")

# WorkManager policy belongs to WorkCoordinator, not repositories or ViewModels.
for path in (MAIN / "com/example/data").rglob("*.kt"):
    if path.name == "WorkCoordinator.kt":
        continue
    text = path.read_text(encoding="utf-8")
    if "WorkManager" in text or "OneTimeWorkRequestBuilder" in text:
        errors.append(f"{path.relative_to(ROOT)} contains direct WorkManager orchestration")

# New production code must not silently add another repository compatibility façade.
for path in MAIN.rglob("*.kt"):
    if path.name.endswith("Compat.kt") and path not in compat_files:
        errors.append(f"{path.relative_to(ROOT)} adds an unregistered compatibility layer")

# The two known shims must remain explicitly documented as deprecated migration surfaces.
compat_text = (MAIN / "com/example/data/SmartManagerRepositoryCompat.kt").read_text(encoding="utf-8")
if "@Deprecated" not in compat_text:
    errors.append("SmartManagerRepositoryCompat.kt is missing deprecation markers")

if errors:
    print("Architecture boundary violations:")
    print("\n".join(f"- {error}" for error in errors))
    sys.exit(1)

print("Architecture boundary validation passed")
