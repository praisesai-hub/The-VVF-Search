MASTER PROMPT — VVF SMART MEDIA MANAGER

FORENSIC-GRADE COMPLETE REPOSITORY AUDIT

ZERO-ASSUMPTION / EVIDENCE-FIRST / PRODUCTION-READINESS REVIEW

Repository:

praisesai-hub/The-VVF-Search

MISSION

\=======

Perform a forensic-grade, evidence-driven audit of this entire repository.

The objective is to discover every meaningful:

\- bug

\- defect

\- security weakness

\- privacy violation

\- architectural flaw

\- data-integrity problem

\- concurrency problem

\- lifecycle problem

\- performance problem

\- reliability problem

\- build problem

\- release problem

\- CI/CD problem

\- GitHub governance problem

\- dependency problem

\- test weakness

\- production/test mismatch

\- documentation contradiction

\- incomplete implementation

\- unsafe fallback

\- missing validation

\- resource leak

\- error-handling defect

\- maintainability problem

\- technical debt

\- edge-case failure

\- regression

\- hidden assumption

\- configuration mistake

\- incorrect default

\- dead code

\- unreachable code

\- stale code

\- suspicious workaround

\- incomplete previous fix

that can be established from repository evidence.

The goal is NOT to make the repository "look good".

The goal is to determine what is actually true.

Do not optimize for a short report.

Optimize for:

\- correctness

\- evidence

\- reproducibility

\- completeness

\- technical accuracy

\- root-cause identification

\- safe corrections

\- meaningful verification.

IMPORTANT:

Do not assume that previous audit conclusions are correct.

Do not assume that previous fixes are correct.

Do not assume that a passing test proves production correctness.

Do not assume that documentation describes actual behavior.

Re-verify everything from current repository evidence.

\============================================================

ABSOLUTE OPERATING RULES

\============================================================

RULE 1 — ZERO ASSUMPTIONS

Never treat any claim as true merely because:

\- README says so

\- documentation says so

\- a comment says so

\- a test exists

\- a test passed previously

\- CI passed previously

\- a commit message says "fixed"

\- a function name suggests correct behavior

\- a dependency is installed

\- an interface exists

\- a feature appears in the UI

\- an earlier reviewer said it was fixed.

Verify it.

If it cannot be verified, mark it:

UNVERIFIED — INSUFFICIENT EVIDENCE

Do not convert uncertainty into a defect.

Do not convert uncertainty into a success claim.

\------------------------------------------------------------

RULE 2 — DOCUMENTATION FIRST

Before making architectural or behavioral conclusions, read all relevant repository documentation and project instructions.

Inspect, where present:

\- README

\- README.md

\- CONTRIBUTING

\- SECURITY

\- LICENSE

\- CHANGELOG

\- docs/\*\*

\- architecture documentation

\- design documentation

\- ADRs

\- release documentation

\- build documentation

\- testing documentation

\- CI/CD documentation

\- GitHub documentation

\- inline code documentation

\- TODO

\- FIXME

\- HACK

\- XXX

\- issue-related documentation committed to the repository

\- generated audit/evidence files

\- configuration documentation.

Documentation establishes INTENDED BEHAVIOR.

It does NOT establish ACTUAL BEHAVIOR.

After reading the documentation, compare documented requirements against actual code and tests.

\------------------------------------------------------------

RULE 3 — DO NOT MODIFY CODE DURING THE INITIAL AUDIT

The initial audit is READ / ANALYZE / TEST / RECORD / PROPOSE.

Do NOT automatically fix source code while discovering findings.

Do NOT modify production code simply because you found a defect.

Do NOT alter tests to make them pass.

Do NOT weaken assertions.

Do NOT bypass CI.

Do NOT disable failing tests.

Do NOT change rulesets.

Do NOT change branch protection.

Do NOT hide failures.

Do NOT suppress warnings merely to produce a cleaner result.

Preserve the original evidence.

Only implement fixes if explicitly instructed after the audit findings have been reviewed.

\------------------------------------------------------------

RULE 4 — PHASED EXECUTION

Do NOT attempt the entire audit as one giant uninterrupted operation.

Execute the audit phase-by-phase.

After every completed phase:

1\. summarize what was inspected

2\. record findings

3\. record evidence

4\. record commands executed

5\. record test/build results

6\. record unresolved questions

7\. record exact next phase

8\. persist the information into:

docs/audit/

Use suitable files such as:

docs/audit/00-baseline.md

docs/audit/01-documentation.md

docs/audit/02-source-inventory.md

docs/audit/03-architecture.md

docs/audit/04-security.md

docs/audit/05-database.md

docs/audit/06-android.md

docs/audit/07-core-features.md

docs/audit/08-ai-duplicates.md

docs/audit/09-tests.md

docs/audit/10-build-release.md

docs/audit/11-ci-github.md

docs/audit/12-dependencies.md

docs/audit/13-performance.md

docs/audit/14-recovery.md

docs/audit/15-git-history.md

docs/audit/16-adversarial.md

docs/audit/17-cross-check.md

docs/audit/FINAL-AUDIT-REPORT.md

If the repository already contains an audit/evidence structure, preserve it and extend it rather than destroying it.

Do not overwrite existing evidence without first preserving it.

\------------------------------------------------------------

RULE 5 — RESUMABILITY

If context limits, execution limits, tool limits, network restrictions, missing Android SDK, missing emulator, or any other environmental limitation prevents completion:

STOP CLEANLY.

Persist:

\- current phase

\- completed work

\- exact files inspected

\- exact commands executed

\- findings discovered

\- tests executed

\- tests not executed

\- reason for stopping

\- exact next action.

Then continue from that persisted state.

Do NOT restart the entire audit unnecessarily.

Do NOT pretend the audit is complete.

\------------------------------------------------------------

RULE 6 — READ ACTUAL CODE

Do not perform a superficial grep-only audit.

Inspect the actual implementation.

Trace important features through their actual call paths:

UI

→ state/ViewModel

→ domain/use-case

→ repository

→ data source

→ database/filesystem/network/native layer.

Inspect callers and callees.

Follow important data transformations.

Follow error paths.

Follow success paths.

Follow cancellation paths.

Follow lifecycle paths.

\------------------------------------------------------------

RULE 7 — NO FALSE POSITIVES

A suspicious pattern is not automatically a defect.

Before classifying something as a finding, establish:

\- what the code actually does

\- when the condition occurs

\- why it matters

\- what expected behavior is

\- what evidence proves the problem

\- whether the problem is reachable

\- whether another layer already prevents it.

If insufficient evidence exists:

UNVERIFIED

Do not present speculation as fact.

\------------------------------------------------------------

RULE 8 — NO UNVERIFIED SUCCESS CLAIMS

Never say:

"fixed"

"verified"

"production-ready"

"safe"

"working"

"resolved"

unless evidence proves it.

A proposed code change is NOT a verified fix.

A passing unit test is NOT automatically proof of production correctness.

A successful debug build is NOT proof of release correctness.

A successful CI job is NOT proof that all required behavior works.

Verification must correspond to the actual failure mode.

\============================================================

PHASE 0 — REPOSITORY IDENTITY AND BASELINE

\============================================================

Establish and record:

\- repository root

\- current branch

\- current HEAD

\- exact commit SHA

\- working-tree status

\- remotes

\- submodules

\- repository structure

\- modules

\- generated files

\- ignored files

\- build systems

\- languages

\- toolchain versions

\- Java/JDK

\- Gradle

\- Android Gradle Plugin

\- Kotlin

\- Android SDK requirements

\- compile SDK

\- target SDK

\- minimum SDK

\- build variants

\- application IDs

\- package names

\- test modules

\- native modules if present

\- Flutter/Dart versions if applicable.

Record exact evidence.

Do not modify anything.

\============================================================

PHASE 1 — COMPLETE DOCUMENTATION INGESTION

\============================================================

Read all relevant documentation.

Build an internal requirements matrix:

Requirement

→ source document

→ exact documented claim

→ implementation location

→ test location

→ CI evidence

→ release evidence

→ status.

Allowed status:

VERIFIED

PARTIALLY VERIFIED

CONTRADICTED

NOT IMPLEMENTED

UNVERIFIED

Pay special attention to claims involving:

\- 100% offline operation

\- privacy

\- zero cloud uploads

\- local AI

\- semantic duplicate detection

\- file scanning

\- internal storage

\- SD card

\- Android 11+

\- foreground service

\- background execution

\- supported file formats

\- database encryption

\- data integrity

\- deletion

\- recycle bin

\- restore

\- permanent deletion

\- OCR

\- embeddings

\- similarity thresholds

\- resumable operations

\- cancellation

\- retry

\- crash recovery

\- no account

\- no advertisements

\- release readiness

\- CI guarantees.

\============================================================

PHASE 2 — COMPLETE SOURCE INVENTORY

\============================================================

Enumerate and inspect all relevant:

\- Kotlin

\- Java

\- XML

\- Gradle

\- Kotlin DSL

\- YAML

\- JSON

\- TOML

\- SQL

\- Dart

\- Python

\- shell scripts

\- Markdown

\- ProGuard/R8

\- AndroidManifest

\- resources

\- assets

\- native code

\- test fixtures

\- configuration

\- scripts

\- build logic.

Identify:

\- dead code

\- duplicate code

\- unreachable code

\- unused dependencies

\- unused resources

\- TODO

\- FIXME

\- HACK

\- XXX

\- magic constants

\- hardcoded paths

\- hardcoded credentials

\- suspicious strings

\- swallowed exceptions

\- empty catches

\- silent fallbacks

\- unsafe defaults

\- inconsistent naming

\- contradictory implementations

\- incomplete implementations.

\============================================================

PHASE 3 — ARCHITECTURE AND DATA-FLOW AUDIT

\============================================================

Trace major functionality end-to-end.

Inspect:

\- dependency boundaries

\- module boundaries

\- clean architecture adherence

\- feature boundaries

\- UI/business logic separation

\- repository contracts

\- database boundaries

\- filesystem boundaries

\- network boundaries

\- state management

\- lifecycle ownership.

Look for:

\- circular dependencies

\- business logic in UI

\- data-layer leakage

\- shared mutable state

\- incorrect coroutine scope

\- incorrect dispatcher

\- blocking main thread

\- race conditions

\- cancellation bugs

\- retry bugs

\- stale state

\- inconsistent state

\- resource leaks

\- failure recovery defects

\- lifecycle leaks.

\============================================================

PHASE 4 — SECURITY AND PRIVACY FORENSICS

\============================================================

Perform a hostile security review.

Inspect:

\- secrets

\- tokens

\- API keys

\- credentials

\- logs

\- exported Activities

\- Services

\- Receivers

\- Providers

\- Intents

\- deep links

\- permissions

\- filesystem access

\- URI handling

\- temporary files

\- backups

\- database files

\- encryption

\- key generation

\- Android Keystore

\- SQL queries

\- path handling

\- archive extraction

\- deserialization

\- WebView

\- network access

\- HTTP

\- HTTPS

\- TLS

\- certificate validation

\- debug settings

\- release settings

\- analytics

\- telemetry

\- crash reporting

\- cloud services

\- accidental uploads.

For an offline/privacy-first application, prove that no hidden network/cloud path violates the documented privacy model.

If network access exists for any reason, identify:

\- exact call path

\- purpose

\- trigger

\- data transmitted

\- whether user data can leave the device

\- whether documentation accurately discloses it.

\============================================================

PHASE 5 — DATABASE / ROOM / STORAGE FORENSICS

\============================================================

Audit:

\- database schema

\- entities

\- DAOs

\- queries

\- migrations

\- transactions

\- indices

\- constraints

\- foreign keys

\- cascades

\- nullability

\- concurrency

\- initialization

\- lifecycle

\- encryption

\- key storage

\- backup

\- restore

\- corruption handling

\- destructive migrations

\- orphan rows

\- stale rows

\- partial operations.

For every operation touching both physical files and database rows, trace:

1\. before operation

2\. physical operation

3\. database operation

4\. commit

5\. UI/state update.

Then determine what happens if the process dies at every stage.

Test or reason about:

\- crash

\- process death

\- cancellation

\- permission loss

\- insufficient storage

\- file disappearance

\- duplicate invocation

\- partial deletion

\- interrupted move

\- interrupted restore

\- retry.

\============================================================

PHASE 6 — ANDROID PLATFORM FORENSICS

\============================================================

Inspect:

\- AndroidManifest

\- permissions

\- scoped storage

\- MediaStore

\- Storage Access Framework

\- direct filesystem access

\- SD card behavior

\- Android 11

\- Android 12

\- Android 13

\- Android 14

\- Android 15

\- Android 16 where relevant

\- foreground services

\- foreground-service type declarations

\- notifications

\- background execution

\- WorkManager

\- broadcasts

\- lifecycle

\- process death

\- configuration changes

\- URI permissions

\- Activity lifecycle

\- Fragment lifecycle

\- Compose lifecycle if applicable

\- memory pressure

\- large media

\- cancellation.

Identify version-specific behavior differences.

\============================================================

PHASE 7 — CORE FEATURE FORENSICS

\============================================================

Deeply audit every major feature.

At minimum inspect:

A. File discovery

B. Storage scanning

C. Media indexing

D. Exact duplicate detection

E. Near duplicate detection

F. Semantic duplicate detection

G. Hashing

H. Sampling

I. OCR

J. AI/embeddings

K. Similarity calculations

L. Grouping/clustering

M. Selection

N. Deletion

O. Recycle bin

P. Restore

Q. Permanent deletion

R. Resumable operations

S. Progress

T. Cancellation

U. Retry

V. Recovery

W. Database persistence

X. Foreground/background execution.

For every major feature test conceptually and, where possible, experimentally:

\- empty input

\- one item

\- duplicate items

\- near duplicate items

\- large datasets

\- missing metadata

\- malformed metadata

\- corrupt files

\- unsupported formats

\- permission denied

\- file disappears

\- file changes during processing

\- storage unavailable

\- storage full

\- process death

\- cancellation

\- retry

\- concurrent execution

\- partial failure

\- repeated invocation

\- device restart where applicable.

\============================================================

PHASE 8 — AI / DUPLICATE-DETECTION FORENSICS

\============================================================

Do not assume semantic correctness merely because a similarity score is produced.

Inspect:

\- model loading

\- model availability

\- local-only guarantee

\- preprocessing

\- embedding dimensions

\- normalization

\- sampling

\- temporal sampling

\- OCR

\- text extraction

\- fallback behavior

\- missing metadata

\- unknown states

\- threshold semantics

\- similarity calculation

\- grouping

\- deterministic behavior

\- model/version compatibility

\- memory usage.

Explicitly look for:

\- UNKNOWN treated as MATCH

\- missing metadata treated as valid evidence

\- extraction failure treated as similarity

\- empty values treated as equivalent

\- invalid hash evidence

\- size mismatch ignored

\- sampling bias

\- insufficient temporal samples

\- threshold inversion

\- floating-point edge cases

\- unstable grouping

\- false positives

\- false negatives.

\============================================================

PHASE 9 — TEST FORENSICS

\============================================================

Read every relevant test.

Do not merely count tests.

Compare:

PRODUCTION IMPLEMENTATION

vs

TEST IMPLEMENTATION

vs

FAKE/MOCK

vs

FIXTURE

vs

EXPECTED CONTRACT.

Look for:

\- fake DAO semantics differing from Room

\- fake filesystem semantics differing from Android

\- mocks hiding failures

\- weak assertions

\- implementation-detail assertions

\- missing failure-path tests

\- missing concurrency tests

\- missing cancellation tests

\- missing process-death tests

\- missing migration tests

\- missing encryption tests

\- missing permission tests

\- missing large-file tests

\- instrumentation tests not matching production resources

\- flaky tests

\- disabled tests

\- skipped tests

\- tests that pass while production behavior is broken.

Assess meaningful coverage gaps.

Do not confuse line coverage with behavioral coverage.

\============================================================

PHASE 10 — BUILD AND RELEASE FORENSICS

\============================================================

Perform clean builds where the environment permits.

Verify:

\- clean checkout

\- Gradle wrapper

\- dependency resolution

\- compilation

\- lint

\- unit tests

\- instrumentation tests

\- release build

\- AAB

\- APK

\- R8

\- ProGuard

\- resource shrinking

\- manifest merging

\- signing configuration

\- versioning.

Inspect the actual release artifact.

Check:

\- manifest

\- permissions

\- exported components

\- included libraries

\- debug artifacts

\- network capability

\- resources

\- native libraries

\- minification

\- obfuscation

\- release-only behavior.

Do not accept "build succeeded" as sufficient evidence.

\============================================================

PHASE 11 — CI/CD AND GITHUB FORENSICS

\============================================================

Audit every GitHub Actions workflow.

Inspect:

\- triggers

\- permissions

\- secrets

\- pull\_request

\- pull\_request\_target

\- workflow\_dispatch

\- fork behavior

\- untrusted code

\- checkout behavior

\- token exposure

\- artifact handling

\- caching

\- dependency installation

\- pinned action versions

\- shell execution

\- environment variables

\- permissions escalation

\- deployment

\- release automation.

Inspect repository governance:

\- branch protection

\- rulesets

\- required checks

\- merge restrictions

\- bypass permissions

\- CODEOWNERS

\- Dependabot

\- CodeQL

\- dependency submission

\- release rules.

Determine whether governance actually enforces the documented rules.

\============================================================

PHASE 12 — DEPENDENCY / SUPPLY CHAIN AUDIT

\============================================================

Inspect direct and transitive dependencies.

Look for:

\- known vulnerabilities

\- outdated dependencies

\- abandoned libraries

\- unnecessary dependencies

\- conflicting versions

\- dynamic versions

\- unpinned sources

\- Git dependencies

\- suspicious repositories

\- unsafe plugins

\- dependency substitution risks

\- excessive permissions.

For every vulnerability determine:

\- whether reachable

\- whether exploitable in this application

\- severity

\- remediation.

\============================================================

PHASE 13 — PERFORMANCE AND RESOURCE FORENSICS

\============================================================

Look for:

\- O(n²) algorithms

\- repeated scans

\- repeated hashing

\- unnecessary I/O

\- excessive memory

\- bitmap leaks

\- unbounded collections

\- entire-file loading

\- inefficient database queries

\- missing pagination

\- excessive coroutine creation

\- thread starvation

\- blocking I/O

\- repeated AI model loading

\- excessive battery consumption

\- foreground-service misuse.

Where possible estimate real-world impact.

\============================================================

PHASE 14 — FAILURE / RECOVERY FORENSICS

\============================================================

For every persistent, destructive, or resumable operation ask:

"What happens if the process dies at this exact point?"

Trace every state transition.

Identify:

\- partial state

\- inconsistent database

\- orphan files

\- missing files

\- duplicate rows

\- stale progress

\- unrecoverable state

\- incorrect retry

\- duplicate retry

\- non-idempotent operations

\- corrupted operation state.

\============================================================PHASE 15 — GIT HISTORY FORENSICS

\============================================================

Inspect relevant history.

Look for:

\- reverted fixes

\- incomplete fixes

\- temporary workarounds

\- tests changed without production fix

\- production changed without tests

\- documentation changed without implementation

\- repeated fixes for same root cause

\- fixes that introduce regressions

\- commits claiming verification without sufficient evidence.

Commit messages are not proof.

\============================================================

PHASE 16 — ADVERSARIAL REVIEW

\============================================================

Act simultaneously as:

1\. Senior Android engineer

2\. Security engineer

3\. Database engineer

4\. QA engineer

5\. SRE/reliability engineer

6\. Privacy auditor

7\. Release engineer

8\. Malicious/untrusted PR reviewer

9\. End user with corrupted files

10\. Maintainer responsible for long-term support.

Try to break every important feature.

For every feature ask:

"How could this fail while all existing tests still pass?"

Then investigate those failure modes.

\============================================================

PHASE 17 — CROSS-LAYER CONTRACT AUDIT

\============================================================

Compare:

DOCUMENTATION

↔

IMPLEMENTATION

↔

INTERFACES

↔

DATABASE

↔

TESTS

↔

INSTRUMENTATION

↔

CI

↔

RELEASE ARTIFACT

↔

GITHUB GOVERNANCE.

Look for contradictions.

Examples:

\- documentation promises offline but code has network path

\- production DAO behaves differently from fake DAO

\- release configuration differs from tested configuration

\- CI checks a different task than required release

\- instrumentation expects different resource text

\- UI displays a state that database does not persist

\- database records state before physical operation succeeds

\- tests assert a different contract than production.

\============================================================

PHASE 18 — FINDING VALIDATION

\============================================================

Every confirmed/probable finding MUST contain:

ID

Severity

Category

Confidence

Exact file

Exact line/range where possible

Evidence

Observed behavior

Expected behavior

Why it is wrong/risky

Trigger/reproduction

Impact

Root cause

Recommended correction

Verification method

Regression test recommendation.

Severity:

CRITICAL

HIGH

MEDIUM

LOW

INFORMATIONAL

Confidence:

CONFIRMED

PROBABLE

UNVERIFIED

Never silently convert one confidence level into another.

\============================================================

PHASE 19 — SELF-DESIGNED CORRECTION

\============================================================

For every CONFIRMED or PROBABLE finding, design a concrete correction.

Provide:

\- exact file(s)

\- exact code/configuration area

\- logical change required

\- root cause addressed

\- compatibility implications

\- migration implications

\- side effects

\- required regression test

\- required documentation change

\- exact verification commands.

Prefer the smallest safe correction.

Do not recommend unnecessary rewrites.

If the root cause requires architectural change, explain why a smaller fix is insufficient.

\============================================================

PHASE 20 — SECOND-PASS COMPLETENESS CHECK

\============================================================

After the first complete audit, perform a deliberate second pass.

The purpose of this pass is ONLY to discover missed findings.

Re-check:

\- undocumented files

\- unvisited directories

\- overlooked workflows

\- overlooked tests

\- error paths

\- fallback paths

\- lifecycle paths

\- cancellation

\- concurrency

\- persistence boundaries

\- release-only code

\- debug-only code

\- security boundaries

\- dependency boundaries

\- previous fixes

\- recent commits.

Ask:

1\. Did every documented requirement get checked?

2\. Did every major feature get traced end-to-end?

3\. Did every test suite get examined?

4\. Did every workflow get audited?

5\. Was the release artifact inspected?

6\. Were previous fixes independently reverified?

7\. Were small defects investigated?

8\. Were suspicious items separated from proven defects?

9\. Were all environmental limitations recorded?

10\. Did any conclusion rely solely on documentation?

\============================================================

FINAL REPORT

\============================================================

Create:

docs/audit/FINAL-AUDIT-REPORT.md

Use this exact structure:

\# VVF Smart Media Manager — Final Forensic Audit

\## 1. Executive Verdict

Choose exactly one:

GO

CONDITIONAL GO

NO-GO

Explain why.

\## 2. Audit Baseline

Record:

\- repository

\- branch

\- HEAD SHA

\- audit date

\- working-tree state

\- environment

\- toolchain

\- limitations.

\## 3. Audit Scope

Record:

\- files/directories inspected

\- modules inspected

\- documentation inspected

\- tests inspected

\- tests executed

\- builds executed

\- workflows inspected

\- artifacts inspected

\- Git history inspected.

\## 4. Requirements Verification Matrix

For every important requirement:

Requirement

→ Documentation

→ Implementation

→ Test

→ CI

→ Release Evidence

→ Status.

\## 5. Critical Findings

Full evidence for every CRITICAL finding.

\## 6. High Findings

Full evidence for every HIGH finding.

\## 7. Medium Findings

Full evidence for every MEDIUM finding.

\## 8. Low Findings

Full evidence for every LOW finding.

\## 9. Security and Privacy

All security/privacy findings.

\## 10. Database / Room / Encryption

All database and encryption findings.

\## 11. Android Platform

All Android-specific findings.

\## 12. AI / Duplicate Detection

All semantic/duplicate-detection findings.

\## 13. Test Quality

All testing weaknesses and production/test mismatches.

\## 14. CI/CD / GitHub Governance

All workflow/ruleset/branch/security findings.

\## 15. Build / Release

All release/build/artifact findings.

\## 16. Documentation Contradictions

Every meaningful documentation mismatch.

\## 17. Technical Debt

Separate technical debt from actual defects.

\## 18. Unverified Items

Every suspicious item that could not be conclusively proven.

For each:

\- why it is suspicious

\- why evidence is insufficient

\- what evidence would prove/disprove it.

\## 19. Previous-Fix Verification

For every previously claimed fix found in repository history or audit documentation:

\- original issue

\- claimed fix

\- current implementation

\- regression test

\- verification result

\- current status.

Use:

CONFIRMED FIXED

REGRESSED

PARTIALLY FIXED

NOT FIXED

UNVERIFIED.

\## 20. Prioritized Fix Plan

P0 — Release blockers

P1 — Must fix before production

P2 — Should fix

P3 — Improvement

For each:

Finding

→ Root Cause

→ Correction

→ Regression Test

→ Verification.

\## 21. Exact Post-Fix Verification Plan

Provide exact commands/tasks that must be run after corrections.

Include:

\- clean checkout

\- clean build

\- unit tests

\- instrumentation tests

\- lint/static analysis

\- security checks

\- release build

\- artifact inspection

\- CI verification.

\## 22. Final Release Gate

Explicitly state:

\- current release blockers

\- confirmed fixed issues

\- unresolved issues

\- unverified issues

\- required evidence

\- whether release is allowed.

\============================================================

FINAL HONESTY REQUIREMENT

\============================================================

NEVER write:

"Everything looks good."

"All issues are fixed."

"Production ready."

"Verified."

unless the evidence actually proves the statement.

If something cannot be tested because of the environment, write:

UNVERIFIED — ENVIRONMENT LIMITATION

and state:

\- exactly what could not be tested

\- why

\- what evidence is required

\- how it should be tested externally.

Do not hide limitations.

Do not downgrade severity merely to achieve GO.

Do not stop after finding major problems.

Do not ignore small problems.

Do not create speculative findings.

Do not alter evidence to make the repository appear healthier.

\============================================================

IMPORTANT EXECUTION PRIORITY

\============================================================

The order is mandatory:

1\. Understand repository instructions.

2\. Read documentation.

3\. Establish baseline.

4\. Inventory repository.

5\. Build requirements matrix.

6\. Audit implementation.

7\. Audit security/privacy.

8\. Audit database/storage.

9\. Audit Android behavior.

10\. Audit core features.

11\. Audit AI/duplicate detection.

12\. Audit tests.

13\. Build and release.

14\. Audit CI/CD/GitHub.

15\. Audit dependencies.

16\. Audit performance.

17\. Audit recovery.

18\. Audit Git history.

19\. Perform adversarial review.

20\. Perform second-pass cross-check.

21\. Produce final evidence-backed verdict.

Never skip a phase silently.

If a phase cannot be completed, record:

PHASE STATUS:

BLOCKED

REASON:

...

EVIDENCE OBTAINED:

...

REMAINING WORK:

...

NEXT ACTION:

...

\============================================================

MOST IMPORTANT OBJECTIVE

\============================================================

Find what is actually wrong.

For every real problem:

PROVE IT

→ EXPLAIN IT

→ IDENTIFY ROOT CAUSE

→ DESIGN THE SAFEST CORRECTION

→ DEFINE THE REGRESSION TEST

→ DEFINE THE VERIFICATION

→ RECORD THE EVIDENCE.

For every uncertain problem:

SHOW WHY IT IS SUSPICIOUS

→ EXPLAIN WHY IT IS NOT YET PROVEN

→ DEFINE WHAT EVIDENCE IS REQUIRED.

The final report must allow another engineer to independently reproduce the conclusions without trusting your opinion.

END OF MASTER PROMPT.