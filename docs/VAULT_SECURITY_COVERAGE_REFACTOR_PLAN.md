# Vault और Security Coverage Refactoring Plan

## उद्देश्य और अपरिवर्तनीय सीमाएँ

इस plan का उद्देश्य JVM instruction coverage में **Vault gate को 95%** और **Security-critical gate को 90%** तक पहुँचाना है। इसमें production coverage policy को कम नहीं किया जाएगा, कोई class/package exclude नहीं किया जाएगा, और device-only code को अलग package में ले जाकर gate से बाहर नहीं किया जाएगा। सभी refactors Android Keystore, AES-256-GCM, local-first processing, और fail-closed persistence requirements को बनाए रखेंगे।

यह plan latest available hosted JVM JaCoCo report पर आधारित है। उस report में Security-critical scope `2,006 / 2,849` covered instructions (**70.41%**) और Vault scope `2,600 / 3,965` covered instructions (**65.57%**) था। Security target के लिए कम-से-कम `2,565` covered instructions चाहिए, अर्थात baseline से **559** additional covered instructions। Vault target के लिए कम-से-कम `3,767` covered instructions चाहिए, अर्थात baseline से **1,167** additional covered instructions।

> Coverage target प्राप्त करने का acceptable तरीका केवल meaningful behavior tests और testable design seams हैं। Suppression, threshold reduction, generated-class exclusion, synthetic no-op calls, package relocation, या production security behavior को कमजोर करना prohibited है।

## Measured Coverage Map

| Gate | Baseline | Target | Additional covered instructions required |
|---|---:|---:|---:|
| Security-critical (`com.example.security`, `com.example.auth`) | 2,006 / 2,849 — 70.41% | 90.00% | 559 |
| Vault (`Vault*` and `KeystoreVault*` classes in data/security) | 2,600 / 3,965 — 65.57% | 95.00% | 1,167 |

| Priority class | Missed | Covered | Why it matters |
|---|---:|---:|---|
| `VaultItemEntityJsonAdapter` | 353 | 400 | Generated adapter is inside the Vault class gate and requires direct typed round-trip/error tests. |
| `VaultManagerEngine` | 294 | 477 | PIN unlock, biometric completion, enrollment, lockout reset, and disable paths are the main orchestration gap. |
| `KeystoreVaultManager` | 236 | 575 | Key lifecycle/provisioning branches are Android-bound; cryptographic and policy logic can be made JVM-testable. |
| `VaultRepository` | 181 | 49 | Legacy V1→V2 migration and atomic replacement failure branches are largely untested. |
| `VaultManagerEngineKt` helpers | 97 | 127 | Envelope presence, Base64 decoding, migration, and PIN validation helpers require direct contract tests. |
| `SecureKeyValueStore` | 244 | 625 | Read/write/decode failures and atomic persistence boundaries remain a significant Security gap. |
| `FirebaseAuthManager` | 140 | 241 | Listener lifecycle, cancellation and provider failure branches remain in Security scope. |
| `LegacyEncryptedPreferencesMigration` | 109 | 37 | Read failure, store-exists, filtering, and commit-failure paths need isolated contracts. |

The five highest Vault classes contain 1,161 of 1,365 missed Vault instructions. Therefore, the Vault target cannot be reached by adding more screen-render tests; it requires direct tests and small boundary refactors in these production paths.

## Target Architecture

The refactor introduces narrow ports at Android boundaries while retaining `KeystoreVaultManager`, `VaultManagerEngine`, `VaultRepository`, and `SmartManagerRepository` as the production APIs. Public app behavior and persisted formats remain compatible.

```text
Compose / BiometricPrompt / Android Keystore / AtomicFile
                    │
                    ▼
             Thin Android adapters
                    │
                    ▼
     Vault/Security coordinators and policy objects
     - VaultAuthenticationStateMachine
     - VaultEnvelopeCoordinator
     - BiometricVaultCoordinator
     - VaultKeyStorePort
     - SecureEnvelopeCodec / AtomicStoreWriter
                    │
                    ▼
      Pure crypto and value objects, JVM-testable
      - AES-GCM codec
      - PBKDF2 PIN verifier/parser
      - envelope validation
      - lockout schedule
      - authenticated result model
```

### Design rules

| Rule | Required design decision |
|---|---|
| Keystore keys never leave Android Keystore in production | Ports return `SecretKey`/`Cipher` only to the coordinator that immediately uses them; test doubles use ephemeral JCE keys only. |
| No plaintext persistence | Envelope codecs operate only on DEK ciphertext, IV, salt, and version fields. Test fixtures must use random in-memory data, never real user files. |
| Biometric authentication is not faked as success in production | The Android adapter converts `BiometricPrompt.AuthenticationResult` into an authenticated-cipher value only after checking `cryptoObject.cipher`. |
| Tests must prove fail-closed behavior | Missing key, malformed Base64, invalid IV, failed commit, invalidated biometric key, and decrypt/authentication failures must return/throw the documented safe outcome. |
| No coverage evasion | Android adapter remains in the Security scope; its small device-only behavior is validated by instrumented tests. JVM target is reached by making policy/codec paths directly testable. |

## Workstream A — Security Module Refactor

### A1. Separate cryptographic policy from Android Keystore provisioning

Refactor `KeystoreVaultManager` into a facade over two internal collaborators.

| Collaborator | Responsibility | Test mode |
|---|---|---|
| `VaultKeyStorePort` | Alias existence, `SecretKey` lookup, create legacy key, create biometric-wrap key, delete biometric key | Deterministic fake for JVM; Android Keystore implementation for instrumented tests |
| `VaultCryptoCodec` | AES-GCM cipher initialization, random DEK validation, PBKDF2 parsing/verification, constant-time comparison, hex conversion | Pure JVM implementation |

The production Android implementation must retain the current properties: AES key size 256, GCM mode, no padding, biometric wrap key with strong biometric requirement, and invalidation after biometric enrollment changes. The facade must preserve legacy encrypt/decrypt methods strictly for V1 migration.

**Required JVM contracts:** alias already exists, missing alias, malformed key entry, provisioning failure, delete-when-absent, legacy cipher round trip, biometric cipher IV round trip, invalid IV, random-DEK size, PIN hash/verify, legacy SHA-256 compatibility, blank/malformed/out-of-range iteration credential rejection, odd/non-hex rejection, and constant-time verification outcome.

**Required instrumented contracts:** key creation in Android Keystore, alias persistence for process recreation, `KeyPermanentlyInvalidatedException` behavior after biometric enrollment change where the device supports it, and hardware/strong-biometric unavailable behavior. Instrumented tests may skip capability-specific assertions only with an explicit capability check, never by swallowing a failure.

### A2. Make secure-store envelope processing independently testable

`SecureKeyValueStore` already has a crypto seam. Extract the remaining serialization and atomic-file responsibilities into small internal collaborators:

| Collaborator | Behavior to preserve |
|---|---|
| `SecureEnvelopeCodec` | Header/version validation, bounded entry/value lengths, duplicate-key handling, malformed/truncated envelope rejection |
| `AtomicStoreWriter` | temp write, flush/fsync, atomic rename, cleanup after failure |
| `SecureStoreFileAccess` | no-backup path resolution and file existence checks |

`SecureKeyValueStore` remains the only application-facing API. JVM tests should exercise every codec and file-writer success/failure branch with a temporary directory and fake `SecureStoreCrypto`; Android Keystore encryption stays in an instrumented adapter test.

### A3. Complete auth and legacy migration branches

For `FirebaseAuthManager`, introduce a narrow `FirebaseAuthGateway` and credential-request factory so listener registration/removal, cancellation propagation, empty credential, Firebase exchange failure, and Microsoft provider failure are deterministic JVM tests. Do not mock static Android/Firebase types throughout the manager.

For `LegacyEncryptedPreferencesMigration`, inject a read-only legacy-preference source behind an internal port. Cover: target store already exists, no allowed entries, null/unreadable values, allowlist filtering, durable target commit success, target commit failure, and legacy read security/IO failures. The Android encrypted-preference reader remains a thin instrumented smoke-test adapter.

## Workstream B — Vault Module Refactor

### B1. Extract the authentication state machine

Move lockout and credential-flow decisions out of `VaultManagerEngine` into `VaultAuthenticationStateMachine`. It receives immutable `VaultLockoutState`, a clock value, and a verification result; it returns an explicit outcome such as `Allowed`, `Rejected(nextState)`, or `LockedUntil(timestamp)`.

This preserves the existing policy: 8–128 character PIN, at least one digit, no whitespace, five failures before lockout, exponential duration with 24-hour cap, and reset only after successful authentication. `VaultManagerEngine` remains responsible for committing the returned state through `StringKeyValueStore`.

**JVM matrix:** first failure, fourth failure, fifth failure, lockout boundary at `lockedUntilMs`, lockout expiry, exponent escalation, maximum-duration cap, malformed persisted counters/timestamps, commit failure, valid unlock reset, invalid PIN before hash lookup, and PIN-change failure while locked.

### B2. Extract envelope and biometric coordinators

Create `VaultEnvelopeCoordinator` for V1-to-V2 envelope creation/unwrap/rewrap and `BiometricVaultCoordinator` for storing/restoring a wrapped session key. The biometric coordinator receives an `AuthenticatedCipher` value object rather than directly depending on `BiometricPrompt.AuthenticationResult`; a thin Android adapter validates and converts the platform result.

| Path | JVM cases |
|---|---|
| PIN initialization | existing PIN, invalid PIN, random 32-byte DEK, durable commit success/failure, DEK zeroization in finally |
| PIN unlock | V2 unwrap, legacy migration, wrong PIN/authentication failure, malformed Base64/envelope, lockout reset only after valid unwrap |
| PIN change | valid rewrap, old-PIN failure, invalid new PIN, commit failure, corrupted current envelope |
| Biometric enrollment | missing authenticated cipher, encrypt failure, commit failure, successful stored IV/ciphertext/version |
| Biometric unlock | absent enrollment, missing cipher, malformed wrapped key, decrypt/authentication failure, successful session creation and lockout reset |
| Disable biometric | durable clear failure leaves key intact; successful clear deletes wrap key exactly once |

### B3. Refactor legacy vault-file migration for atomic behavior

`VaultRepository.migrateLegacyItem` combines legacy decrypt, session encrypt, filesystem replacement, metadata update, and DAO persistence. Extract `VaultFileRewriter` behind an `AtomicVaultFilePort` that is production-backed by `AtomicFile` and JVM-backed by a deterministic fake.

Test legacy file missing, malformed legacy IV, legacy decrypt failure, session encryption failure, `startWrite` failure, partial-write failure with `failWrite`, `finishWrite` failure, zero-length replacement, DAO insert failure, correct V2 metadata, biometric-protection flag, and plaintext zeroization. The production streaming path must not be replaced by an in-memory implementation; the test fake represents filesystem outcomes only.

### B4. Cover generated Vault serialization as a real contract

Add direct typed Moshi adapter tests for `VaultItemEntityJsonAdapter`, including full entity round trip, default/null-compatible fields, malformed/missing required field rejection, unknown-field behavior, enum/value boundaries, and `vaultFormatVersion` compatibility. Instantiate the generated adapter explicitly so coverage does not accidentally route through reflection.

## Phased Test and Refactor Matrix

| Milestone | Refactor batch | New test focus | Gate check | Exit criterion |
|---|---|---|---|---|
| M0 — Baseline | None | Archive current XML and class/method coverage map | Local policy checker only | No code or gate changes; target budget documented |
| M1 — Security pure core | A1 PIN/crypto + A2 envelope codec | malformed credential, cipher, serialization, atomic-write, fake-key lifecycle | JVM Security gate measured | All new behavior tests pass; no policy adjustment |
| M2 — Vault state/envelope | B1 + B2 | lockout, V1/V2 migration, PIN rewrap, biometric coordinator outcomes | JVM Vault gate measured | State-machine and envelope branches covered deterministically |
| M3 — Vault repository/adapter | B3 + B4 | atomic migration failures, DAO outcomes, generated JSON adapter contracts | JVM Vault gate measured | `VaultRepository` and `VaultItemEntityJsonAdapter` high-miss classes materially reduced |
| M4 — Android boundary | Keystore and biometric Android adapters | real Keystore provision/use/delete, BiometricPrompt capability paths, secure-store Android crypto | Instrumented policy measured | Device-only behavior verified; no mock-only security claim |
| M5 — Final gate | No speculative refactor | Full JVM + instrumented CI on one batched commit | Both policy files | Vault ≥95%, Security ≥90%, all other existing gates still pass |

The milestones intentionally avoid a CI dispatch per test file. One focused local/JVM validation is used during a milestone, then one hosted JVM validation after the batch, and one final hosted JVM plus instrumented validation after M4/M5. Existing coverage artifacts must be retained on failures.

## Test Fixture and Safety Rules

| Concern | Required fixture rule |
|---|---|
| Key material | Generate ephemeral random test keys; never use real vault aliases, production credentials, or real user files. |
| PINs | Use test-only values that meet the policy; assert only booleans/outcomes, never log hashes or salts. |
| Clocks | Inject a monotonic `nowMs` function; never use sleeping tests for lockout timing. |
| Filesystem | Use temporary directories and an `AtomicVaultFilePort` fake to force each failure point deterministically. |
| Android Keystore | JVM tests use a fake port/JCE test key. Instrumented tests exercise Android Keystore on supported devices. |
| Corruption | Build malformed envelopes/serialized bytes in memory and assert fail-closed behavior; do not mutate shared fixtures. |

## CI and Evidence Discipline

1. Keep existing package/class gate definitions unchanged.
2. Run `scripts/check_coverage_floor.py` against the generated XML after every milestone, and record actual numbers rather than predicted percentages.
3. Run the instrumented policy checker after M4 and retain the XML/HTML artifacts even if it fails.
4. Do not claim the target is met from test counts, method counts, or local mocks; only the hosted policy-check output is acceptance evidence.
5. Do not dispatch another full emulator run while an active validation for the same batch is still producing evidence.

## Acceptance Criteria

The refactoring is complete only when all conditions below are true.

| Category | Acceptance criterion |
|---|---|
| JVM Security | Hosted report passes `security-critical packages >= 90.00%`. |
| JVM Vault | Hosted report passes `vault >= 95.00%`. |
| Existing protections | Aggregate, repository/data, cloud-sync, and instrumented gates remain enforced and pass. |
| Cryptographic behavior | AES-256-GCM, Android Keystore use, PIN work factor, envelope versioning, and fail-closed behavior remain unchanged or are strengthened. |
| Device behavior | Instrumented tests validate actual Android Keystore/biometric adapter paths where supported. |
| Regression safety | Legacy V1 vault reads/migration, PIN lockout, secure-store migration, and atomic file replacement have deterministic success and failure tests. |
| Evidence | Final CI artifacts include JVM and instrumented JaCoCo reports plus policy-check output. |

## Explicit Non-Goals

This effort does not migrate the app to Hilt, alter vault cryptographic algorithms, lower lockout strength, remove legacy migration support, send files/keys to cloud services, or merge the branch to `main` before the gates pass.
