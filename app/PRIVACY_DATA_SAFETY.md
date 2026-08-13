# Google Play Data Safety & Privacy Guidance — VVF Smart Manager

This document provides complete, accurate information required to complete the **Data Safety** section in Google Play Console for **VVF Smart Manager**.

---

## 1. Executive Privacy Overview

- **Core Storage Model**: **Device-First & Local-Only**. All scanned file metadata, duplicate analysis hashes, OCR text indices, and recycle bin records are strictly stored on the user's local device.
- **Secure Vault Privacy**: Files moved to the Secure Vault are encrypted locally on the device using **AES-256-GCM** keys backed by the **Android Keystore**. Vault files are never uploaded to any cloud server.
- **Optional Cloud Sync**: Cloud synchronization is optional and triggered only when the user configures cloud integration. Sync requests are transmitted over secure HTTPS/TLS.

---

## 2. Play Console Data Safety Declarations

### A. Data Collection & Sharing Summary

| Data Category | Data Type | Collected / Access | Shared | Stored On-Device | Stored Remotely | Purpose |
|---|---|---|---|---|---|---|
| **Files and docs** | Files & document metadata (names, sizes, paths, categories) | Yes | No | Yes (Room DB) | No | App functionality (File management, search, duplicate detection) |
| **Files and docs** | Photos, Videos, Documents | Yes | No | Yes | No | App functionality (OCR text scanning, perceptual hash generation) |
| **Personal Info / Credentials** | Vault PIN & Biometric hashes | Yes | No | Yes (Android Keystore / Encrypted) | No | Account management & Security (Vault authentication) |
| **App info and performance** | Crash logs (Firebase Crashlytics) | Optional | Yes (Google/Firebase) | No | Yes | Analytics & Bug fixing (Only active when Firebase is configured) |

---

## 3. Detailed Data Breakdown

### 1. Files & Media Metadata
- **Data Collected**: File names, file sizes, creation/modification dates, file category (Images, Videos, PDFs, Documents, Archives).
- **Perceptual Hashes**: Perceptual difference hashes (`dHash`), Hamming distance vectors, and document fingerprints generated locally for AI/smart duplicate detection.
- **OCR Text**: Text extracted from scanned documents/images via on-device ML Kit.
- **Storage Location**: Local SQLite database (`app_database.db`) in internal application storage (`/data/data/com.example/databases/`).
- **Data Sharing**: **Not shared** with any third party.

### 2. Secure Vault Content
- **Data Collected**: Files selected by the user to be encrypted into the Vault.
- **Encryption at Rest**: AES-256-GCM encryption with keys generated and stored in the hardware-backed **Android Keystore System**.
- **Storage Location**: Internal application storage (`/data/data/com.example/files/vault/`).
- **Data Sharing**: **Strictly Device-Only. Never shared.**

### 3. Optional Cloud Synchronization (`CloudSyncWorker`)
- **Data Transmitted**: User-selected sync item records (`CloudSyncItemEntity`).
- **Encryption in Transit**: Transmitted securely using **HTTPS / TLS 1.2+**.
- **Destination**: User-configured or enterprise cloud service endpoint (via Retrofit API).

---

## 4. Encryption & Security Declarations for Play Store

When completing the Play Console questionnaire:

1. **Is data encrypted in transit?**
   - **Yes**. All outbound network requests (Cloud Sync, API calls) use standard HTTPS/TLS encryption.

2. **Do you provide a way for users to request that their data be deleted?**
   - **Yes**. Users can delete all app data, cleared recycle bins, and vault contents directly within the app UI or via Android System Settings (`Clear Data`). Since data is local-only, deleting the app removes all stored data permanently.

3. **Does the app collect data from children?**
   - **No**.

4. **Is data collection optional or required?**
   - Storage access is required for core file management features. Cloud sync is optional.
