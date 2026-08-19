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

# Bounded-context rules prevent identity, Drive authorization, transfer, and telemetry from collapsing.
context_rules = {
    "drive": (
        MAIN / "com/example/context/drive",
        {"FirebaseAuthManager", "FirebaseAuth", "FirebaseCrashlytics", "CredentialManager"},
    ),
    "cloud": (
        MAIN / "com/example/context/cloud",
        {"FirebaseAuthManager", "FirebaseAuth", "FirebaseCrashlytics", "GoogleAuthManagerFactory"},
    ),
}
for context_name, (directory, forbidden_symbols) in context_rules.items():
    if not directory.exists():
        continue
    for path in directory.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for symbol in forbidden_symbols:
            if symbol in text:
                errors.append(f"{path.relative_to(ROOT)} ({context_name}) references forbidden {symbol}")

application_bootstrap = MAIN / "com/example/VVFApplication.kt"
if application_bootstrap.exists():
    application_text = application_bootstrap.read_text(encoding="utf-8")
    if "FirebaseCrashlytics" in application_text or "FirebaseApp.initializeApp" in application_text:
        errors.append(f"{application_bootstrap.relative_to(ROOT)} contains direct telemetry provider bootstrap")

cloud_transfer_files = [
    MAIN / "com/example/data/CloudSyncEngine.kt",
    MAIN / "com/example/data/GoogleDriveProviderAdapter.kt",
    MAIN / "com/example/worker/CloudSyncWorker.kt",
]
for path in cloud_transfer_files:
    if not path.exists():
        continue
    text = path.read_text(encoding="utf-8")
    for symbol in ("FirebaseAuthManager", "FirebaseAuth", "FirebaseCrashlytics", "GoogleAuthManagerFactory"):
        if symbol in text:
            errors.append(f"{path.relative_to(ROOT)} (CloudTransfer) references forbidden {symbol}")

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
