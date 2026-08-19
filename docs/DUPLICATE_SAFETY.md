# Duplicate Detection Safety Policy

Duplicate detection has two different meanings: **cryptographic identity** and **similarity**. They must never share the same destructive default.

| Detection class | Evidence | Destructive automatic cleanup |
|---|---|---|
| Exact duplicate | Non-empty matching SHA-256 content hash | Allowed for redundant members of an exact-hash group |
| Visual candidate | dHash/Hamming-distance similarity at the configured threshold | Never automatic; review-only |
| Video candidate | Keyframe perceptual similarity | Never automatic; review-only |
| Semantic candidate | Embedding cosine similarity | Never automatic; review-only |
| Document candidate | Size + first 4096 bytes + last 4096 bytes, stored as `documentCandidateFingerprint` | Never automatic; review-only |

A document boundary fingerprint is intentionally a fast **candidate signal**, not a cryptographic content identity: two files can have identical size, head, and tail bytes while differing in the middle. Only a non-empty matching `md5Hash` value—despite the historical field name, this stores the full SHA-256 content hash—establishes exact document identity and may enter the exact-duplicate cleanup path.

`MainViewModelCompat.autoSelectExtraDuplicates()` now selects only redundant members of exact SHA-256 groups. The duplicate screen renders all non-exact groups as review-only cards without destructive checkboxes. `DuplicateManager` independently validates every requested ID against the DAO’s active exact-hash duplicate set, so a caller cannot bypass the UI safety boundary by passing a visual, video, semantic, or document candidate directly.

The background `DuplicateCleanupWorker` already groups only non-empty matching content hashes and remains restricted to that exact path. The repository architecture guard checks that the manager performs exact-hash validation, the compatibility surface does not auto-select similarity groups, and every candidate group is marked review-only in the UI.

A future candidate-deletion workflow must introduce an explicit confirmation use case with a fresh candidate snapshot, a visible explanation of uncertainty, and a second user action. It must not reuse the exact-duplicate cleanup method.
