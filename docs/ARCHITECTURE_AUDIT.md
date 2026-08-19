# Architecture audit findings

## Current canonical entry points

- `VVFSmartManagerApp` creates a single `MainViewModel`.
- `MainViewModel` owns UI state and directly exposes `SmartManagerRepository` through `repository`.
- `SmartManagerRepository` is the app-level service locator and a god-object: it owns DAO access, `FileRepository`, `VaultRepository`, `PluginRepository`, OCR/model selection, duplicate scan orchestration, file/trash operations, cloud queue policy, WorkManager scheduling, retry policy, and vault pass-through methods.

## Compatibility surfaces

- `MainViewModelCompat.kt` is not an obsolete shim in practice. It supplies most UI-facing flows and commands: duplicate state, vault PIN/session state, paging, biometric callbacks, file/trash actions, and semantic search results. `MainViewModelCompatTest` exercises these APIs as behavior contracts.
- `SmartManagerRepositoryCompat.kt` provides the UI's `searchFiles`, stats, duplicate groups, plugin, cloud, and vault flow aliases. It is a compatibility extension layer over `SmartManagerRepository`, but it is still imported by the production UI.
- Compatibility code therefore needs a staged migration boundary rather than immediate deletion.

## Vault boundary

- `VaultRepository` is a storage/application orchestration layer: DAO mutations, physical encrypt/decrypt/restore, legacy migration, and authenticated-session requirements.
- `VaultManagerEngine` is a focused security primitive for PIN envelopes, biometric wrapping, and secure preference storage.
- `VaultSecurityApi` composes `VaultPinApi`, `VaultBiometricApi`, and `VaultSessionApi` through private delegates sharing `VaultSessionHolder`. These delegates are an internal implementation detail, not independent application layers.
- Recommended treatment: keep `VaultManagerEngine` and `VaultSecurityApi` as the security port/adapter boundary; do not expose delegate classes or create additional vault repositories.

## Recommended staged target

1. Keep `SmartManagerRepository` as the compatibility façade temporarily, but stop adding feature behavior to it.
2. Introduce explicit use-case objects for new UI operations and migrate high-risk domains first: vault, cloud sync, duplicate cleanup, and storage import/trash.
3. Move WorkManager enqueue methods and retry/orchestration out of the repository into use cases/coordinators.
4. Mark `MainViewModelCompat` and `SmartManagerRepositoryCompat` as deprecated compatibility APIs with migration documentation, then remove them only after UI and tests use the canonical use-case contracts.
5. Add an architecture guard that forbids new production references to compatibility symbols and direct WorkManager calls from repository classes.
