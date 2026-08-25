# Detekt Legacy Remediation Plan

**Repository:** `praisesai-hub/The-VVF-Search`
**Baseline commit:** `d2b6818` (`Freeze current release evidence`)
**Baseline command:** `./gradlew :app:detekt`
**Current baseline:** **225 weighted issues**
**User-reported previous count:** **216 weighted issues**

## 1. Baseline reconciliation

The user-reported count of 216 was captured on an earlier local working state that contained unpushed repairs. The current clean clone from `origin/main` is at commit `d2b6818` and reports 225 weighted Detekt issues. The delta is therefore not silently ignored: all remediation will target the current clean clone, and every later count will be compared with this 225-issue baseline. The target is zero active Detekt findings, not merely a lower count. The complete rule count is 130 `MaxLineLength` + 46 `MagicNumber` + 49 behavior/complexity findings = **225**.

## 2. Rule inventory

| Rule | Count | Primary treatment | Safety constraint |
|---|---:|---|---|
| `MaxLineLength` | 130 | Wrap declarations, calls, conditions, and test fixtures without changing tokens or evaluation order. | Never use formatting to hide a semantic change; run compilation/tests after each source family. |
| `MagicNumber` | 46 | Replace non-domain literals with named constants only where semantics are clear; retain genuine protocol/version/threshold values with documented constants. | Never change retry, crypto, file-size, ML, or lockout behavior while naming values. |
| `CyclomaticComplexMethod` | 9 | Extract decision branches into small pure/private helpers and explicit result types. | Preserve branch ordering, fail-closed behavior, cancellation, and error mapping. |
| `TooGenericExceptionCaught` | 7 | Catch the narrowest expected exceptions; preserve coroutine cancellation and provider-specific failures. | Never convert `CancellationException` into success or a generic empty result. |
| `ReturnCount` | 7 | Use guard helpers, sealed outcomes, and single-result flow where readability improves. | Do not remove fail-closed exits merely to satisfy the rule. |
| `LongMethod` | 7 | Extract cohesive operations by responsibility. | Keep transaction boundaries, cleanup, and atomicity unchanged. |
| `NestedBlockDepth` | 5 | Flatten with early-return helpers and `runCatching` only where exception semantics remain explicit. | No broad `runCatching` around suspending or destructive operations. |
| `LongParameterList` | 4 | Introduce domain parameter objects only where call sites represent one coherent concept. | Avoid DTO churn and preserve public/facade API compatibility. |
| `UseCheckOrError` | 3 | Replace direct `IllegalStateException` construction with `check`/`error` where the condition is a programmer invariant. | Keep domain/security exceptions when callers depend on their type. |
| `TooManyFunctions` | 2 | Extract cohesive collaborators only after tests cover the public behavior. | Do not split the repository facade or security boundary in a way that weakens ownership. |
| `SwallowedException` | 2 | Log/classify expected failures or rethrow them; preserve intentionally ignored cancellation only with explicit rationale. | No silent loss of security, deletion, sync, or migration errors. |
| `ComplexCondition` | 2 | Name predicates and use domain state predicates. | Preserve short-circuit order and null/permission semantics. |
| `UnusedParameter` | 1 | Remove only private unused parameters; preserve interface/API parameters required for compatibility. | Check overrides and reflection before removal. |
| `Incubating` | 1 | Replace or document the incubating API at the build boundary. | Do not downgrade toolchain or disable the check globally. |

## 3. File hotspot order

| Priority | File | Findings | Main workstream |
|---:|---|---:|---|
| 1 | `StorageScanner.kt` | 28 | Scan outcome, cancellation, provider isolation, complexity, constants, and exception policy. |
| 2 | `VideoDuplicateEvidence.kt` | 27 | Evidence state machine, thresholds, branches, and constants; add characterization tests before extraction. |
| 3 | `SmartManagerRepository.kt` | 25 | Facade orchestration, long methods, return paths, and domain predicates; preserve single-entry architecture. |
| 4 | `VaultScreen.kt` | 13 | Compose UI branching and magic dimensions; no behavioral/security changes. |
| 5 | `MainViewModelCompat.kt` | 12 | Compatibility API delegation and constants; preserve deprecated surface. |
| 6 | `GoogleDriveProviderAdapter.kt` | 12 | Provider error mapping, retries, URI/file semantics, and exception narrowing. |
| 7 | `FileDao.kt` | 12 | SQL/data merge constants and complex update paths; preserve metadata, vault, and recycle-bin state. |
| 8 | `AppDatabaseMigrationTest.kt` | 8 | Test-only literals and setup extraction; maintain historical migration assertions. |
| 9 | Remaining 31 files | 88 | Apply the same rule-specific process in small groups, with focused tests for behavior-sensitive code. |

## 4. Execution batches

### Batch A — Formatting and inventory-safe constants

This batch first covers the 130 `MaxLineLength` findings because they are mechanically reviewable, then `MagicNumber`, `UnusedParameter`, `UseCheckOrError`, and obvious test fixture literals. Formatting is performed file-family by file-family and compiled before moving on. Crypto iterations, retry delays, ML thresholds, file-size caps, database versions, and lockout values require named domain constants plus tests, not blind replacement. It begins with files where constants are unambiguous: UI dimensions, timeout labels, fixture IDs, and repeated test values.

**Exit criteria:** Detekt count decreases without changing production behavior; `git diff --check` passes; affected unit tests pass.

### Batch B — Formatting and low-risk structural extraction

This batch handles `LongMethod`, `NestedBlockDepth`, and simple `ReturnCount` findings in UI, test, and adapter code. Extraction boundaries will follow existing responsibilities: validation, persistence, provider transfer, cleanup, and presentation state. No broad suppression will be introduced.

**Exit criteria:** all extracted methods compile; direct tests for affected classes pass; no new Detekt findings are introduced.

## 5. Observed execution record

The clean-clone Detekt baseline was 225 weighted issues. The formatting batch reduced this to 168, the first semantic batch to 145, the low-risk cleanup to 88, the physical-storage boundary cleanup to 63, the structural boundary cleanup to 23, and the final targeted boundary pass to 0. The authoritative final run is recorded in `docs/detekt-remediation/detekt-authoritative-final.log`.

The focused video-evidence, metadata-preservation, Google Drive resumable-session, and Room migration tests passed. The full debug unit-test suite completed successfully with 269 tests. Final debug APK assembly passed and produced `app/build/outputs/apk/debug/app-debug.apk`. Final lint passed with 0 errors and 33 warnings. SARIF output is disabled only because AGP 9.3.1 crashes in its SARIF quick-fix writer on a `%1$s` message; text, XML, and HTML reports remain enabled and `abortOnError` remains true.

### Batch C — Storage and file-integrity paths

`StorageScanner`, `PhysicalStorageManager`, `FileOperationStore`, `DuplicateCleanupWorker`, and related workers will be handled as a single safety stream. The work will preserve cancellation propagation, complete-versus-partial scan semantics, fail-closed deletion, journal recovery, and stale-record reconciliation. Complexity will be reduced using explicit sealed outcomes and small provider helpers.

**Exit criteria:** scanner cancellation/partial-provider tests, deletion uncertainty tests, journal recovery tests, and worker tests pass; no destructive operation is made more permissive.

### Batch D — Vault and security paths

`VaultManagerEngine`, `KeystoreVaultManager`, telemetry, and authentication-related tests will be reviewed separately. Only named constants and safe helper extraction will be applied. Exception narrowing must distinguish authentication failure, lockout, Keystore invalidation, storage commit failure, and coroutine cancellation.

**Exit criteria:** vault unit and instrumented tests pass where an emulator is available; security policy checks pass; no plaintext secret or fail-open authentication path is introduced.

### Batch E — Repository, database, and cloud orchestration

`SmartManagerRepository`, `FileDao`, `AppDatabase`, `GoogleDriveProviderAdapter`, `CloudSyncEngine`, `CloudSyncWorker`, and `WorkCoordinator` will be refactored only after their public behavior is characterized. Parameter objects will be introduced only for cohesive concepts such as transfer state or scan request. Room migrations, DAO merge semantics, cloud retry/idempotency, and WorkManager result mapping will remain explicit.

**Exit criteria:** migration tests, DAO metadata tests, cloud sync tests, worker tests, and policy gates pass; schema files remain unchanged except for legitimately generated history.

### Batch F — Compose/UI and remaining low-volume files

UI files will receive pure presentation refactors: named dimensions, predicates, and extracted composables. Compatibility/deprecated APIs will not be deleted merely to satisfy Detekt. Accessibility, loading/error/empty states, and user-visible behavior will be preserved.

**Exit criteria:** Kotlin compilation, Compose unit tests, screenshot/Roborazzi checks where configured, and lint pass.

### Batch G — Zero-finding closure

After each batch, Detekt will run on the full project. Remaining findings will be resolved by root cause. A suppression is acceptable only for an intentional, documented exception with a narrow scope and a code comment explaining why refactoring would harm correctness or public compatibility. Global rule disabling and baseline regeneration to hide findings are prohibited.

**Exit criteria:** `:app:detekt` reports zero weighted issues; the baseline XML is not regenerated to conceal debt; `git diff --check` passes.

## 5. Verification loop after every batch

Each batch will use the following order:

1. Run `git diff --check` and inspect the staged semantic diff.
2. Run the smallest affected unit-test class set.
3. Run `./gradlew :app:detekt` and compare the count and rule/file distribution with the previous snapshot.
4. Run architecture, secret, security-compliance, coverage-floor, dependency, runtime-security, and security-health policy gates.
5. Continue only if the count decreases or an explicitly documented rule migration replaces an equivalent finding without increasing weighted debt.
6. At the end of the batch series, run full debug APK packaging, lint, JVM tests, and available instrumented tests.

## 6. Full final gates

The final gate set is:

```text
./gradlew :app:detekt
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # only when an emulator/device is available
python3 scripts/check_no_secrets.py
python3 scripts/check_architecture_boundaries.py
python3 scripts/security_compliance_check.py . --json ... --markdown ...
python3 -m unittest scripts/test_check_coverage_floor.py
PYTHONPATH=scripts python3 -m unittest scripts/test_verify_release_runtime_security.py
PYTHONPATH=scripts python3 -m unittest scripts/test_release_dependency_gate.py
PYTHONPATH=scripts python3 -m unittest scripts/test_weekly_security_health.py
git diff --check
```

A gate is never reported as passed when it was stopped, OOM-killed, blocked by missing Firebase configuration, blocked by an unavailable emulator, or terminated at a Gradle daemon failure. Those conditions are recorded separately from code failures.

## 7. Completion definition

The remediation is complete only when the current repository has zero active Detekt findings, all behavior-sensitive tests pass, debug APK packaging succeeds and produces an APK, lint succeeds without fatal issues, policy gates pass, and the working tree contains a committed, reviewable diff. If an external environment remains necessary for a gate, the code remediation may be complete but release readiness remains **blocked**, with the exact blocker recorded.
