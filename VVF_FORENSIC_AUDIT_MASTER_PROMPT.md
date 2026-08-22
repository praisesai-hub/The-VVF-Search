# 🔍 MASTER PROMPT — VVF SMART MEDIA MANAGER
## Forensic-Grade Complete Repository Audit
> **Zero-Assumption / Evidence-First / Production-Readiness Review**  
> **Repository:** [`praisesai-hub/The-VVF-Search`](https://github.com/praisesai-hub/The-VVF-Search)

---

## 🎯 MISSION

Perform a forensic-grade, evidence-driven audit of this entire repository.

The objective is to discover every meaningful:
- Bug
- Defect
- Security weakness
- Privacy violation
- Architectural flaw
- Data-integrity problem
- Concurrency problem
- Lifecycle problem
- Performance problem
- Reliability problem
- Build problem
- Release problem
- CI/CD problem
- GitHub governance problem
- Dependency problem
- Test weakness
- Production/test mismatch
- Documentation contradiction
- Incomplete implementation
- Unsafe fallback
- Missing validation
- Resource leak
- Error-handling defect
- Maintainability problem
- Technical debt
- Edge-case failure
- Regression
- Hidden assumption
- Configuration mistake
- Incorrect default
- Dead code
- Unreachable code
- Stale code
- Suspicious workaround
- Incomplete previous fix  
*...that can be established from repository evidence.*

> [!NOTE]
> The goal is **NOT** to make the repository "look good".  
> The goal is to determine what is **actually true**.

**Do not optimize for a short report.** Optimize for:
- Correctness
- Evidence
- Reproducibility
- Completeness
- Technical accuracy
- Root-cause identification
- Safe corrections
- Meaningful verification

> [!CAUTION]
> **IMPORTANT PRINCIPLES:**
> - Do not assume that previous audit conclusions are correct.
> - Do not assume that previous fixes are correct.
> - Do not assume that a passing test proves production correctness.
> - Do not assume that documentation describes actual behavior.
> - **Re-verify everything from current repository evidence.**

---

## ⚖️ ABSOLUTE OPERATING RULES

### RULE 1 — ZERO ASSUMPTIONS
Never treat any claim as true merely because:
- README says so
- Documentation says so
- A comment says so
- A test exists
- A test passed previously
- CI passed previously
- A commit message says "fixed"
- A function name suggests correct behavior
- A dependency is installed
- An interface exists
- A feature appears in the UI
- An earlier reviewer said it was fixed

**Verify it.** If it cannot be verified, mark it:
```text
UNVERIFIED — INSUFFICIENT EVIDENCE
```
- Do not convert uncertainty into a defect.
- Do not convert uncertainty into a success claim.

---

### RULE 2 — DOCUMENTATION FIRST
Before making architectural or behavioral conclusions, read all relevant repository documentation and project instructions.

Inspect, where present:
- `README` / `README.md`
- `CONTRIBUTING`
- `SECURITY`
- `LICENSE`
- `CHANGELOG`
- `docs/**`
- Architecture documentation
- Design documentation
- ADRs (Architecture Decision Records)
- Release documentation
- Build documentation
- Testing documentation
- CI/CD documentation
- GitHub documentation
- Inline code documentation
- `TODO` / `FIXME` / `HACK` / `XXX`
- Issue-related documentation committed to the repository
- Generated audit/evidence files
- Configuration documentation

> [!IMPORTANT]
> **Documentation establishes INTENDED BEHAVIOR. It does NOT establish ACTUAL BEHAVIOR.**  
> After reading the documentation, compare documented requirements against actual code and tests.

---

### RULE 3 — DO NOT MODIFY CODE DURING THE INITIAL AUDIT
The initial audit is **READ / ANALYZE / TEST / RECORD / PROPOSE**.
- Do **NOT** automatically fix source code while discovering findings.
- Do **NOT** modify production code simply because you found a defect.
- Do **NOT** alter tests to make them pass.
- Do **NOT** weaken assertions.
- Do **NOT** bypass CI.
- Do **NOT** disable failing tests.
- Do **NOT** change rulesets.
- Do **NOT** change branch protection.
- Do **NOT** hide failures.
- Do **NOT** suppress warnings merely to produce a cleaner result.

**Preserve the original evidence.**  
Only implement fixes if explicitly instructed after the audit findings have been reviewed.

---

### RULE 4 — PHASED EXECUTION
Do **NOT** attempt the entire audit as one giant uninterrupted operation. Execute the audit phase-by-phase.

After every completed phase:
1. Summarize what was inspected
2. Record findings
3. Record evidence
4. Record commands executed
5. Record test/build results
6. Record unresolved questions
7. Record exact next phase
8. Persist the information into: `docs/audit/`

Use suitable files such as:
- `docs/audit/00-baseline.md`
- `docs/audit/01-documentation.md`
- `docs/audit/02-source-inventory.md`
- `docs/audit/03-architecture.md`
- `docs/audit/04-security.md`
- `docs/audit/05-database.md`
- `docs/audit/06-android.md`
- `docs/audit/07-core-features.md`
- `docs/audit/08-ai-duplicates.md`
- `docs/audit/09-tests.md`
- `docs/audit/10-build-release.md`
- `docs/audit/11-ci-github.md`
- `docs/audit/12-dependencies.md`
- `docs/audit/13-performance.md`
- `docs/audit/14-recovery.md`
- `docs/audit/15-git-history.md`
- `docs/audit/16-adversarial.md`
- `docs/audit/17-cross-check.md`
- `docs/audit/FINAL-AUDIT-REPORT.md`

> [!NOTE]
> If the repository already contains an audit/evidence structure, preserve it and extend it rather than destroying it. Do not overwrite existing evidence without first preserving it.

---

### RULE 5 — RESUMABILITY
If context limits, execution limits, tool limits, network restrictions, missing Android SDK, missing emulator, or any other environmental limitation prevents completion:

**STOP CLEANLY.**  
Persist:
- Current phase
- Completed work
- Exact files inspected
- Exact commands executed
- Findings discovered
- Tests executed
- Tests not executed
- Reason for stopping
- Exact next action

Then continue from that persisted state.
- Do **NOT** restart the entire audit unnecessarily.
- Do **NOT** pretend the audit is complete.

---

### RULE 6 — READ ACTUAL CODE
Do not perform a superficial grep-only audit. Inspect the actual implementation.

Trace important features through their actual call paths:
```text
UI 
 ↳ state / ViewModel 
    ↳ domain / use-case 
       ↳ repository 
          ↳ data source 
             ↳ database / filesystem / network / native layer
```
- Inspect callers and callees.
- Follow important data transformations.
- Follow error paths.
- Follow success paths.
- Follow cancellation paths.
- Follow lifecycle paths.

---

### RULE 7 — NO FALSE POSITIVES
A suspicious pattern is not automatically a defect. Before classifying something as a finding, establish:
- What the code actually does
- When the condition occurs
- Why it matters
- What expected behavior is
- What evidence proves the problem
- Whether the problem is reachable
- Whether another layer already prevents it

If insufficient evidence exists, classify as:
```text
UNVERIFIED
```
Do not present speculation as fact.

---

### RULE 8 — NO UNVERIFIED SUCCESS CLAIMS
Never say:
> `"fixed"` | `"verified"` | `"production-ready"` | `"safe"` | `"working"` | `"resolved"`

...unless evidence proves it.

- A proposed code change is **NOT** a verified fix.
- A passing unit test is **NOT** automatically proof of production correctness.
- A successful debug build is **NOT** proof of release correctness.
- A successful CI job is **NOT** proof that all required behavior works.

**Verification must correspond to the actual failure mode.**

---

## 📋 AUDIT EXECUTION PHASES

### PHASE 0 — Repository Identity and Baseline
Establish and record:
- Repository root
- Current branch & current HEAD commit SHA
- Working-tree status & remotes / submodules
- Repository structure & modules
- Generated files & ignored files
- Build systems, languages & toolchain versions:
  - Java / JDK
  - Gradle & Android Gradle Plugin (AGP)
  - Kotlin
  - Android SDK requirements: `compileSdk`, `targetSdk`, `minSdk`
- Build variants, application IDs & package names
- Test modules & native modules (if present)
- Flutter / Dart versions (if applicable)

*Record exact evidence. Do not modify anything.*

---

### PHASE 1 — Complete Documentation Ingestion
Read all relevant documentation. Build an internal requirements matrix:

| Requirement | Source Document | Documented Claim | Implementation Location | Test Location | CI Evidence | Release Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |

**Allowed Statuses:** `VERIFIED` | `PARTIALLY VERIFIED` | `CONTRADICTED` | `NOT IMPLEMENTED` | `UNVERIFIED`

Pay special attention to claims involving:
- 100% offline operation & privacy (zero cloud uploads, no accounts, no advertisements)
- Local AI & semantic duplicate detection
- File scanning (internal storage vs. SD card, Android 11+ support)
- Foreground service & background execution
- Supported file formats
- Database encryption & data integrity
- File operations: Deletion, recycle bin, restore, permanent deletion
- OCR, embeddings & similarity thresholds
- Resumable operations, cancellation, retry & crash recovery
- Release readiness & CI guarantees

---

### PHASE 2 — Complete Source Inventory
Enumerate and inspect all relevant files across:
- **Languages / Formats:** Kotlin, Java, XML, Gradle (Groovy/Kotlin DSL), YAML, JSON, TOML, SQL, Dart, Python, Shell scripts, Markdown, ProGuard/R8 rules, `AndroidManifest.xml`, resources, assets, native code, test fixtures, configuration, and build logic.

Identify:
- Dead, unreachable, and duplicate code
- Unused dependencies and resources
- `TODO`, `FIXME`, `HACK`, `XXX` comments
- Magic constants, hardcoded paths, hardcoded credentials, and suspicious strings
- Swallowed exceptions, empty catches, and silent fallbacks
- Unsafe defaults, inconsistent naming, contradictory or incomplete implementations

---

### PHASE 3 — Architecture and Data-Flow Audit
Trace major functionality end-to-end. Inspect:
- Dependency boundaries, module boundaries & clean architecture adherence
- Feature boundaries & UI / business logic separation
- Repository contracts, database boundaries, filesystem boundaries & network boundaries
- State management & lifecycle ownership

Look for:
- Circular dependencies & business logic in UI
- Data-layer leakage & shared mutable state
- Incorrect coroutine scope or dispatcher (e.g. blocking main thread)
- Race conditions, cancellation bugs, retry bugs, stale/inconsistent state
- Resource leaks, failure recovery defects & lifecycle leaks

---

### PHASE 4 — Security and Privacy Forensics
Perform a hostile security review. Inspect:
- Secrets, tokens, API keys, credentials, and logs
- Exported Activities, Services, Receivers, Providers, Intents, and Deep Links
- Permissions, filesystem access, URI handling, temporary files, backups
- Database files, encryption, key generation, and Android Keystore
- SQL injection / queries, path handling, archive extraction, deserialization, WebView
- Network access: HTTP, HTTPS, TLS, certificate validation, debug vs. release settings
- Analytics, telemetry, crash reporting, cloud services, accidental uploads

> [!WARNING]
> For an offline/privacy-first application, prove that no hidden network/cloud path violates the documented privacy model.  
> If network access exists for any reason, identify:
> - Exact call path & purpose
> - Trigger & data transmitted
> - Whether user data can leave the device
> - Whether documentation accurately discloses it

---

### PHASE 5 — Database / Room / Storage Forensics
Audit:
- Database schema, entities, DAOs, queries, migrations, transactions, indices, constraints, foreign keys, cascades, and nullability
- Concurrency, initialization, lifecycle, encryption, key storage, backup, restore, corruption handling, destructive migrations, orphan/stale rows, and partial operations

For every operation touching both physical files and database rows, trace:
1. `Before operation`
2. `Physical operation`
3. `Database operation`
4. `Commit`
5. `UI / state update`

Then determine what happens if the process dies at every stage:
- Crash / process death
- Cancellation / permission loss
- Insufficient storage / file disappearance
- Duplicate invocation / partial deletion
- Interrupted move / interrupted restore / retry

---

### PHASE 6 — Android Platform Forensics
Inspect:
- `AndroidManifest.xml` & permissions
- Scoped storage, MediaStore, Storage Access Framework (SAF), direct filesystem access, SD card behavior
- Android version-specific behaviors (Android 11, 12, 13, 14, 15, 16)
- Foreground services & foreground-service type declarations
- Notifications, background execution & WorkManager
- Broadcasts, lifecycle, process death, configuration changes, URI permissions
- Activity, Fragment, and Compose lifecycles
- Memory pressure, large media handling, and cancellation

---

### PHASE 7 — Core Feature Forensics
Deeply audit every major feature:
- **A.** File discovery
- **B.** Storage scanning
- **C.** Media indexing
- **D.** Exact duplicate detection
- **E.** Near duplicate detection
- **F.** Semantic duplicate detection
- **G.** Hashing
- **H.** Sampling
- **I.** OCR
- **J.** AI / embeddings
- **K.** Similarity calculations
- **L.** Grouping / clustering
- **M.** Selection
- **N.** Deletion
- **O.** Recycle bin
- **P.** Restore
- **Q.** Permanent deletion
- **R.** Resumable operations
- **S.** Progress reporting
- **T.** Cancellation
- **U.** Retry
- **V.** Recovery
- **W.** Database persistence
- **X.** Foreground / background execution

For every major feature test conceptually and experimentally:
- Empty input & single item
- Duplicate and near-duplicate items
- Large datasets
- Missing / malformed metadata & corrupt files
- Unsupported formats & permission denied scenarios
- File disappears or changes during processing
- Storage unavailable or full
- Process death, cancellation, retry, concurrent execution, partial failure, repeated invocation, device restart

---

### PHASE 8 — AI / Duplicate-Detection Forensics
Do not assume semantic correctness merely because a similarity score is produced. Inspect:
- Model loading, model availability & local-only guarantees
- Preprocessing, embedding dimensions, normalization, sampling & temporal sampling
- OCR, text extraction, fallback behavior, missing metadata & unknown states
- Threshold semantics, similarity calculation, grouping, deterministic behavior
- Model/version compatibility & memory usage

**Explicitly check for:**
- `UNKNOWN` treated as `MATCH`
- Missing metadata treated as valid evidence
- Extraction failure treated as similarity
- Empty values treated as equivalent
- Invalid hash evidence & ignored size mismatches
- Sampling bias & insufficient temporal samples
- Threshold inversion & floating-point edge cases
- Unstable grouping, false positives & false negatives

---

### PHASE 9 — Test Forensics
Read every relevant test. Do not merely count tests.

Compare:
```text
PRODUCTION IMPLEMENTATION  ↔  TEST IMPLEMENTATION  ↔  FAKE / MOCK  ↔  FIXTURE  ↔  EXPECTED CONTRACT
```

Look for:
- Fake DAO semantics differing from Room
- Fake filesystem semantics differing from Android
- Mocks hiding failures & weak assertions
- Implementation-detail assertions
- Missing failure-path, concurrency, cancellation, process-death, migration, encryption, permission, or large-file tests
- Instrumentation tests not matching production resources
- Flaky, disabled, or skipped tests
- Tests that pass while production behavior is broken

*Assess meaningful coverage gaps. Do not confuse line coverage with behavioral coverage.*

---

### PHASE 10 — Build and Release Forensics
Perform clean builds where the environment permits. Verify:
- Clean checkout & Gradle wrapper integrity
- Dependency resolution & compilation
- Lint, unit tests, instrumentation tests
- Release build, AAB, APK
- R8 / ProGuard rules & resource shrinking
- Manifest merging & signing configuration
- Versioning

Inspect the actual release artifact:
- Manifest permissions & exported components
- Included libraries & debug artifacts
- Network capabilities & resources
- Native libraries, minification, obfuscation & release-only behavior

*Do not accept "build succeeded" as sufficient evidence.*

---

### PHASE 11 — CI/CD and GitHub Forensics
Audit every GitHub Actions workflow:
- Triggers, permissions, secrets, `pull_request`, `pull_request_target`, `workflow_dispatch`
- Fork behavior, untrusted code handling, checkout behavior, token exposure
- Artifact handling, caching, dependency installation, pinned action versions
- Shell execution, environment variables, permission escalation, deployment & release automation

Inspect repository governance:
- Branch protection & rulesets
- Required checks & merge restrictions
- Bypass permissions & `CODEOWNERS`
- Dependabot, CodeQL & dependency submission
- Release rules

*Determine whether governance actually enforces the documented rules.*

---

### PHASE 12 — Dependency / Supply Chain Audit
Inspect direct and transitive dependencies:
- Known vulnerabilities (CVEs)
- Outdated or abandoned libraries
- Unnecessary dependencies & conflicting versions
- Dynamic versions & unpinned sources
- Git dependencies & suspicious repositories
- Unsafe plugins & dependency substitution risks
- Excessive permissions requested by dependencies

For every vulnerability determine:
- Reachability & exploitability in this application
- Severity & remediation path

---

### PHASE 13 — Performance and Resource Forensics
Identify:
- $\mathcal{O}(n^2)$ algorithms
- Repeated scans & repeated hashing
- Unnecessary I/O & excessive memory consumption
- Bitmap leaks & unbounded collections
- Entire-file memory loading
- Inefficient database queries & missing pagination
- Excessive coroutine creation & thread starvation
- Blocking I/O on UI/Main threads
- Repeated AI model loading
- Excessive battery consumption & foreground-service misuse

*Where possible, estimate real-world impact.*

---

### PHASE 14 — Failure / Recovery Forensics
For every persistent, destructive, or resumable operation ask:  
> **"What happens if the process dies at this exact point?"**

Trace every state transition and identify:
- Partial state & inconsistent database
- Orphan files & missing files
- Duplicate rows & stale progress
- Unrecoverable state
- Incorrect or duplicate retry
- Non-idempotent operations & corrupted operation state

---

### PHASE 15 — Git History Forensics
Inspect relevant repository history:
- Reverted fixes & incomplete fixes
- Temporary workarounds
- Tests changed without production fix
- Production changed without tests
- Documentation changed without implementation
- Repeated fixes for the same root cause
- Fixes that introduce regressions
- Commits claiming verification without sufficient evidence

*Commit messages are not proof.*

---

### PHASE 16 — Adversarial Review
Act simultaneously as:
1. Senior Android Engineer
2. Security Engineer
3. Database Engineer
4. QA Engineer
5. SRE / Reliability Engineer
6. Privacy Auditor
7. Release Engineer
8. Malicious / Untrusted PR Reviewer
9. End User with corrupted files
10. Maintainer responsible for long-term support

Try to break every important feature. For every feature ask:
> *"How could this fail while all existing tests still pass?"*  
Then investigate those failure modes.

---

### PHASE 17 — Cross-Layer Contract Audit
Compare cross-layer contracts to identify contradictions:
```text
DOCUMENTATION  ↔  IMPLEMENTATION  ↔  INTERFACES  ↔  DATABASE  ↔  TESTS  ↔  INSTRUMENTATION  ↔  CI  ↔  RELEASE ARTIFACT  ↔  GITHUB GOVERNANCE
```

Examples of contradictions:
- Documentation promises offline, but code has network paths.
- Production DAO behaves differently from Fake DAO.
- Release configuration differs from tested configuration.
- CI checks a different task than required release.
- UI displays a state that the database does not persist.
- Database records state before the physical file operation succeeds.
- Tests assert a different contract than production code.

---

### PHASE 18 — Finding Validation
Every confirmed/probable finding **MUST** contain:

```yaml
ID: "FINDING-XXX"
Severity: "CRITICAL | HIGH | MEDIUM | LOW | INFORMATIONAL"
Category: "Security | Architecture | Database | Android | AI | Build | Tests | Docs"
Confidence: "CONFIRMED | PROBABLE | UNVERIFIED"
Exact File: "path/to/file.kt"
Exact Line/Range: "L120-L145"
Evidence: "Detailed explanation and proof"
Observed Behavior: "What actually happens"
Expected Behavior: "What should happen"
Why Risky: "Impact and reasoning"
Trigger/Reproduction: "Step-by-step reproduction"
Impact: "Exploitability / Data Loss / Crash"
Root Cause: "Underlying issue"
Recommended Correction: "Smallest safe fix"
Verification Method: "How to test"
Regression Test Recommendation: "Specific test case"
```

> [!CAUTION]
> Never silently convert one confidence level into another (`UNVERIFIED` → `CONFIRMED` without direct evidence).

---

### PHASE 19 — Self-Designed Correction
For every `CONFIRMED` or `PROBABLE` finding, design a concrete correction:
- Exact file(s) and code/configuration area
- Logical change required
- Root cause addressed
- Compatibility and migration implications
- Potential side effects
- Required regression test & documentation change
- Exact verification commands

*Prefer the smallest safe correction. Do not recommend unnecessary rewrites.*

---

### PHASE 20 — Second-Pass Completeness Check
After the first complete audit, perform a deliberate second pass to discover missed findings:
- [ ] Did every documented requirement get checked?
- [ ] Did every major feature get traced end-to-end?
- [ ] Did every test suite get examined?
- [ ] Did every workflow get audited?
- [ ] Was the release artifact inspected?
- [ ] Were previous fixes independently reverified?
- [ ] Were small defects investigated?
- [ ] Were suspicious items separated from proven defects?
- [ ] Were all environmental limitations recorded?
- [ ] Did any conclusion rely solely on documentation?

---

## 📑 FINAL REPORT STRUCTURE

Create: `docs/audit/FINAL-AUDIT-REPORT.md`

Use this exact structure:

```markdown
# VVF Smart Media Manager — Final Forensic Audit

## 1. Executive Verdict
Choose exactly one: [ GO | CONDITIONAL GO | NO-GO ]
Explain why.

## 2. Audit Baseline
- Repository & Branch
- HEAD SHA & Audit Date
- Working-Tree State
- Environment & Toolchain
- Limitations

## 3. Audit Scope
- Files/Directories inspected
- Modules inspected
- Documentation inspected
- Tests inspected & executed
- Builds executed
- Workflows & Artifacts inspected
- Git History inspected

## 4. Requirements Verification Matrix
Requirement → Documentation → Implementation → Test → CI → Release Evidence → Status

## 5. Critical Findings
Full evidence for every CRITICAL finding.

## 6. High Findings
Full evidence for every HIGH finding.

## 7. Medium Findings
Full evidence for every MEDIUM finding.

## 8. Low Findings
Full evidence for every LOW finding.

## 9. Security and Privacy
All security/privacy findings.

## 10. Database / Room / Encryption
All database and encryption findings.

## 11. Android Platform
All Android-specific findings.

## 12. AI / Duplicate Detection
All semantic/duplicate-detection findings.

## 13. Test Quality
All testing weaknesses and production/test mismatches.

## 14. CI/CD / GitHub Governance
All workflow/ruleset/branch/security findings.

## 15. Build / Release
All release/build/artifact findings.

## 16. Documentation Contradictions
Every meaningful documentation mismatch.

## 17. Technical Debt
Separate technical debt from actual defects.

## 18. Unverified Items
Suspicious items that could not be conclusively proven (Why suspicious, missing evidence, required proof).

## 19. Previous-Fix Verification
Original issue → Claimed fix → Current implementation → Regression test → Verification result → Current status:
[ CONFIRMED FIXED | REGRESSED | PARTIALLY FIXED | NOT FIXED | UNVERIFIED ]

## 20. Prioritized Fix Plan
- P0 — Release blockers
- P1 — Must fix before production
- P2 — Should fix
- P3 — Improvement
Finding → Root Cause → Correction → Regression Test → Verification

## 21. Exact Post-Fix Verification Plan
Exact commands/tasks post-correction (Clean build, unit tests, instrumentation tests, lint, security, release build, CI).

## 22. Final Release Gate
- Current release blockers
- Confirmed fixed issues
- Unresolved issues
- Unverified issues
- Required evidence
- Whether release is allowed
```

---

## 🛡️ FINAL HONESTY REQUIREMENT

> [!CAUTION]
> **NEVER write:**  
> `"Everything looks good."` | `"All issues are fixed."` | `"Production ready."` | `"Verified."`  
> ...unless the evidence actually proves the statement.

If something cannot be tested because of the environment, write:
```text
UNVERIFIED — ENVIRONMENT LIMITATION
```
and explicitly state:
- Exactly what could not be tested
- Why
- What evidence is required
- How it should be tested externally

**Guidelines:**
- Do not hide limitations.
- Do not downgrade severity merely to achieve `GO`.
- Do not stop after finding major problems.
- Do not ignore small problems.
- Do not create speculative findings.
- Do not alter evidence to make the repository appear healthier.

---

## ⚡ MANDATORY EXECUTION PRIORITY

The execution order is mandatory:
1. Understand repository instructions.
2. Read documentation.
3. Establish baseline.
4. Inventory repository.
5. Build requirements matrix.
6. Audit implementation.
7. Audit security/privacy.
8. Audit database/storage.
9. Audit Android behavior.
10. Audit core features.
11. Audit AI/duplicate detection.
12. Audit tests.
13. Build and release.
14. Audit CI/CD/GitHub.
15. Audit dependencies.
16. Audit performance.
17. Audit recovery.
18. Audit Git history.
19. Perform adversarial review.
20. Perform second-pass cross-check.
21. Produce final evidence-backed verdict.

> [!NOTE]
> Never skip a phase silently. If a phase cannot be completed, record:
> ```text
> PHASE STATUS: BLOCKED
> REASON: ...
> EVIDENCE OBTAINED: ...
> REMAINING WORK: ...
> NEXT ACTION: ...
> ```

---

## 🎯 MOST IMPORTANT OBJECTIVE

**Find what is actually wrong.**

- **For every real problem:**  
  `PROVE IT` → `EXPLAIN IT` → `IDENTIFY ROOT CAUSE` → `DESIGN THE SAFEST CORRECTION` → `DEFINE THE REGRESSION TEST` → `DEFINE THE VERIFICATION` → `RECORD THE EVIDENCE`

- **For every uncertain problem:**  
  `SHOW WHY IT IS SUSPICIOUS` → `EXPLAIN WHY IT IS NOT YET PROVEN` → `DEFINE WHAT EVIDENCE IS REQUIRED`

The final report must allow another engineer to independently reproduce the conclusions without trusting your opinion.

---
*END OF MASTER PROMPT*
