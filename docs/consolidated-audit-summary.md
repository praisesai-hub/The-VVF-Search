# The-VVF-Search — Consolidated Master Audit & Remediation Ledger

**Repository:** `praisesai-hub/The-VVF-Search`  
**Audit scope:** Previous audit rounds 1–2 + current audit round 3  
**Current remediation branch:** `audit/remediation-round-3`  
**Current PR:** #50  
**Current verdict:** **NO-GO — production qualification incomplete**

> This file is the single cumulative audit record. Findings from earlier rounds are retained here; later rounds update their status rather than creating duplicate findings.

## 1. Executive position

The repository has received substantial source-level remediation. The current codebase contains meaningful controls for file-operation safety, bounded scanning, backup exclusion, Keystore-backed vault encryption, dependency policy, static analysis, coverage enforcement, release evidence collection and CI automation.

Production readiness is nevertheless **not established**. Source inspection and configured workflows are not equivalent to a successful clean Android release qualification.

Round 3 has started implementation work. In PR #50 the Room database is being moved from plaintext SQLite to SQLCipher-backed Room storage, with a Keystore-wrapped random database passphrase and a migration path for existing plaintext databases. The change is not yet considered verified until hosted Android build/instrumentation evidence passes.

## 2. Consolidated finding ledger

| ID | Finding | Evidence / condition | Current status | Priority | Remediation |
|---|---|---|---|---|---|
| F-02 | Encrypted DB to in-memory fallback | Earlier audits found no acceptable production fallback evidence; fail-closed behavior is required. | **REMEDIATED IN CODE; VERIFY** | HIGH | Room key manager now fails closed if Keystore/key metadata is unavailable. Hosted tests must prove no fallback path exists. |
| F-03 | Room database encryption | Earlier Room builder used normal `Room.databaseBuilder` without SQLCipher. | **FIX IMPLEMENTED; VERIFY** | CRITICAL | SQLCipher 4.18.0 + `SupportOpenHelperFactory`; random DB key wrapped by Android Keystore; plaintext-to-encrypted migration added. |
| F-10 | Path traversal | Prior fix reported; complete filesystem race matrix was not demonstrated. | OPEN VERIFICATION | HIGH | Add/retain canonical-child validation and race/device tests; verify all file-operation entry points. |
| F-11 | Rename traversal | Regression tests reported; filesystem race and full matrix remained open. | OPEN VERIFICATION | HIGH | Test rename/move/delete/recycle-bin operations against symlink/race/path escape cases. |
| F-12 | Unlimited/deep scan exhaustion | Scanner now has cancellation, batching, symlink avoidance and output bounds. | OPEN STRESS VERIFICATION | MEDIUM/HIGH | Run large-tree, low-storage, low-memory and cancellation stress tests. |
| F-13 | `MANAGE_EXTERNAL_STORAGE` | Manifest requests broad storage access; Play policy eligibility is separate from technical capability. | OPEN | HIGH | Prefer MediaStore/SAF where possible; document and verify policy eligibility before Play release. |
| F-14 | Backup protection | `allowBackup=false` and backup/data-extraction exclusions exist. | OPEN ARTIFACT VERIFICATION | HIGH | Verify merged manifest and Android 12+ backup/device-transfer behavior on device. |
| F-15 | R8/resource shrinking | Release source enables minification and resource shrinking. | OPEN ARTIFACT VERIFICATION | HIGH | Clean release build; inspect mapping and missing-class output; verify reflection/serialization/TFLite/Room keep rules. |
| F-16 | Gradle wrapper | Wrapper files and validation exist. | OPEN REPRODUCIBILITY VERIFICATION | MEDIUM | Verify clean runner provenance and wrapper validation without regenerating checked-in wrapper artifacts. |
| F-17 | CI gates | Workflow gates exist, but workflow configuration alone does not prove merge enforcement. | OPEN | CRITICAL | Verify GitHub ruleset/branch-protection required checks and bypass policy. |
| F-18 | Android verification | Prior review environment lacked Android SDK/device evidence. | OPEN | CRITICAL | Hosted Android runner plus representative device matrix; retain artifacts and logs. |
| F-19 | Test coverage | Coverage thresholds do not prove complete security-model coverage. | OPEN | HIGH | Expand security regression matrix; require migration, crash-window, reconciliation and platform tests. |
| F-20 | CodeQL production/source mismatch | CodeQL workflow creates a compatibility workspace using Kotlin 2.3.21 while production uses Kotlin 2.4.20-RC. | OPEN | HIGH | Keep production dependency patched; remove compatibility rewrite once CodeQL supports the production compiler, or provide documented compensating validation. |
| F-21 | Duplicate dependency-submission workflows | Android CI contained a dependency-submission job and a separate dependency-submission workflow also existed. | **REMEDIATED** | MEDIUM | Removed the duplicate `.github/workflows/dependency-submission.yml`; Android CI remains the authoritative submission path. |
| F-22 | Secret materialization in CI | CI writes `google-services.json` and `.env` from secrets. | OPEN | HIGH | Use environment-safe materialization, ensure no shell interpolation/log exposure, and prove generated files cannot enter artifacts. |
| F-23 | Optional Firebase configuration | CI intentionally allows missing Firebase configuration. | OPEN VERIFICATION | MEDIUM/HIGH | Prove cloud/auth boundary fails closed when configuration is absent and remains disabled unless explicitly provisioned. |
| F-24 | Storage permission matrix | Legacy and modern media permissions coexist with broad storage permission. | OPEN | HIGH | Test Android 11–16 scoped-storage, MediaStore and SAF behavior and permission revocation/recovery. |
| F-25 | Android support contract | Project declares `minSdk 24`; prior product requirement referenced Android 11+. | OPEN PRODUCT DECISION | MEDIUM/HIGH | Either support/test API 24–30 or raise the minimum to the actual supported product floor. |
| F-26 | Semantic AI fallback semantics | README describes a fallback when TFLite/model is unavailable. | OPEN | MEDIUM | UI must distinguish semantic AI from conventional similarity fallback; tests must verify correct mode reporting. |
| F-27 | Signed release is manual | Signed release is `workflow_dispatch` and restricted to `main`. | EXPECTED / OPEN GATE INTEGRATION | HIGH | Keep signing isolated; require all pre-release checks before manual signing is allowed. |
| F-28 | API 35-only release emulator | Release workflow tests API 35 Pixel 2. | OPEN | HIGH | Add representative Android 11–16 profiles and relevant storage/Keystore/WorkManager scenarios. |
| F-29 | Release evidence collection | Evidence collection runs after validation steps. | ACCEPTED DESIGN / VERIFY | MEDIUM | Preserve `if: always()` evidence capture but never treat evidence collection as a success override. |
| F-30 | Dependency/security policy complexity | Multiple independent policy scripts validate dependency graphs and runtime policy. | OPEN MAINTENANCE RISK | MEDIUM | Add policy-contract tests and keep a single authoritative policy source where practical. |
| F-31 | Detekt baseline debt | Large baseline exists. | OPEN | MEDIUM | Enforce no-growth and progressively shrink the baseline. |
| F-32 | Cloud/local privacy boundary | README correctly avoids blanket local-only claims; cloud integrations exist. | OPEN | HIGH | Verify provider implementations, auth boundaries, explicit user action, upload paths and failure behavior. |
| F-33 | Weekly Security Health write permission | Scheduled workflow has `issues: write` and consumes security-health data. | OPEN | MEDIUM | Review script for untrusted-content injection and restrict issue mutation to intended records. |
| F-34 | Kotlin/CodeQL compatibility blocker | Production Kotlin 2.4.20-RC is newer than the currently supported CodeQL range used by the hosted extractor. | OPEN / UPSTREAM DEPENDENCY | HIGH | Do not downgrade below the security-fixed Kotlin line. Remove compatibility workaround when supported; keep security issue visible meanwhile. |
| F-35 | Exact required-check list | Exact repository ruleset state is separate from YAML. | OPEN | CRITICAL | Obtain authoritative ruleset/branch-protection evidence before adding/removing required checks. |
| F-36 | Documentation/security-language consistency | Earlier documentation referred to Room as secure/encrypted despite plaintext implementation. | **PARTIALLY REMEDIATED** | HIGH | Update all documentation to describe SQLCipher only after build/device evidence proves it. |
| F-37 | Interrupted Room swap can create empty DB | Existing migration returned when the primary DB was absent, ignoring `.plaintext.backup`/`.encrypted.tmp`. | **FIX IMPLEMENTED; VERIFY** | CRITICAL | Recover a valid encrypted staging file first; otherwise restore the plaintext backup and retry migration before Room opens. Add crash-window tests. |
| F-38 | Room DB key Base64 incompatible with minSdk 24 | `java.util.Base64` is API 26+, while the app declares minSdk 24 and has no desugaring. | **FIX IMPLEMENTED; VERIFY** | HIGH | Replaced with `android.util.Base64`; add API-24 runtime/build verification. |
| F-39 | Room rollback could delete only surviving plaintext backup | Previous `finally` deleted the backup even if rollback failed. | **FIX IMPLEMENTED; VERIFY** | HIGH | Preserve backup when restoration cannot be confirmed; surface a fatal/recovery state instead of deleting the only copy. |
| F-40 | CloudSync unsupported-provider status mismatch | Worker persisted `NOT_SUPPORTED`, while the established terminal failure contract/test expects `FAILED` plus a diagnostic code. | **FIX IMPLEMENTED; VERIFY** | MEDIUM | Persist `FAILED` with `PROVIDER_NOT_SUPPORTED`; retain terminal worker failure result. |
| F-41 | Declared Gradle version drift | Workflow declares `GRADLE_VERSION=9.3.1`, while wrapper/runner evidence reports Gradle 9.7.0. | OPEN | LOW/MEDIUM | Remove unused misleading declaration or make it authoritative and test it against the wrapper. |
| F-42 | Google OAuth generated-resource contract | `R.string.default_web_client_id` caused compile failure when the generated resource was absent even though Google Services processing itself succeeded. | **FIX IMPLEMENTED; VERIFY** | MEDIUM/HIGH | Resolve resource by name and fail closed at authentication time instead of failing compilation; verify configured and unconfigured Firebase builds. |

## 3. Round-3 changes already implemented

### 3.1 Duplicate dependency submission removed

The standalone `.github/workflows/dependency-submission.yml` duplicated the dependency-submission behavior already present in Android CI. It also used a different Gradle dependency-submission action version.

**Change:** standalone duplicate workflow removed.  
**Validation required:** confirm exactly one expected dependency-graph submission occurs on `main`.

### 3.2 Room database encryption implemented

The previous Room builder opened a normal SQLite database. Round 3 replaces that boundary with SQLCipher.

Implementation components:

- `net.zetetic:sqlcipher-android:4.18.0`
- `androidx.sqlite:sqlite:2.7.0`
- `SupportOpenHelperFactory`
- random 256-bit database passphrase
- Android Keystore AES-256-GCM wrapping key
- wrapped passphrase persisted only in private application storage
- no hard-coded database key
- no plaintext/in-memory database fallback
- fail-closed behavior when Keystore/key metadata cannot be recovered

The SQLCipher project documents Room integration through `SupportOpenHelperFactory`, and the modern SQLCipher Android package is the maintained replacement for the deprecated legacy package.

### 3.3 Existing plaintext database migration

The application cannot simply switch Room to SQLCipher and abandon an existing plaintext database. Round 3 therefore adds a pre-Room migration:

1. load SQLCipher native library;
2. obtain Keystore-wrapped database key;
3. recover any interrupted swap state before checking the primary path;
4. detect whether the existing database already opens with the key;
5. if not, verify that it is valid plaintext SQLite;
6. create a temporary encrypted SQLCipher database;
7. attach the plaintext database;
8. execute `sqlcipher_export` into the encrypted database;
9. preserve the Room schema version;
10. stage the plaintext database as a backup during replacement;
11. install the encrypted database;
12. remove the plaintext staged copy only after successful installation;
13. restore the original if replacement fails;
14. retain the backup if rollback itself cannot be confirmed.

This migration remains **unverified** until it is exercised on a real Android runner with representative existing database states and crash-window tests.

### 3.4 API-24 compatibility correction

The Room key manager previously used `java.util.Base64`. Android's platform Base64 implementation is used instead so the declared `minSdk 24` contract does not fail at runtime on Android 7.x.

### 3.5 CloudSync terminal-state correction

`CloudSyncResult.NotSupported` is now persisted as the normal terminal `FAILED` state with `PROVIDER_NOT_SUPPORTED` as the diagnostic error code. This aligns persisted state with the worker's existing terminal failure contract and test expectations.

### 3.6 Firebase OAuth compile-time dependency reduction

The authentication manager no longer directly references generated `R.string.default_web_client_id`. It resolves the resource by name and fails closed if it is absent or blank. This keeps secret-free validation builds compilable while preserving explicit runtime failure when Google OAuth is not configured.

## 4. CodeQL finding — current external evidence

The repository uses Kotlin `2.4.20-RC` because that line contains the security fix tracked by the repository's open security issue. The CodeQL workflow therefore creates a workspace-only compatibility copy using an older supported Kotlin version.

Current upstream CodeQL support has progressed to Kotlin `2.4.10`, but that does not establish support for the repository's `2.4.20-RC` compiler. Downgrading the application below the security-fixed Kotlin line is therefore not an acceptable remediation merely to make CodeQL green.

Required end state:

- production dependency remains on the security-fixed Kotlin line;
- CodeQL directly analyzes the production compiler graph once upstream support exists;
- compatibility rewriting is removed;
- a fresh CodeQL run passes against the actual production build graph.

Until then this is a documented verification limitation, not a reason to disable CodeQL.

## 5. CI and required status checks

The Android CI workflow contains substantial gates:

- wrapper validation
- tracked-secret policy
- architecture boundaries
- dependency compatibility
- runtime dependency security
- JVM tests
- JVM coverage
- lint
- Detekt
- debug build
- release AAB validation
- Android instrumentation
- Android coverage.

CodeQL, dependency submission, weekly security health and signed release are separate workflows.

However:

> **A workflow check is not automatically a required merge check.**

The exact repository Ruleset/Branch Protection configuration must be inspected before deciding that any status check is inappropriate or should be removed.

No required check should be invented. No existing required check should be removed without evidence that it is obsolete, duplicated or harmful to the release contract.

## 6. Release gates

The following remain hard release gates:

### Gate A — Room encryption

**Implementation:** present in PR #50.  
**Verification:** pending hosted build, migration test and device evidence.

### Gate B — Merge enforcement

**Implementation:** workflow gates present.  
**Verification:** repository-level ruleset evidence pending.

### Gate C — Release/R8

**Implementation:** source configuration present.  
**Verification:** clean release artifact, R8 mapping and warning inspection pending.

### Gate D — Android/device validation

**Implementation:** API 35 emulator workflow exists.  
**Verification:** complete platform/security matrix pending.

### Gate E — CodeQL fidelity

**Implementation:** compatibility workaround exists.  
**Verification:** direct production-compiler analysis pending upstream support.

## 7. Security test matrix still required

| Area | Required tests |
|---|---|
| Room | fresh install, encrypted reopen, wrong/missing key, Keystore failure, plaintext-to-encrypted upgrade, interrupted migration, corrupted temp file, rollback failure with backup retention |
| File operations | traversal, symlink, canonical path, rename race, delete race, recycle-bin consistency |
| Scanner | cancellation, huge tree, low memory, inaccessible directories, symlink loops, file-count limits |
| Vault | crash window, authentication failure, key invalidation, partial write, restore/export |
| Cloud | disabled-by-default, auth failure, resumable upload, redirect handling, remote-ID reconciliation, unsupported-provider terminal state |
| AI | model present, model missing, TFLite unavailable, fallback mode, malformed embedding, resource exhaustion |
| Backup | merged manifest, Android backup, device transfer, vault exclusion, database exclusion |
| Release | R8, mapping, missing classes, AAB signature, checksum, provenance, SBOM |
| Platform | Android 11, 12, 13, 14, 15, 16; storage permissions; Keystore; WorkManager; large files |
| Compatibility | Android API 24–25 database-key initialization and reopen if minSdk 24 remains supported |

## 8. UI/UX target

The final UI should use the VVF identity through the logo/launch-icon visual language without turning security states into marketing claims.

Recommended primary navigation:

`Home · Files · Duplicates · Search · Vault · Activity · Settings`

Security-sensitive state should always be explicit:

- `AI — On-device`
- `AI — Fallback matching`
- `Vault — Protected`
- `Cloud — Disabled`
- `Cloud — Connected`
- `Storage — Scoped access`
- `Scan — Running / Paused / Cancelled / Incomplete`

Duplicate results should distinguish:

- Exact duplicate
- High semantic similarity
- Possible match

The UI must never silently represent conventional similarity matching as active semantic AI.

## 9. Claims prohibited until evidence exists

Do not claim:

- “All audit phases complete”
- “Production-ready”
- “Encrypted Room database” as a verified release property before hosted/device evidence
- “CI enforces security” without repository ruleset evidence
- “100% local-only” while cloud integrations remain available
- full Android-version support without the corresponding device matrix
- semantic AI operation when the application is actually in fallback mode
- Play Store compliance solely because the manifest contains the required permissions

## 10. Current release verdict

**NO-GO.**

The repository is in a materially stronger state than the initial audit, and Round 3 remediation is actively addressing the most important unresolved storage-security issue. The remaining work is primarily verification, release hardening, repository governance and completion of the security test matrix.

The correct current statement is:

> **Source-level remediation is substantial and Round-3 remediation is in progress. Production readiness is not established until Room encryption/migration, repository merge enforcement, clean Android release/R8 evidence, complete device validation and CodeQL production-compiler analysis are verified.**

## 11. Evidence policy

Every future audit finding or remediation must be added to this file rather than creating a competing summary. Each entry must contain:

**Finding → Evidence → Root cause → Remediation → Regression test → CI/device evidence → Remaining risk → Final status.**

No completion claim is accepted from source inspection alone when the finding requires runtime, artifact, device or repository-governance evidence.

## 12. Round-3 live validation record

### 12.1 Previous failed run

The earlier failed Android CI run was blocked first by the generated Firebase `default_web_client_id` resource being referenced directly by production code. `google-services.json` itself was present and `processDebugGoogleServices` succeeded. The compile-time resource dependency was therefore the application/code contract failure, not a missing secret.

### 12.2 Current remediation commits

- `d474690fb64cdfe547742a49130ef156bc9bcdc6` — API-24-compatible Room key Base64.
- `5970b42fe904130d8696dd0d415691b2a01bc89a` — interrupted Room encryption swap recovery and rollback preservation.
- `8edff7be9bbfde1d7ae25cbf8dea1767e6b3abd2` — unsupported CloudSync provider terminal-state correction.

A fresh Android CI run **#491** and CodeQL run **#399** were triggered from the latest remediation head. Their results are not yet evidence of success; they must be evaluated to completion.

## 13. External reference policy used for remediation

For current implementation decisions, use authoritative upstream documentation rather than stale snippets. Google documents that `default_web_client_id` is generated from an OAuth client with `client_type == 3`, and GitHub documents that Actions secrets are scope-dependent and unavailable to fork-triggered `pull_request` workflows. These references support the Firebase/secret-boundary diagnosis but do not substitute for repository runtime evidence.

## 14. Next evidence-gated actions

1. Complete CI #491 and CodeQL #399.
2. If Unit tests fail, fix the production/test contract rather than weakening assertions.
3. If instrumentation fails, inspect the actual test failure before changing artifact-upload behavior.
4. Run Room migration/recovery tests covering all crash windows, including retained plaintext backup after rollback failure.
5. Verify API-24 behavior if minSdk 24 remains declared; otherwise make an explicit product decision to raise minSdk.
6. Audit Dependabot configuration and update state, including GitHub Actions dependencies and blocked/stale security updates.
7. Audit secret names, scopes, permissions and workflow exposure without retrieving or exposing secret values.
8. Verify repository Ruleset/Branch Protection required checks and remove only checks proven obsolete/duplicate/harmful; do not add arbitrary checks.
9. Run clean release AAB/R8 validation and inspect mapping/missing-class output.
10. Update this file with every resulting finding and evidence. Do not create a second consolidated audit summary.
