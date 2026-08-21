# The-VVF-Search: Findings 11–14 Verification and Remediation Report

**Date:** 21 August 2026  
**Repository:** [praisesai-hub/The-VVF-Search](https://github.com/praisesai-hub/The-VVF-Search)

## Executive summary

Findings 11–14 were rechecked against the current repository and the available automated security checks. Finding 11 was already well addressed and received additional regression-oriented path review. Finding 12 was valid as a performance and resilience concern, so recursive scanning now retains cancellation, batching, hidden-directory exclusions, symlink skipping, and a hard discovered-file ceiling of 250,000. Finding 13 remains a high Google Play policy and UX validation risk, but the code has a centralized permission lifecycle and SAF fallback and does not request all-files access automatically at app launch. Finding 14 has strong source-level backup exclusions, but generated APK/AAB merged-manifest and device restore evidence still require an Android release environment.

## Finding status

| Finding | Status | Action |
|---|---|---|
| 11. Rename security | Good fix, strengthened | Existing filename and canonical-child checks retained; regression matrix reviewed and operation-level path controls documented. |
| 12. Deep storage scan | Confirmed performance risk, fixed at source level | Added symlink/reparse avoidance and a 250,000-file traversal ceiling while retaining cancellation and batch backpressure. |
| 13. `MANAGE_EXTERNAL_STORAGE` | High policy risk, partly addressed | Centralized status check, Settings intent, and SAF fallback verified. Play Console justification and device UX evidence remain required. |
| 14. Backup security | Strong source controls, evidence gap remains | Manifest and both modern/legacy backup policies exclude the entire app data root. Merged artifact and restore validation remain required. |

## Detailed verification

### 11. Rename security

`PhysicalStorageManager.validateSafeFileName()` rejects traversal names, absolute names, separators, dot names, NUL, control characters, trimmed/ambiguous names, and names longer than 255 characters. `resolveChildWithinParent()` canonicalizes the parent and candidate and verifies that the final destination remains below the intended parent boundary.

The reviewed regression matrix includes `../x`, `../../x`, absolute paths, Windows-style separators, `C:\...`, `.`, `..`, slash and backslash separators, NUL, newline/control characters, long names, and valid names. Existing tests cover path traversal and control-character rejection. Additional device-level validation should cover symlink races, case-insensitive collisions, Unicode normalization, and overwrite behavior on representative filesystems.

### 12. Deep storage scan

The previous arbitrary depth restriction is removed, but unlimited recursion is not left unconstrained. The recursive scanner now:

| Safeguard | Current implementation |
|---|---|
| Cancellation | Calls `ensureActive()` before traversal and per entry. |
| Backpressure | Emits batches of 100 records rather than retaining all emitted records in the flow. |
| Hidden/system exclusion | Skips hidden entries and the root-level Android directory according to existing policy. |
| Symlink/reparse avoidance | Resolves each directory canonically and skips a directory when canonical and lexical paths differ. |
| File-count ceiling | Stops traversal after 250,000 discovered file paths. |
| Work scheduling | Production indexing runs through WorkManager with storage and battery constraints. |

The ceiling is a resilience guard rather than a completeness guarantee for pathological trees. Production tuning should use telemetry-free benchmarks on representative large storage volumes and verify user-visible progress and resumability.

### 13. `MANAGE_EXTERNAL_STORAGE`

The permission is declared only for the full-device file-manager mode. `StoragePermissionManager` checks `Environment.isExternalStorageManager()` on Android 11 and later, returns an app-specific Settings intent only when access is missing, and exposes `shouldUseSafFallback()` when restricted access is unavailable. `MainActivity` provides an explicit entry point instead of requesting access automatically during application startup.

The fallback is a real SAF path in the file-picker and content-URI code. Denial does not prevent the app from operating on user-selected SAF documents, although operations requiring broad filesystem enumeration remain unavailable. Before Play publication, the product owner must confirm that broad file management is the core qualifying functionality, prepare the Play Console declaration, and attach user-flow evidence for grant, denial, revocation, and return-from-Settings behavior.

### 14. Backup security

The current source has three aligned controls:

```xml
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/backup_rules"
```

Both `data_extraction_rules.xml` and `backup_rules.xml` exclude the root app-data domain. The policy comments explicitly state that vault and authentication state are not supported for restore outside their original key lifecycle. Exported component review found the launcher activity exported as required and the startup provider not exported.

Source inspection cannot prove OEM-specific backup behavior. Release validation must inspect the merged manifest from the generated APK/AAB, verify modern cloud backup and device-to-device transfer behavior, verify legacy Auto Backup behavior, and confirm that vault keys, PIN material, secure-store data, Room metadata, encrypted files, and cloud credentials do not appear in backup artifacts.

## Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/example/storage/StorageScanner.kt` | Added canonical symlink avoidance and a bounded discovered-file ceiling. |
| `docs/remediation-report-11-14.md` | Added this updated report. |

## Verification

Static assertions confirmed the scanner safeguards, canonical theme and manifest state, backup exclusions, and existing path-validation implementation. Repository security tests completed successfully:

```text
Ran 20 tests in 0.191s
OK
```

Android Gradle compilation, merged-manifest inspection, instrumentation tests, OEM backup behavior, SAF permission revocation, symlink race testing, and physical-device release validation require an Android SDK and representative devices. Those checks are not claimed as passing in this sandbox.

## Release conclusion

Finding 11 is a good source-level fix with further filesystem-race testing required. Finding 12 is now bounded against pathological traversal while preserving deep scanning and cancellation. Finding 13 remains a policy approval gate rather than a code-only issue. Finding 14 has strong source-level exclusion policy but still requires generated-artifact and device-level evidence before a production Release Candidate PASS.
