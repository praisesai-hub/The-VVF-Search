# Video Duplicate Detection Evidence

A single keyframe is not sufficient evidence that two videos are duplicates. The video detector now uses a versioned fingerprint with four temporal samples at approximately 10%, 35%, 60%, and 85% of the media duration.

| Evidence | Role |
|---|---|
| Four temporal dHashes | Requires all sampled positions to satisfy the configured Hamming threshold; a shared opening frame alone cannot match. |
| Duration | Must be within 5% or one second, whichever is more permissive. |
| Resolution and aspect ratio | Requires compatible dimensions and an aspect-ratio difference no greater than 0.02. |
| Optional audio-track signature | Uses available audio-presence, MIME, and bitrate metadata; differing non-empty signatures reject a similarity match. |
| Chunk hash | Hashes first, middle, and last 64 KiB plus file length for a stronger content signal. It can shortcut temporal matching only when metadata is compatible. |
| Full SHA-256 | The existing complete content hash remains cryptographic evidence and is definitive. |

Fingerprint data is persisted in `FileItemEntity` and migrated through Room `MIGRATION_6_7`. Local files, MediaStore content URIs, and SAF tree entries use the same evidence fields. Older rows with only a single legacy `visualSimilarityHash` are not treated as valid video fingerprints and are safely excluded until rescanned.

Video groups remain similarity candidates and are not eligible for automatic destructive cleanup. Exact cryptographic cleanup is governed separately by the duplicate-safety policy in `docs/DUPLICATE_SAFETY.md`.
