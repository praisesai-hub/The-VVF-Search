# Destructive file-operation recovery

Destructive file operations are represented by a durable `file_operations` intent before touching physical storage. The operation ID is stable per file and operation type, and recycle-bin destinations derived from that ID are deterministic. This prevents a retry from creating a second trash copy after a process death.

The state transitions are `PREPARED → PHYSICAL_COMPLETED → COMMITTED`. The physical step is performed first. The Room file record is updated only after physical success, and the operation intent is removed only after the metadata commit. If the process dies after physical completion, recovery recognizes the persisted `PHYSICAL_COMPLETED` state or the deterministic destination and finishes the Room transition without repeating the destructive action.

Permanent deletion follows the same protocol. If the source is already absent during recovery, the physical phase is treated as complete and the Room record is removed. A failed physical operation remains recoverable and is not silently treated as a successful database deletion. Recycle-bin emptying processes items individually so a low-storage or permission failure does not erase the remaining recovery queue.

Recovery is invoked before new destructive operations and is exposed through `recoverPendingFileOperations()` for WorkManager/process-start integration. User-facing failures remain generic; internal operation IDs and physical paths remain diagnostic-only.
