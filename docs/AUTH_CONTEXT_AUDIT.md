# Authentication bounded-context audit

## Identity

`FirebaseAuthManager` is the identity boundary. It owns Firebase `FirebaseUser` state, Google Credential Manager sign-in, Firebase credential exchange, Microsoft OAuth provider sign-in, and Firebase sign-out. Credential Manager is an identity sign-in mechanism here; it does not authorize Drive file operations.

## DriveAuthorization

`GoogleAuthManager` owns a separate stored Google OAuth session with access/refresh tokens, email, display name, validation, restore, and clear operations. `GoogleAuthManagerFactory` owns the secure-store-backed singleton and legacy credential migration. This is a Drive authorization/session context, not Firebase identity.

The current `GoogleAuthState.SignedIn` exposes an access token directly and `GoogleDriveProviderAdapter` consumes `GoogleAuthManager` directly. This couples a provider adapter to a concrete credential store and makes token ownership visible to the transfer layer.

## CloudTransfer

`CloudSyncPolicy` owns build provisioning and explicit user opt-in. `CloudSyncWorker` currently mixes consent policy, plugin-to-provider mapping, DAO state transitions, GoogleAuthManager factory lookup, CloudSyncEngine construction, retry decisions, and transfer status persistence. `CloudSyncEngine` is mostly provider-agnostic but directly constructs `GoogleDriveProviderAdapter(authManager)`, which is the strongest coupling seam.

`CloudProviderAdapter` and `CloudSyncResult` are already useful transfer contracts. The next boundary should inject a provider adapter registry/factory or a `DriveAuthorization` port into CloudSyncEngine, so CloudTransfer never knows Firebase identity or token storage details.

## Telemetry

`CrashReportingPolicy` is already a narrow consent-only telemetry policy. `VVFApplication.initCrashlytics()` initializes FirebaseApp and enables Crashlytics only after release-build plus explicit consent. This should remain isolated from both Firebase identity state and Drive authorization state.

## Target boundaries

| Context | Owns | Must not own |
|---|---|---|
| Identity | Firebase user/session, Credential Manager sign-in, identity sign-out | Drive access tokens, cloud file transfer, telemetry consent |
| DriveAuthorization | Drive-scoped OAuth session, token validation/refresh/clear, provider authorization | Firebase user identity semantics, transfer retries, DAO item status |
| CloudTransfer | Provider-agnostic upload/download contracts, queue orchestration, status transitions, retry policy | FirebaseAuth, CredentialManager, raw token storage |
| Telemetry | Crashlytics initialization and consent policy | Identity login state, Drive OAuth state, transfer policy |

## Recommended staged refactor

1. Add `DriveAuthorizationPort` and `DriveAccessToken` so adapters receive a narrow authorization contract rather than `GoogleAuthManager`.
2. Add a `CloudProviderRegistry`/factory to select adapters; keep Google Drive adapter inside the Drive provider adapter boundary.
3. Make `CloudSyncEngine` depend on the provider registry and authorization port, not the concrete token manager.
4. Keep `FirebaseAuthManager` separate and document that Google Credential Manager is used to obtain an identity credential that is exchanged for Firebase identity; it does not grant Drive file access.
5. Keep `CrashReportingPolicy` and Crashlytics bootstrap isolated as Telemetry.
6. Add static architecture checks that reject FirebaseAuth/Crashlytics imports in Drive/CloudTransfer packages and reject GoogleAuthManager imports in Identity code.

## Implemented boundary changes

The Drive adapter now depends on `DriveAuthorizationPort` and receives an authorization header through that port. `CloudProviderRegistry` owns provider selection, while `CloudSyncEngine` owns transfer orchestration without constructing a Google auth manager. `CloudSyncWorker` obtains authorization through `DriveAuthorizationFactory`, so the worker no longer reaches into the Google OAuth storage factory directly.

`GoogleAuthManager` implements the port as the DriveAuthorization adapter, but `GoogleAuthState.SignedIn` no longer exposes the raw access token to UI consumers. Crashlytics initialization now lives behind `TelemetryPort` and `CrashlyticsTelemetry`; `VVFApplication` only invokes the telemetry boundary.
