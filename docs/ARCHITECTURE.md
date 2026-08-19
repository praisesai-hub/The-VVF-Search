# Application architecture

The production target is a strict **UI → Domain/Use Cases → Repositories → Data Sources** flow.

```text
Compose UI
   ↓
MainViewModel (presentation state only)
   ↓
Domain use cases / coordinators
   ↓
Repositories (data and domain persistence boundaries)
   ↓
Room DAO, secure storage, filesystem, WorkManager workers
```

## Canonical boundaries

`MainViewModel` owns presentation state, event collection, and lifecycle-aware flow composition. It must not contain encryption, filesystem mutation, retry policy, or WorkManager request construction.

Domain use cases and coordinators own cross-repository workflows. `WorkCoordinator` is the canonical owner of WorkManager constraints, unique-work names, backoff, and scheduling. New background-work orchestration must be added there, not to `SmartManagerRepository`.

Repositories own persistence and data-source coordination. `SmartManagerRepository` remains the current application façade during the migration, but it is now a compatibility façade for legacy callers. New feature behavior must be introduced in a domain use case or a focused repository instead of expanding this class.

Vault security has one intentional port/adapter boundary. `VaultManagerEngine` is the focused security primitive for PIN envelopes and biometric wrapping. `VaultSecurityApi` is the authenticated-session port implemented internally by the PIN, biometric, and session delegates. `VaultRepository` owns vault file/DAO orchestration. The delegates are private implementation details, not additional application layers.

## Compatibility policy

`MainViewModelCompat.kt` and `SmartManagerRepositoryCompat.kt` are isolated migration surfaces. They must not be copied or extended for new features. The repository compatibility extensions are explicitly deprecated, and the architecture guard rejects new compatibility files, direct compatibility references, and direct WorkManager orchestration inside data classes.

The migration sequence is:

1. Add a focused domain use case or coordinator for the new workflow.
2. Move new UI call sites to that canonical contract.
3. Keep a small deprecated adapter only for existing tests or legacy call sites.
4. Remove the adapter after all call sites and tests have migrated.

The architecture boundary check is run in Android CI through `scripts/check_architecture_boundaries.py`.
