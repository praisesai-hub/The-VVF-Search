# VVF Smart Manager — Architecture & Design Documentation

## Overview
`VVF Smart Manager` is designed with clean architecture principles in Android using Kotlin, Coroutines/Flow, Room Database, and Jetpack Compose. The data layer is centered around modularized domain repositories, coordinated through a unified facade (`SmartManagerRepository`).

---

## 1. Repository Delegation & Modular Architecture

To prevent a monolithic codebase, `SmartManagerRepository` serves as a unified entry point (facade) that delegates specific domains to specialized repositories and manager components:

```
                         +--------------------------+
                         |  SmartManagerRepository  |
                         |         (Facade)         |
                         +------------+-------------+
                                      |
         +------------------+---------+--------+------------------+
         |                  |                  |                  |
         v                  v                  v                  v
+-----------------+ +---------------+ +------------------+ +------------------+
| FileRepository  | | VaultRepository| | PluginRepository | | DuplicateManager |
+-----------------+ +---------------+ +------------------+ +------------------+
```

### Domain Component Responsibilities

1. **`FileRepository`**
   - **Responsibility**: Manages device file metadata and soft deletion / recycle bin operations.
   - **Operations**: `insertFile`, `updateFile`, `deleteFileById`, `searchFiles`, `getFilesByCategory`, `moveFilesToRecycleBinAtomic`, `emptyRecycleBin`, `getRecycleBinFiles`, `restoreFromRecycleBin`.
   - **Data Flow**: Interfaces directly with Room `FileDao` for all active and recycled file records.

2. **`VaultRepository`**
   - **Responsibility**: Manages encrypted vault items and security operations.
   - **Operations**: Encrypting/decrypting files to/from secure local storage using `KeystoreVaultManager` (AES-256 GCM), inserting and deleting `VaultItemEntity` records in SQLite.

3. **`PluginRepository`**
   - **Responsibility**: Manages modular functionality plugins.
   - **Operations**: Storing plugin availability, enabling/disabling individual feature plugins, and querying installed plugin metadata via `FileDao`.

4. **`DuplicateManager`**
   - **Responsibility**: High-level duplicate file management and bulk operations.
   - **Operations**: Orchestrates scanning triggers, delegate calls to `DuplicateDetectionEngine`, and executing bulk deletion/cleanup routines.

---

## 2. Core Subsystems & Engine Architecture

### A. `OcrEngine` (Text Recognition Subsystem)
- **Design**: Encapsulates document text extraction capabilities. Uses Google ML Kit's `TextRecognition` engine (`MLKitOcrEngine`) for fast on-device optical character recognition.
- **Workflow**: 
  1. Accepts image or scanned document files.
  2. Processes bitmap inputs through ML Kit recognizers asynchronously.
  3. Returns extracted text strings stored alongside file entities in Room database for semantic file search.

### B. `CloudSyncWorker` (Background Synchronization)
- **Design**: Implemented using Android Jetpack `WorkManager` (`CoroutineWorker`).
- **Workflow**:
  1. Executes scheduled or on-demand background sync jobs (`WORK_NAME = "cloud_sync_work"`).
  2. Syncs local `CloudSyncItemEntity` records with remote cloud endpoints using Retrofit API services.
  3. Implements exponential backoff and retry mechanisms (`Result.retry()` / `Result.failure()`) on network errors.
  4. Maintains testability without global static mutation overrides.

### C. `DuplicateDetectionEngine` (Perceptual Hashing & LSH)
- **Design**: Perceptual hashing engine utilizing locality-sensitive hashing (LSH) and Hamming Distance algorithms (`HammingDistanceCalculator`).
- **Algorithms**:
  - **Images**: Generates perceptual difference hashes (`dHash`) based on pixel luminance comparison.
  - **Videos**: Generates `videoDHash` using keyframe sampling.
  - **Documents**: Generates content fingerprints (`computeDocumentFingerprint`).
  - **Similarity Check**: Uses bitwise XOR and popcount bit-distance evaluation to detect duplicate or near-duplicate media and documents within defined bit thresholds.

---

## 3. Storage Scanning Flow
- **`StorageScanner`**: Streams scanned device media and documents using Kotlin `Flow` (`scanDeviceStorageFlow`).
- **Memory Efficiency**: Emits batch chunks (`List<FileItemEntity>`) to avoid loading large storage trees into contiguous memory arrays simultaneously.
