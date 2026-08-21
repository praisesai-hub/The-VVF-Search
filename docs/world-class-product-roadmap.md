# The-VVF-Search World-Class Product Roadmap

**Product ambition:** Build a privacy-first Android file intelligence and vault product that users can trust with their personal files, while matching leading products in reliability, speed, clarity, accessibility, and release discipline.

> World-class is not a feature count. It is the combination of trustworthy data handling, predictable recovery, fast interaction, transparent privacy controls, accessible UX, measurable quality, and a merge path that cannot bypass failed gates.

## Product north star

The app should make local file discovery, duplicate review, secure vaulting, and optional cloud transfer feel simple and reversible. Every destructive action must be reviewable, recoverable where technically possible, and represented by a durable state machine. Every privacy-sensitive capability must be explicit, local-first, and understandable to a non-technical user.

| Product pillar | World-class outcome | Primary measurement |
|---|---|---|
| Trust and privacy | No silent data loss, no raw sensitive-path leakage, fail-closed vault and sync behavior. | Zero unresolved P0 security findings; tested recovery windows; sanitized production logs. |
| Reliability | Scans, indexing, vault operations, and sync survive interruption, process death, permission changes, and partial failures. | Crash-free sessions, successful recovery scenarios, bounded retry rates, zero orphaned committed operations. |
| Speed | Search and duplicate review remain responsive on large libraries. | Measured time-to-first-result, p95 scan throughput, bounded memory, no main-thread I/O. |
| Clarity | Users understand what is scanned, protected, uploaded, deleted, or blocked. | Task completion, permission-denial recovery, accessibility review, low support ambiguity. |
| Quality | Every release is reproducible, analyzed, tested, signed, and merge-protected. | Green required checks, signed AAB evidence, SBOM, coverage policy, protected `main`. |

## Release sequence

### Phase 0: Release integrity, P0 blockers

The first release objective is not to add features. It is to make the repository trustworthy. The current evidence shows Android CI failure, unresolved unit and instrumentation failures, and an unprotected `main` branch. The latest repository state also shows that CodeQL and dependency submission can be green while Android CI remains red, which proves that individual workflow success is not equivalent to release readiness.

The team must first make Android unit tests and instrumentation tests green, retrieve and retain JUnit artifacts, resolve the duplicate-manager and resumable-upload contract failures without weakening assertions, and keep the sanitized Vault PIN and Drive error contracts consistent across unit and device tests. Then `main` must require the Android build, unit tests, instrumentation tests, CodeQL, dependency/security checks, lint/static analysis, and coverage gates before merge.

**Acceptance:** the latest commit has a terminal green Android CI/CD run, green CodeQL and dependency checks, no failed required checks, and a repository API response proving branch protection or ruleset enforcement.

### Phase 1: Data integrity and secure state machines

Room data and physical files must be treated as one recoverable system. Every destructive or relocating operation should have an operation record with an explicit state such as `PREPARED`, `PHYSICAL_COMPLETED`, `COMMITTED`, or `FAILED`. Recovery must be idempotent, and reconciliation must be tested after crashes before and after each irreversible boundary.

The database encryption boundary must be decided explicitly. If Room stores paths, names, OCR text, embeddings, cloud identifiers, or vault metadata that are sensitive at rest, integrate a supported encrypted database design and test key/passphrase lifecycle. If some metadata remains plaintext by design, document the classification and backup policy honestly.

**Acceptance:** crash-window tests cover encryption, database insertion, restore overwrite, physical deletion, missing files, orphan files, and corrupted files; recovery produces a single authoritative state; no destructive migration or silent fallback exists.

### Phase 2: World-class duplicate intelligence

Duplicate detection must clearly separate **candidate generation** from **definitive evidence**. A bucket key is only an indexing optimization. It must never itself imply equality or justify deletion. The pipeline should be:

```text
candidate bucket
    -> metadata tri-state filter
    -> temporal/perceptual comparison
    -> confidence and evidence record
    -> user review
    -> reversible action
```

Video comparison should use duration-aware distributed sampling rather than assuming that the first three aligned samples are sufficient for every duration. Missing metadata must have an explicit `MATCH`, `MISMATCH`, or `UNKNOWN` state. `UNKNOWN` must not be treated as `MATCH` for automatic deletion. MD5 may remain as a fast legacy candidate signal, but size plus SHA-256 should be the canonical identity for integrity-sensitive decisions.

**Acceptance:** benchmark corpus reports precision, recall, false-positive rate, and false-negative rate for exact, re-encoded, cropped, reordered, and unrelated videos; automatic deletion is disabled for low-confidence or unknown-evidence cases; evidence is visible in the review UI.

### Phase 3: Search and indexing excellence

The search experience should be local-first, incremental, cancellable, and resilient to process death. Indexing must use bounded work, backpressure, WorkManager constraints, and observable progress. Search results should appear progressively, retain stable ordering, and explain when OCR or semantic indexing is unavailable.

Semantic search must degrade gracefully when the native model is unavailable. The application should never crash because a TFLite library or model is absent. Model version, embedding version, and index status should be explicit so migrations can be reasoned about rather than silently mixing incompatible vectors.

**Acceptance:** large-library benchmark on representative devices, p95 time-to-first-result target defined and met, no unbounded queues, no main-thread disk or bitmap work, and deterministic recovery after process death.

### Phase 4: Vault and authentication excellence

The vault should use a single PIN policy source, persistent failed-attempt state, exponential cooldown, restart persistence, monotonic-time safeguards where possible, biometric interaction rules, and clear user recovery behavior. Keys must be Android Keystore-backed in production, with hardware-security capability recorded rather than universally claimed. Test environments should use injected fakes, not production memory-key fallbacks.

Vault file formats must be versioned and authenticate metadata through AAD. A file record should bind the ciphertext to a stable record identifier, format version, original size, and MIME type. Key rotation and migration must be explicit. Database passphrases and sensitive buffers should have a documented lifecycle, while avoiding claims of complete RAM erasure that the runtime cannot guarantee.

**Acceptance:** wrong-key, wrong-IV, modified-ciphertext, modified-tag, truncation, corrupted-envelope, Keystore reset, key invalidation, restart lockout, clock manipulation, biometric failure, PIN migration, and crash-window tests pass on supported Android versions.

### Phase 5: Storage, permissions, and backup trust

The application should prefer scoped storage and SAF, explain why a permission is needed, request broad storage access only when the product and Play policy genuinely justify it, and remain useful when access is denied or later revoked. Every physical path must resolve under an approved root before operation, with symlink and race considerations documented.

Backup policy must be verified from the merged artifact and device behavior. Vault keys, PIN material, database passphrases, and sensitive metadata must not be accidentally transferred. The UI should provide a clear, intentional export or recovery model rather than relying on opaque platform backup behavior.

**Acceptance:** denial, revocation, SAF fallback, Android 12–16 scoped-storage behavior, merged-manifest inspection, data-extraction-rule inspection, and device-transfer tests pass.

### Phase 6: UX, accessibility, and product polish

The product surface should be organized around a small number of clear jobs: find files, review duplicates, protect files, recover actions, and optionally sync. Destructive actions should use confirmation sheets with evidence, impact, and undo/recovery state. Empty, loading, permission-denied, model-unavailable, offline, and partial-failure states need first-class designs.

The UI must support large fonts, TalkBack semantics, keyboard navigation where relevant, contrast requirements, reduced motion, localization, and predictable screen-reader announcements for scan progress and vault state. Brand naming, package identity, theme, launcher icon, and README claims should all describe the same product truth.

**Acceptance:** accessibility audit, screenshot review on light/dark themes, large-font test, TalkBack smoke test, and end-to-end task completion review on representative devices.

## Engineering quality system

| Area | Required practice |
|---|---|
| Architecture | Keep domain contracts explicit; isolate filesystem, database, cloud, crypto, and UI adapters; use dependency injection for testability. |
| Error handling | Map expected failures to stable domain codes; rethrow cancellation; distinguish retryable network/storage conditions from permanent authorization or validation failures. |
| Observability | Use structured, privacy-safe events with operation ID, entity ID, state, duration, and error code. Never log full paths, user filenames, vault identifiers, tokens, or raw provider errors in release builds. |
| Performance | Measure scan throughput, indexing throughput, memory, battery, query latency, and UI frame health on realistic libraries. |
| Security | Map controls to OWASP MASVS storage, crypto, auth, network, platform, code, resilience, and privacy areas. |
| Build | Pin dependencies where practical, validate wrapper provenance, generate SBOM and license evidence, run R8, inspect mapping, and produce signed AAB evidence. |
| Governance | Protect `main`, require pull requests and approvals, prevent bypass of required checks, and retain test and release artifacts. |

## Definition of world-class readiness

The app may be called world-class only when the following statements are simultaneously true:

| Statement | Required proof |
|---|---|
| It protects user data at rest. | Explicit Room and vault data classification, encryption implementation or documented boundary, backup validation, and key lifecycle tests. |
| It does not lose or silently corrupt files. | Crash-window, reconciliation, atomicity, orphan, and restore-overwrite tests. |
| It is fast on real libraries. | Device benchmark results with p95 latency, throughput, memory, battery, and frame metrics. |
| It is understandable. | Permission, failure, recovery, accessibility, localization, and destructive-action UX reviews. |
| It is safe to release. | Green required CI, protected branch, clean release build, signed AAB, R8 mapping, SBOM, SAST, dependency, license, and secret evidence. |

## Current decision

> **Current status: Build toward world-class, but Release Candidate remains BLOCKED.**

The first implementation sprint should close the red Android CI and instrumentation tests, preserve their exact regression coverage, and establish merge enforcement. Only after that should the team expand duplicate-algorithm benchmarks, Room encryption, UX polish, and performance work under a green, protected release pipeline.

## References

[1]: https://github.com/praisesai-hub/The-VVF-Search/actions "The-VVF-Search GitHub Actions"
[2]: https://github.com/praisesai-hub/The-VVF-Search/pulls "Open pull requests"
[3]: https://mas.owasp.org/MASVS/ "OWASP Mobile Application Security Verification Standard"
[4]: https://developer.android.com/privacy-and-security/backup "Android backup and restore guidance"
[5]: https://docs.github.com/en/rest/branches/branch-protection "GitHub branch protection documentation"
