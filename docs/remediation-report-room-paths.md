# Room Data-at-Rest and Filesystem Path Audit Report

**Date:** 21 August 2026  
**Repository:** [praisesai-hub/The-VVF-Search](https://github.com/praisesai-hub/The-VVF-Search)

## Executive summary

The reviewed Room fallback claim is **not reproduced** in the current production source. `VVFApplication` initializes `AppDatabase.getDatabase(this)`, `AppDatabase` uses `Room.databaseBuilder(...).build()`, and no production `fallbackToDestructiveMigration()` or in-memory fallback was found. In-memory Room builders exist only in test sources.

A separate and important boundary remains: the current Room database is not SQLCipher-backed. No SQLCipher dependency, `SupportFactory`, encrypted Room builder, or database passphrase lifecycle was found. Room therefore should not be described as an encrypted database. Sensitive data classification and an explicit encrypted-database design remain release work if database-at-rest encryption is required.

The path-traversal audit confirmed that filename validation and canonical child validation already protect rename destinations. A shared canonical containment helper was added and applied to app-managed trash restore inputs. User-selected source paths remain intentionally supported by the file-manager use case and must be handled through Android storage permissions or SAF authority, not incorrectly restricted to app-private roots. This distinction is documented below.

## Room data classification

| Data category | Current location | Current protection evidence |
|---|---|---|
| File names, paths, size, timestamps, categories, tags, OCR text, embeddings, and duplicate fingerprints | Room `files` table | Standard Room database; no SQLCipher evidence found. |
| Vault metadata such as original name, encrypted file path, IV, category, and size | Room `vault_items` table | Standard Room database; vault file payload uses separate AES-GCM operations. |
| Encrypted vault file payload | App-managed vault files | AES-GCM session and Keystore-backed key lifecycle are present. |
| PIN material and authentication state | `SecureKeyValueStore` and compatibility migration boundary | Encrypted secure-store implementation is present; ordinary SharedPreferences is not the production authentication fallback. |
| Persistent cryptographic keys | Android Keystore | Production key creation fails closed when Keystore is unavailable. Hardware capability is inspected at runtime where supported. |
| Cloud/auth state | Room and secure store depending on state | Must be included in backup, restore, and privacy validation. |

## Finding: encrypted Room DB fallback claim

The specific claim that encrypted Room initialization falls back to a blank or in-memory database is not supported by the current checkout. No production in-memory builder or fallback database path exists. `Room.inMemoryDatabaseBuilder` references found by search are test fixtures only.

The actual finding is a **database encryption verification gap**. The Room builder is ordinary Room, and the project currently has no SQLCipher integration. The release documentation must therefore avoid claiming that all Room metadata is encrypted at rest. If SQLCipher is introduced later, it requires a versioned migration plan, passphrase lifecycle review, backup policy, recovery behavior, and instrumented tests.

## Filesystem path audit

### Existing controls

`PhysicalStorageManager.validateSafeFileName()` rejects blank, dot, dot-dot, separator, NUL, control-character, whitespace-trimmed, and overlong names. `resolveChildWithinParent()` canonicalizes the parent and candidate and verifies that the candidate remains a descendant of the parent. This protects rename destinations from path traversal and directory changes.

Content URIs use `DocumentFile` and `ContentResolver` APIs rather than being converted to uncontrolled filesystem paths. Vault, recycle-bin, and restored directories are app-managed locations.

### New control

A shared `resolveAllowedPhysicalPath()` helper now canonicalizes an app-managed physical path and verifies that it remains under one of the approved roots: app files, cache, external app files/cache, or the primary external-storage root. The helper is applied to the physical trash input used by `restoreFromTrash()`, preventing a caller from supplying an arbitrary outside-root trash file.

User-selected source files are not all under app-managed roots. The normal file-manager workflow supports user storage locations, so delete and move operations must not be incorrectly constrained to app-private roots. Their security boundary is permission and SAF authority validation, while app-generated destinations use canonical containment checks.

### Remaining operation matrix

| Operation | Current result | Required follow-up |
|---|---|---|
| Rename | Canonical parent/child validation present | Add symlink/race tests on supported filesystems. |
| Copy/move to recycle bin | Generated recycle destination; source may be user-selected | Add operation-level permission and crash recovery tests. |
| Delete | Physical path and content URI paths supported | Add symlink and revoked-URI tests. |
| Recursive scan | Scanner policy and cancellation exist | Validate inaccessible directories and symlink behavior on devices. |
| Restore from trash | App-managed trash input now canonical-root checked; user destination may be selected | Add overwrite, symlink, and crash-window tests. |
| Vault import/export | Uses app-managed vault destination and user-selected source/destination flows | Add destination-root and interrupted-operation tests. |
| Cloud download | Provider adapter uses temporary file and atomic replacement | Bind downloaded record identity and validate destination authority. |

## Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/example/storage/PhysicalStorageManager.kt` | Added canonical approved-root resolution and applied it to physical trash restore inputs. |
| `docs/remediation-report-room-paths.md` | Added this report. |

## Verification

Static checks confirmed that no production Room in-memory fallback, destructive migration fallback, SQLCipher `SupportFactory`, or database passphrase construction exists. They also confirmed the new canonical resolver, its use in physical trash restoration, existing filename validation, explicit migrations, and content-URI handling.

Android Gradle tests and device-level symlink, SAF, permission, and crash-recovery tests require an Android SDK and representative devices. Those could not be executed in the current sandbox and are not claimed as passing.

## Release conclusion

The fictional encrypted-DB-to-in-memory fallback claim should be closed as **not reproduced**. The absence of encrypted Room integration should remain a **high-priority verification and architecture decision**, not be hidden under that claim. Path validation is improved, but comprehensive operation-level and filesystem-race evidence is still required before a production Release Candidate PASS.
