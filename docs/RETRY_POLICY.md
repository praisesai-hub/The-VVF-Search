# Classified Retry Policy

The application does not retry arbitrary exceptions. Every retry surface must identify an operation family and pass failures through `com.example.domain.retry.RetryPolicy`.

## Classification

| Operation family | Retryable failures | Terminal failures |
|---|---|---|
| `DATABASE_READ` / `DATABASE_WRITE` | SQLite database locks | Constraint violations, invalid input, permissions, ordinary SQLite failures |
| `FILE_STORAGE` | Explicitly temporary I/O such as resource-busy, try-again, or connection-reset errors | Missing files, permission denial, disk full, ordinary I/O failures |
| `INDEXING` | Temporary I/O | Database constraints, permissions, disk full, missing sources, ordinary scan failures |
| `CLOUD_TRANSFER` | Timeouts, DNS/connectivity failures, temporary I/O | Authentication/authorization failures, unsupported providers, missing files, invalid requests, ordinary I/O failures |
| `DUPLICATE_CLEANUP` / `CACHE_CLEANUP` | Temporary I/O | Permission denial, disk full, missing files, ordinary failures |

The classifier unwraps nested causes before making a decision. It uses stable reason codes such as `DATABASE_LOCKED`, `TIMEOUT`, `NETWORK_UNAVAILABLE`, `TEMPORARY_IO`, `PERMISSION_DENIED`, and `NON_TRANSIENT_FAILURE`; raw exception text is not used as a user-facing message.

## Attempt and backoff policy

The default budget is **three total executions**: the initial execution plus at most two retries. The in-process delay is 100 ms followed by 200 ms. WorkManager applies domain-specific exponential backoff at the job boundary: cloud transfer starts at 10 seconds, background indexing at 15 seconds, and local cleanup jobs at 30 seconds. A terminal failure returns immediately without consuming the retry budget.

The architecture guard rejects generic `withRetry { ... }` calls and legacy `runAttemptCount >= 3` thresholds. New retry behavior must add an operation to `RetryOperation`, classify it in `RetryPolicy`, and include a regression test for both retryable and terminal failures.
