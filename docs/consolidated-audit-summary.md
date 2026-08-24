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
3. detect whether the existing database already opens with the key;
4. if not, verify that it is valid plaintext SQLite;
5. create a temporary encrypted SQLCipher database;
6. attach the plaintext database;
7. execute `sqlcipher_export` into the encrypted database;
8. preserve the Room schema version;
9. stage the plaintext database as a backup during replacement;
10. install the encrypted database;
11. remove the plaintext staged copy;
12. restore the original if replacement fails.

This migration remains **unverified** until it is exercised on a real Android runner with representative existing database states.

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
| Room | fresh install, encrypted reopen, wrong/missing key, Keystore failure, plaintext-to-encrypted upgrade, interrupted migration, corrupted temp file, rollback |
| File operations | traversal, symlink, canonical path, rename race, delete race, recycle-bin consistency |
| Scanner | cancellation, huge tree, low memory, inaccessible directories, symlink loops, file-count limits |
| Vault | crash window, authentication failure, key invalidation, partial write, restore/export |
| Cloud | disabled-by-default, auth failure, resumable upload, redirect handling, remote-ID reconciliation |
| AI | model present, model missing, TFLite unavailable, fallback mode, malformed embedding, resource exhaustion |
| Backup | merged manifest, Android backup, device transfer, vault exclusion, database exclusion |
| Release | R8, mapping, missing classes, AAB signature, checksum, provenance, SBOM |
| Platform | Android 11, 12, 13, 14, 15, 16; storage permissions; Keystore; WorkManager; large files |

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
