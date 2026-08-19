# WorkManager Idempotency and Crash Recovery

WorkManager’s unique-work policy prevents duplicate scheduling, but it does not by itself prevent a second worker from repeating an upload after a process crash. Cloud synchronization therefore persists operation state in `cloud_sync` and uses an atomic lease protocol.

## Durable operation state

Every cloud item has a non-empty `operationId`. The operation ID remains unchanged across retries and process restarts. The row also stores the current lease owner, lease expiry, attempt count, first start time, latest heartbeat, completion time, and a sanitized error reason code.

| State field | Purpose |
|---|---|
| `operationId` | Stable logical operation identity and provider idempotency key |
| `leaseOwner` | WorkManager worker UUID currently allowed to mutate the operation |
| `leaseExpiresAtMs` | Crash-recovery deadline for abandoned claims |
| `attemptCount` | Durable execution count, independent of WorkManager’s in-memory process |
| `startedAtMs` | First execution timestamp |
| `heartbeatAtMs` | Latest liveness signal while an upload is active |
| `completedAtMs` | Terminal success or permanent-failure timestamp |
| `lastErrorCode` | Sanitized diagnostic reason, never raw exception text |

## Claim protocol

At startup, `CloudSyncWorker` releases expired or legacy zero-expiry `UPLOADING` rows back to `QUEUED`. It then claims each operation using an atomic update guarded by `operationId`, status, and lease expiry. A worker that cannot claim an item skips it rather than overwriting the active owner’s state.

While uploading, the worker refreshes the lease every 30 seconds. Completion and failure transitions require both the stable operation ID and the current lease owner, so a stale worker cannot mark a newer attempt as complete. Retryable failures return the item to `QUEUED`; terminal failures become `FAILED` and receive a completion timestamp.

Google Drive uploads carry the stable operation ID in Drive `appProperties`. Before creating a new resumable upload, the adapter searches for an existing remote object with the same operation property. If found, the retry is treated as already complete instead of creating a duplicate remote file.

The schema upgrade is `MIGRATION_5_6`, which backfills legacy rows as `legacy-{id}` operation IDs and creates a unique operation index. The architecture guard requires the cloud worker to retain the operation store, claim, heartbeat, and compare-and-set transition primitives.
