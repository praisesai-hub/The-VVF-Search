#!/usr/bin/env python3
"""Deterministic, read-only security/compliance checks for an Android repository.

This checker performs source/configuration checks only. It does not claim runtime,
release-signing, dependency-vulnerability, or penetration-test compliance.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class Finding:
    control: str
    title: str
    status: str
    severity: str
    evidence: str
    remediation: str


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def files(root: Path, patterns: tuple[str, ...]) -> list[Path]:
    result: list[Path] = []
    for pattern in patterns:
        result.extend(root.glob(pattern))
    return sorted(set(p for p in result if p.is_file()))


def find_text(root: Path, patterns: tuple[str, ...], expression: str) -> list[str]:
    rx = re.compile(expression, re.IGNORECASE)
    hits: list[str] = []
    for path in files(root, patterns):
        for number, line in enumerate(read(path).splitlines(), 1):
            if rx.search(line):
                hits.append(f"{path.relative_to(root)}:{number}: {line.strip()[:180]}")
    return hits


def check(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    manifest = root / "app/src/main/AndroidManifest.xml"
    manifest_text = read(manifest)
    build_files = files(root, ("*.gradle", "*.gradle.kts", "app/*.gradle", "app/*.gradle.kts"))
    build_text = "\n".join(read(p) for p in build_files)
    source_patterns = ("app/src/main/**/*.kt", "app/src/main/**/*.java", "app/src/main/**/*.xml", "*.gradle*", "app/*.gradle*")

    def add(control: str, title: str, status: str, severity: str, evidence: str, remediation: str) -> None:
        findings.append(Finding(control, title, status, severity, evidence, remediation))

    cleartext = find_text(root, ("app/src/main/**/*.kt", "app/src/main/**/*.java", "app/src/main/**/*.xml"), r"http://(?!schemas\.android\.com/)")
    add("MASVS-NETWORK-1", "Cleartext HTTP references", "FAIL" if cleartext else "PASS", "HIGH" if cleartext else "INFO",
        "; ".join(cleartext[:5]) if cleartext else "No http:// reference found in scanned app sources/resources.",
        "Remove cleartext endpoints or document an approved exception; enforce HTTPS and verify Network Security Config.")

    secret_hits = find_text(root, source_patterns, r"(?i)(?<!KEY_)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\\s*[:=]\\s*['\\\"][^'\\\"]{12,}['\\\"]")
    add("MASVS-STORAGE-1", "Hardcoded credential-like literals", "FAIL" if secret_hits else "PASS", "CRITICAL" if secret_hits else "INFO",
        "; ".join(secret_hits[:8]) if secret_hits else "No credential-like assignment literal found by this heuristic.",
        "Move credentials to a secret manager/CI secret store and rotate any exposed credential. Review heuristic results manually.")

    token_pref = find_text(root, ("app/src/main/**/*.kt", "app/src/main/**/*.java"), r"SharedPreferences|KEY_ACCESS_TOKEN|KEY_REFRESH_TOKEN")
    add("MASVS-STORAGE-1", "Sensitive token persistence review", "PARTIAL" if token_pref else "PASS", "HIGH" if token_pref else "INFO",
        "; ".join(token_pref[:8]) if token_pref else "No matching token/preference reference found.",
        "Confirm that refresh tokens are encrypted with a Keystore-backed design, access tokens are short-lived, and backup exclusion is tested.")

    fallback = find_text(root, ("app/src/main/**/*.kt", "app/src/main/**/*.java"), r"fallbackKey|KeyGenerator\.getInstance\(['\"]AES")
    add("MASVS-CRYPTO-1", "Cryptographic key fallback review", "PARTIAL" if fallback else "PASS", "HIGH" if fallback else "INFO",
        "; ".join(fallback[:8]) if fallback else "No fallback key pattern found.",
        "Do not silently use an ephemeral key for durable vault data; fail closed or implement a documented, durable key-wrapping/recovery design.")

    backup = root / "app/src/main/res/xml/data_extraction_rules.xml"
    legacy_backup = root / "app/src/main/res/xml/backup_rules.xml"
    backup_text = read(backup) + read(legacy_backup)
    explicit_excludes = bool(re.search(r"<exclude\b", backup_text))
    add("MASVS-STORAGE-2", "Backup and restore rules", "PASS" if explicit_excludes else "PARTIAL", "HIGH",
        f"Manifest allowBackup/dataExtractionRules present={bool(re.search(r'allowBackup', manifest_text))}; explicit exclude rule={explicit_excludes}.",
        "Define explicit per-domain backup/transfer policy for vault files, tokens, database, cache, and keys; test restore and device transfer.")

    exported = re.findall(r"android:exported\s*=\s*\"(true|false)\"", manifest_text)
    main_exported = bool(re.search(r"android:name=\"\.MainActivity\"[\s\S]*?android:exported=\"true\"", manifest_text))
    add("MASVS-PLATFORM-1", "Exported component review", "PARTIAL" if main_exported else "PASS", "MEDIUM" if main_exported else "INFO",
        f"Manifest exported declarations={exported}; launcher activity exported={main_exported}.",
        "Keep only intentionally public components exported and validate all incoming intents/URI data. Verify merged manifest and runtime behavior.")

    broad_permissions = find_text(root, ("app/src/main/AndroidManifest.xml",), r"READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_")
    add("MASVS-PLATFORM-2", "Storage/media permission review", "PARTIAL" if broad_permissions else "PASS", "MEDIUM" if broad_permissions else "INFO",
        "; ".join(broad_permissions),
        "Request the narrowest permission at runtime for each API level, support scoped/partial media access, and test denial/revocation paths.")

    r8_disabled = bool(re.search(r"isMinifyEnabled\s*=\s*false", build_text, re.IGNORECASE))
    r8_present = bool(re.search(r"isMinifyEnabled\s*=\s*true", build_text, re.IGNORECASE))
    add("MASVS-RESILIENCE-1", "Release shrinking/obfuscation configuration", "PARTIAL" if not r8_present else "PASS", "MEDIUM" if not r8_present else "INFO",
        f"isMinifyEnabled=true present={r8_present}; explicit false present={r8_disabled}.",
        "Verify release-only R8/shrinking configuration, keep rules, mapping retention, and a signed release artifact. Do not disable R8 to bypass failures.")

    todo = find_text(root, ("app/src/main/**/*.kt", "app/src/main/**/*.java"), r"TODO|FIXME|NotImplemented|simulation|fake|mock|stub")
    add("MASVS-CODE-1", "Placeholder/unfinished implementation markers", "PARTIAL" if todo else "PASS", "MEDIUM" if todo else "INFO",
        "; ".join(todo[:10]) if todo else "No marker matched in app main sources.",
        "Review each marker and either implement, remove, or explicitly document it before release; never treat a mock as production behavior.")

    local_properties = root / "local.properties"
    tracked_secret_files = [p for p in files(root, ("**/local.properties", "**/*.keystore", "**/*.jks")) if ".git" not in p.parts]
    add("MASVS-CODE-2", "Local credentials/signing material in repository", "FAIL" if tracked_secret_files else "PASS", "CRITICAL" if tracked_secret_files else "INFO",
        "; ".join(str(p.relative_to(root)) for p in tracked_secret_files) if tracked_secret_files else "No local.properties/keystore/JKS file found in extracted tree.",
        "Keep signing credentials and local SDK configuration outside source control; rotate anything ever committed and scan full Git history.")

    test_files = files(root, ("app/src/test/**/*.kt", "app/src/androidTest/**/*.kt", "app/src/test/**/*.java", "app/src/androidTest/**/*.java"))
    add("MASVS-CODE-3", "Automated test inventory", "PASS" if test_files else "FAIL", "HIGH" if not test_files else "INFO",
        f"Detected {len(test_files)} test source/resource files.",
        "Add tests for auth expiry, backup/restore, Keystore invalidation, permissions, worker idempotency, crash recovery, and destructive operations.")

    wrapper = root / "gradlew"
    add("BUILD-1", "Reproducible Gradle wrapper", "PASS" if wrapper.exists() else "FAIL", "HIGH" if not wrapper.exists() else "INFO",
        f"gradlew present={wrapper.exists()}.", "Use the repository wrapper in CI and document pinned JDK/SDK/build-tools versions.")

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Run read-only Android security/compliance checks")
    parser.add_argument("root", type=Path)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    findings = check(root)
    payload = {"repository": str(root), "scope": "static source/configuration checks only", "findings": [asdict(f) for f in findings]}
    if args.json:
        args.json.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.markdown:
        lines = ["# Automated Security Compliance Check", "", f"Repository: `{root}`", "", "> Static checks only. Runtime, penetration testing, dependency CVE feeds, and release signing are not verified by this runner.", "", "| Control | Status | Severity | Evidence | Remediation |", "|---|---|---|---|---|"]
        for f in findings:
            evidence = f.evidence.replace("|", "\\|").replace("\n", " ")
            remediation = f.remediation.replace("|", "\\|")
            lines.append(f"| {f.control} — {f.title} | **{f.status}** | {f.severity} | {evidence} | {remediation} |")
        args.markdown.write_text("\n".join(lines) + "\n", encoding="utf-8")
    counts = {s: sum(f.status == s for f in findings) for s in ("PASS", "PARTIAL", "FAIL")}
    print(json.dumps({"counts": counts, "total": len(findings)}, ensure_ascii=False))
    return 1 if counts["FAIL"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
