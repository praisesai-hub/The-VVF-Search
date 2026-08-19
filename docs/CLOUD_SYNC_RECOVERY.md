# Cloud sync correctness and recovery contract

Cloud operations use a stable local `operationId` and persist the provider’s remote file ID after completion. Google Drive stores the operation ID in private `appProperties`, which allows a retry to discover an already-created remote file instead of creating a duplicate.

Drive filename and property queries are built through the HTTP URL builder and escape apostrophes and backslashes in Drive query values. File-list responses are paginated and the first matching remote ID is used for subsequent media operations; the repository does not treat a filename as a durable remote identity.

Large transfers use Drive resumable upload sessions in 256 KiB chunks. The session URL and committed byte offset are persisted in `cloud_sync` while the current WorkManager lease is active. On retry, the adapter probes the session with `Content-Range: bytes */<total>`, adopts the server’s committed offset, and continues from that boundary. A session URI is retained for recovery and is invalidated by provider failure or expiry; an idempotency lookup remains the final duplicate guard.

The Room 8→9 migration adds `remoteFileId`, `resumableSessionUri`, and `resumableBytesCommitted`. Progress updates are guarded by `operationId`, `status = 'UPLOADING'`, and `leaseOwner`, preventing an expired worker from overwriting a newer claim. Success and failure results also persist the final progress evidence before the lease is completed or requeued.

The JVM test suite covers escaped operation-ID lookup, persisted-session offset recovery, remote-ID propagation through the worker, and migration defaults. Android instrumented tests continue to exercise worker state transitions against the device Room implementation.

## References

Google Drive’s official [search guide](https://developers.google.com/workspace/drive/api/guides/search-files) documents escaping apostrophes and backslashes in `q` values and searching private `appProperties`. The official [upload guide](https://developers.google.com/workspace/drive/api/guides/manage-uploads) documents resumable session URLs, 256 KiB chunk boundaries, `Content-Range`, and interrupted-upload status recovery. The [files.list reference](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list) documents pagination and stable file IDs.
