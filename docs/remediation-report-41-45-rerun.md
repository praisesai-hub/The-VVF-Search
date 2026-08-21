# The-VVF-Search: Findings 41–45 Rerun Report

**Date:** 21 August 2026  
**Repository:** [praisesai-hub/The-VVF-Search](https://github.com/praisesai-hub/The-VVF-Search)

## Executive summary

Findings 41–45 were rerun against the current repository after the earlier remediation pass. Finding 41 is now **not reproduced**: CI has explicit JVM and instrumentation coverage policy gates, and failures are fail-closed. Finding 42 is **partly addressed**: the repository has broad security and instrumentation coverage, but the complete production matrix listed in the finding is not fully demonstrated by automated tests. Finding 43 is **partly addressed**: GitHub Actions runs instrumentation tests on an API 35 emulator, but this is not evidence across Android 12–16 and representative physical OEM devices. Finding 44 is a **release governance concern**, not an implementation defect: the current application ID is stable within the repository, but it should be frozen before Play production publication. Finding 45 was **already fixed**: the generic theme is now `Theme.VVFSmartManager`.

No package rename was performed because changing an Android application ID after release changes the Play identity and can strand existing installs. No speculative device screenshot tests were claimed as completed.

## Finding status

| Finding | Current status | Action |
|---|---|---|
| 41 | Not reproduced | Existing CI coverage gates were verified and retained. |
| 42 | Partly valid | Existing test inventory is substantial; missing cases remain a test-plan/release-gate gap. |
| 43 | Partly valid | API 35 emulator instrumentation is configured; API 12–16 and OEM physical-device evidence remain required. |
| 44 | Release governance concern | Current ID is `com.aistudio.vvfsmartmanager.app`; freeze and document it before production publication. |
| 45 | Already fixed | Theme is `Theme.VVFSmartManager` in resources and manifest. |

## Detailed verification

### 41. CI coverage enforcement

The original claim is no longer true for the current checkout. `scripts/coverage-policy.json` enforces a 70% aggregate JVM instruction floor, 90% for security/auth packages, 85% for the data layer, 95% for vault classes, and 90% for cloud-sync classes. `scripts/instrumented-coverage-policy.json` is applied after the Android instrumentation coverage report.

The workflow runs `check_coverage_floor.py` as a normal step. It does not use a fail-open Codecov upload as the enforcement mechanism. A policy violation causes the step and job to fail. The workflow also uploads artifacts with `if-no-files-found: error`, so missing coverage reports are not silently accepted.

### 42. Security-test matrix

The claim is partly valid. The repository contains JVM, Robolectric, and Android instrumentation tests for vault management, Keystore behavior, secure storage, physical storage, repositories, workers, OAuth, and UI. Existing test names include `VaultSecurityApiInstrumentedTest`, `KeystoreVaultManagerTest`, `SecureKeyValueStoreInstrumentedTest`, `PhysicalStorageManagerInstrumentedTest`, `CloudSyncWorkerInstrumentedTest`, and `DuplicateCleanupWorkerInstrumentedTest`.

The full matrix in the finding is not proven by inventory alone. Release qualification should explicitly add or confirm tests for random IVs, wrong key and IV, modified and truncated ciphertext, tag corruption, Keystore reset/invalidation, crash-window recovery, orphan encrypted files, restore overwrite behavior, lockout persistence, clock changes, PIN migration, and biometric failure. This was recorded as a release test gap rather than fabricating coverage claims.

### 43. Release instrumentation evidence

The claim is partly valid. The CI workflow uses `reactivecircus/android-emulator-runner` with API level 35, Google APIs, x86_64, and a Pixel 2 profile, then runs `connectedDebugAndroidTest` and Android coverage enforcement.

This provides automated emulator evidence, but not the requested Android 12, 13, 14, 15, and 16 matrix or representative Pixel, Samsung, and Xiaomi physical-device evidence. Scoped storage, Keystore, biometric, MediaStore, WorkManager, large-file, and low-memory behavior should be exercised in a release device matrix before publication.

### 44. Application identity

The current `applicationId` is `com.aistudio.vvfsmartmanager.app`, with namespace `com.example`. The application ID is consistent in the current Gradle project and is the identity that must be treated as the release candidate. The finding is therefore a release-governance concern rather than evidence of an unstable build configuration.

Changing it to `com.vvf.smartmanager` would be a breaking identity change if the current ID has already been distributed. The safe action is to choose and freeze the final reverse-domain ID before the first Play production release, then document that decision and avoid later renames.

### 45. Generic theme

This finding was already fixed in the previous pass. `app/src/main/res/values/themes.xml` defines `Theme.VVFSmartManager`, and both the application and launcher activity in `AndroidManifest.xml` reference `@style/Theme.VVFSmartManager`. No `Theme.MyApplication` reference remains in the main source tree or README.

## Current release gates

| Gate | Current evidence | Remaining work |
|---|---|---|
| JVM coverage | Explicit policy checker and thresholds | Confirm the report is generated in every protected branch build. |
| Instrumentation coverage | API 35 emulator and policy checker | Expand API/OEM/device matrix. |
| Security tests | Broad test inventory across crypto, vault, storage, cloud, and workers | Map each required threat case to a named test. |
| Package identity | Stable current Gradle application ID | Freeze and publish the final identity decision. |
| Theme naming | Canonical `Theme.VVFSmartManager` | None identified. |

## Files changed

| File | Change |
|---|---|
| `docs/remediation-report-41-45-rerun.md` | Added this rerun report. |

No source change was required during this rerun because the theme and coverage gate fixes were already present in the current branch. The application ID was intentionally not changed because identity renames are release-breaking and require an explicit product decision.

## Verification

Static checks confirmed the current application ID, canonical theme references, coverage policy thresholds, coverage enforcement step, and API 35 instrumentation workflow. `git diff --check` was also run before publication.

Full Android Gradle execution and physical-device validation require an Android SDK and representative devices. The sandbox does not provide those devices, so the report does not claim that release instrumentation evidence exists beyond the configured API 35 emulator job.

## Conclusion

The 41–45 rerun separates issues already fixed in the repository from remaining release qualification work. Coverage enforcement and theme naming are already corrected. Security test breadth, multi-version/device evidence, and final package-identity governance remain explicit production-release responsibilities.
