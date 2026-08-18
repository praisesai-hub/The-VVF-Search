# Google Play Data Safety & Privacy Guidance — VVF Smart Manager

This document is the release baseline for the Google Play **Data Safety** form and the in-app privacy notice. It must be reviewed again before enabling a new remote integration, telemetry provider, or cloud transfer build flag.

## 1. Privacy design

VVF Smart Manager is **local-first**. File metadata, duplicate hashes, OCR text, search embeddings, vault records, and recycle-bin records are processed and stored in the app sandbox on the device. The app does not send user files to a server for scanning, OCR, duplicate detection, or semantic matching.

The encrypted vault uses AES-256-GCM with an authenticated in-memory session key. Key material is created only after vault authentication and is backed by Android Keystore protections. Vault container filenames are opaque identifiers and do not repeat the source filename. App backups and device-transfer extraction are disabled because vault and authentication state must not be restored outside their original key lifecycle.

> **Storage limitation:** app-private storage and Android file-based encryption protect local records under the normal Android security model. A rooted, compromised, or physically forensically acquired device is a stronger threat model. The app does not claim that overwrite/delete operations guarantee physical erasure on flash storage.

## 2. Default network and telemetry posture

| Surface | Default behavior | User action required before activation |
|---|---|---|
| File scanning, OCR, duplicate matching, semantic fallback | On-device only | None |
| Firebase Crashlytics | Disabled | Explicit crash-reporting consent in a provisioned privacy setting; release build required |
| Cloud synchronization | Disabled in every default build | Approved OAuth provisioning, release build enablement, explicit device-owner opt-in, enabled provider, and a user-selected queue item |
| Cloud worker scheduling | Never scheduled at app startup | A user-selected item must first pass the cloud-consent policy |

A release must not enable `CLOUD_SYNC_ENABLED` until its OAuth client configuration, provider scopes, token lifecycle, account disconnect/revocation flow, transfer UX, and Play Data Safety declaration have all passed security review.

## 3. Data categories and retention

| Data category | Examples | Local use and storage | Remote sharing in the default build |
|---|---|---|---|
| Files and documents | name, URI/path, size, date, category | Room database for management and search | No |
| Derived local analysis | SHA-256, dHash, document fingerprint, local semantic embedding | Room database for duplicates/search | No |
| OCR text | on-device ML Kit result | Room database for local search | No |
| Vault content | user-selected sensitive files | encrypted GCM files in internal app storage | No |
| Vault metadata | original name, size, category, encrypted file path | app-private Room database; container filename is opaque | No |
| Authentication state | PIN verifier, PIN/biometric-wrapped vault DEK, optional OAuth tokens | Android-Keystore-protected encrypted no-backup storage | No by default |
| Diagnostics | crash reports | no collection by default | No by default |

Users can remove indexed metadata and app-private records through app data clearing or Android system settings. Vault and recycle-bin delete operations are best effort on modern flash storage; therefore the product copy must not make absolute physical-erasure promises.

## 4. Required Play Console answers for the default build

The following answers apply only while cloud transfer and crash reporting remain disabled by default:

| Play question | Default-build answer |
|---|---|
| Is user data collected or shared with third parties? | No remote collection or sharing by default. Local processing occurs for app functionality. |
| Is data encrypted in transit? | No outbound user-data transfer is enabled by default. If a future approved cloud build is enabled, HTTPS/TLS is mandatory. |
| Is data encrypted at rest? | Vault files and secure stores use authenticated encryption with Android Keystore-protected keys. General local metadata relies on Android app sandbox and device encryption. |
| Can users request deletion? | Yes. Users can delete app data through Android system settings and can remove managed content within the app. |

## 5. Cloud transfer security boundary

The current default build keeps cloud transfer disabled. If a future approved build enables Google Drive transfer, the app sends the selected source bytes to Google Drive over HTTPS. The app **does not** apply client-side end-to-end encryption, zero-knowledge encryption, or an app-managed encrypted cloud manifest before that upload. Any provider at-rest encryption or storage-access control is a provider control, not a VVF client-side encryption guarantee.

Vault encryption and cloud transfer are separate security boundaries. The vault encrypts vault content locally with authenticated encryption and Android Keystore-protected key material. It does not automatically transform a generic cloud upload into an end-to-end encrypted transfer. Product copy, onboarding, and Play Data Safety declarations must not describe Google Drive transfer as end-to-end encrypted, zero-knowledge, or client-side encrypted unless an independently reviewed implementation adds a per-file encryption key lifecycle, authenticated encrypted blob format, encrypted metadata manifest, key recovery model, and decrypt-on-download roundtrip coverage.

## 6. Release checklist

Before each production release, verify that backups remain disabled, no hardcoded secrets are present, Lint and Detekt are clean, tests pass, the signed release is built, and this document matches the actual release configuration. Any enabled telemetry or cloud-transfer capability requires a separate privacy review and an updated Play Data Safety form.
