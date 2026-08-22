# Android Instrumentation Failure Root-Cause Analysis

**Repository:** [The-VVF-Search](https://github.com/praisesai-hub/The-VVF-Search)  
**Analyzed PR:** [#47](https://github.com/praisesai-hub/The-VVF-Search/pull/47)  
**Analyzed head:** `dfee303fec84394dd60f2f0bed953d1365b447a8`  
**Android workflow:** [Run 32535993914](https://github.com/praisesai-hub/The-VVF-Search/actions/runs/32535993914)  
**Artifact:** `android-test-reports`, Android 15 emulator  
**Analysis status:** Root-cause triage, no production fix applied

## Executive conclusion

The current CI artifact proves eight instrumentation failures, but it does not prove one common root cause. The failures divide into four independent areas: recycle-bin physical-operation state, app-private scanner discovery, OCR preview semantics, and resource-driven Compose text/visibility. The evidence supports a different diagnosis for each area.

The **Fake DAO fix in `eefb0ae` is logically correct but not fully verified in CI**. It correctly expanded lookup coverage to recycle-bin rows and made `updateFile` and `deleteFileById` maintain mutually exclusive active/unhashed/recycle collections. However, the failing test reaches `PhysicalStorageManager.moveToTrash` before the DAO update assertion, and the test injects a fake `FileDao` while the repository still uses the real Room-backed `FileOperationStore`. A stale open operation can therefore survive outside the Fake DAO and reuse the deterministic operation ID `file-MOVE_TO_TRASH-30`. The fix cannot be declared complete until the operation-store dependency is isolated or explicitly reset and the test passes in a clean repeatable run.

> **Release verdict: NO-GO.** The Android build and instrumentation jobs are terminal failures. Room encryption and clean release artifact gates remain unresolved.

## Current CI evidence

| Check or artifact | Result | Interpretation |
|---|---|---|
| `Analyze (java-kotlin)` | SUCCESS | Static analysis gate passed for the PR head |
| `CodeQL` | SUCCESS | CodeQL passed for the PR head |
| `Build & Test Android App` | FAILURE | Terminal Android workflow failure |
| `Run Instrumented Android Tests` | FAILURE | Terminal instrumentation failure |
| Dependency submission | SKIPPED | Not a green dependency-submission result for this pull-request event |
| JUnit artifact | Present and non-expired | Contains eight current instrumentation failures |

The exact failing tests in the artifact are listed below.

| # | Test class | Test method | Failure category |
|---:|---|---|---|
| 1 | `SmartManagerRepositoryInstrumentedTest` | `recycleBinOperations_preservePhysicalDataAndDaoIntegrity` | Physical operation / persisted operation state / fixture isolation |
| 2 | `StorageScannerInstrumentedTest` | `scanDeviceStorage_discoversAppPrivateFilesAndComputesRealHashes` | App-private scan discovery or fixture visibility |
| 3 | `StorageScannerInstrumentedTest` | `scanDeviceStorageFlow_emitsFullAndFinalBatches` | Same scan-discovery path, with batch contract impact |
| 4 | `StorageScannerInstrumentedTest` | `scanDeviceStorage_skipsHiddenAndroidAndEmptyFiles` | App-private scan discovery/filtering |
| 5 | `OcrOverlayImageInstrumentedTest` | `readableImage_rendersPreviewAndOverlayBlocks` | Content-description contract mismatch, with decode still requiring isolation |
| 6 | `AiDuplicatesScreenInstrumentedTest` | `duplicateCleanerRendersProvidedGroupsAndSections` | Hard-coded assertion does not match resource output |
| 7 | `AiDuplicatesScreenInstrumentedTest` | `duplicateCleanerEmptyStateRemainsFailClosed` | Hard-coded assertion does not match resource output; additional UI contract checks may remain |
| 8 | `DashboardScreenInstrumentedTest` | `dashboardRendersHealthRoadmapQuickActionsAndCategoryNavigation` | Hard-coded assertion does not match current resource-rendered text |

## 1. Recycle-bin and Fake DAO failure

### Observed evidence

The test creates a real source file in `context.cacheDir`, inserts an ordinary row into `fakeDao.activeFiles`, calls `repository.moveToRecycleBin`, and expects the source to move physically and the updated row to be present in `updatedSingleFiles`. The terminal failure occurs earlier:

```text
PhysicalStorageManager: operation=PHYSICAL_STORAGE_TRASH reason=FileNotFoundException
FileNotFoundException: source unavailable
SmartManagerRepository.kt:337
UserSafeException: The file operation could not be completed.
```

The failure is therefore not an assertion that the Fake DAO placed the row in the wrong collection. The physical move failed before `dao.updateFile` was reached.

### Fake DAO change in `eefb0ae`

The commit changed four relevant behaviors in `SmartManagerRepositoryInstrumentedTest.TestFileDao`:

| Fake DAO behavior | Before `eefb0ae` | After `eefb0ae` | Assessment |
|---|---|---|---|
| `getFileById` | Active + unhashed only | Active + unhashed + recycle-bin | Correct for repository lookups after trashing |
| `getFileByName` | Active + unhashed only | Active + unhashed + recycle-bin | Correct for recycle-bin visibility |
| `updateFile` | Recorded update only | Removes old row from all lists, then inserts into active or recycle list based on `isRecycleBin` | Correct state-transition semantics |
| `deleteFileById` | Recorded deletion only | Removes row from all lists and records ID | Correct deletion semantics |

The `updateFile` implementation is particularly important because a row must not remain simultaneously in `activeFiles` and `recycleBinFiles` after a transition:

```kotlin
activeFiles.removeAll { it.id == file.id }
unhashedFiles.removeAll { it.id == file.id }
recycleBinFiles.removeAll { it.id == file.id }
if (file.isRecycleBin) recycleBinFiles += file else activeFiles += file
updatedSingleFiles += file
```

This is a valid Fake DAO correction. It is not, however, sufficient to prove the complete repository operation.

### Root cause assessment

**Primary root cause supported by evidence: test dependency isolation is incomplete, with a likely stale real `FileOperationStore` operation.** The repository constructor accepts the fake `FileDao`, but its `fileOperationStore` property is lazy and defaults to `AppDatabase.getDatabase(context).fileOperationStore()` unless `fileOperationStoreOverride` is supplied. The test injects only `fakeDao`; it does not inject or reset a `FileOperationStore`.

The operation ID is deterministic:

```text
file-MOVE_TO_TRASH-30
```

`FileOperationStore.findOpenOperation` returns an existing `PREPARED` or `PHYSICAL_COMPLETED` operation for the same `fileId` and operation type. The repository calls `recoverPendingFileOperations()` before starting a new move, then reuses any open operation. A previous failed run can therefore retain an operation whose `sourcePath` points to a deleted temporary file. The current run creates a new temporary file, but the repository can still attempt the old path and produce `source unavailable`.

This hypothesis is consistent with all observed facts: the source is created in the test, the exception is thrown by `PhysicalStorageManager` before the Fake DAO transition, and the operation store is real and persisted independently of the fake DAO. The artifact does not expose the operation row itself, so the stale-row explanation is **strongly indicated but not yet independently proven**.

### What is verified and what is not

| Claim | Verdict |
|---|---|
| `eefb0ae` modified the Fake DAO state semantics | **Verified by Git diff** |
| The Fake DAO now handles active-to-recycle and recycle-to-active transitions correctly in isolation | **Verified by source inspection** |
| The current failing test proves the Fake DAO fix is wrong | **Not supported** |
| The Fake DAO fix is fully verified in CI | **False**; the test is terminally failing |
| The repository test is isolated from persisted operation state | **False**; real `FileOperationStore` remains injected by default |
| Stale `FileOperationStore` state is the exact current cause | **Probable, not yet proven from a row dump** |

### Minimal verification and remediation

The next test-only verification should inject a deterministic in-memory `FileOperationStore` fake, or clear the operation row for `file-MOVE_TO_TRASH-30` in setup and teardown. The fake should implement the same lifecycle operations as the production store and record transitions. Then run the test repeatedly in isolation and in the full suite.

A passing result must verify all of the following: the source exists before the move; the operation begins with the current source path; the physical trash file exists after the move; the Fake DAO contains exactly one recycle-bin row and no active duplicate; restore uses the current recycle path; the original path exists after restore; the row is active and has a blank `originalPath`; and no open operation remains after commit.

Do not weaken the assertion to accept `UserSafeException`. The current exception is evidence of an incomplete physical-operation fixture or production operation-state issue.

## 2. `scanDeviceStorage_discoversAppPrivateFilesAndComputesRealHashes`

### Observed evidence

The test creates a PDF and PNG beneath a unique directory under `context.cacheDir`, calls `scanDeviceStorage(computeHashes = true)`, and expects both files. The failure is an assertion failure. The artifact's companion test reports a total discovered count of only one and the skip/filter test receives an empty set for the expected test-root files.

### Relevant production path

`StorageScanner.scanDeviceStorage` scans MediaStore first and then scans:

```kotlin
context.getExternalFilesDir(null)
context.filesDir
context.cacheDir
```

The recursive scanner skips hidden names, skips zero-length files, skips a direct child directory named `Android`, rejects directory symlink/canonical mismatches, and computes hashes only after discovery. Therefore a failure to find the test files is upstream of MD5/SHA-256 and image dHash computation.

### Root cause assessment

**Primary root cause: app-private test-root discovery is failing in the Android 15 runtime.** The exact artifact proves the files were not returned; it does not yet distinguish among a fixture visibility problem, a scanner traversal problem, or an exception swallowed by the broad scanner catch block.

The likely branches are:

| Branch | Evidence status | Why it matters |
|---|---|---|
| Test fixture created in a directory not visible to the scanner instance | Plausible | The expected paths are under `cacheDir`, but the scanner must be instrumented to log the actual roots and `listFiles()` result without exposing full paths in release logs |
| Canonical-path guard rejects a runtime directory | Plausible | The scanner calls `file.canonicalFile` and compares it to `file.absoluteFile.path`; Android runtime path aliases can make this stricter than intended |
| `listFiles()` returns null or traversal throws | Plausible | The scanner catches `Exception` around the complete app-private scan and only logs a generic sanitized message, so the artifact does not expose the exact branch |
| Hidden/empty filtering causes the empty result | Unlikely for the first test | The PDF and PNG are non-hidden and non-empty |
| Hash computation causes discovery loss | Unsupported | Items are emitted only after hash computation, but quiet hash functions should return empty strings rather than remove the item; this requires targeted logging/test isolation |

The three scanner failures share the same app-private scan path, so they may have a common discovery failure. It is not yet safe to say they share one root cause because the flow-batching assertion adds a separate contract dimension.

### Minimal next test

Add a test-only diagnostic seam or structured counters for scanned roots, directories visited, directories rejected by canonical checks, files skipped by reason, and batches emitted. Avoid logging absolute paths. Run the three tests separately and together. First prove that the scanner discovers the fixture with `computeHashes = false`; only then re-enable hashing. This isolates traversal from hashing.

## 3. `scanDeviceStorageFlow_emitsFullAndFinalBatches`

### Observed evidence

The fixture creates 105 non-empty files below the same `cacheDir` test root. The test expects at least one batch of exactly 100 and all 105 paths in the flattened result. The current scanner emits full batches at size 100 and flushes a final partial batch, so the batching algorithm itself is structurally correct.

### Root cause assessment

**Primary root cause supported by current evidence: the scanner discovers too few or zero app-private fixture files, so the batch contract is never reached with 105 items.** This is a dependent symptom of the scanner discovery failure, not proof that the `BATCH_SIZE` implementation is wrong.

A second, separate issue should still be verified: `scanDeviceStorageFlow` uses `channelFlow`, while `scanDeviceStorage` executes the callback inside `withContext(Dispatchers.IO)`. Cancellation and channel backpressure need a targeted test, but the present artifact does not implicate them. The current failure should not be fixed by changing batch size or by weakening the assertion.

## 4. `scanDeviceStorage_skipsHiddenAndroidAndEmptyFiles`

### Observed evidence

The fixture creates a hidden file, a nested non-hidden file, an empty file, a visible file, and an `Android` directory directly under `cacheDir`. It expects exactly the visible and nested private files under the test root and excludes the Android file. The artifact reports:

```text
expected: [visible.txt, nested/private.txt]
was: []
```

The scanner's intended filtering logic would correctly skip `.hidden.txt`, `empty.txt`, and the direct `Android` directory while retaining `visible.txt` and `nested/private.txt` if the test root is traversed.

### Root cause assessment

**Primary root cause: traversal/discovery failure before per-file filtering.** The empty expected subset is stronger evidence against a simple hidden-file or empty-file filter bug. If filtering alone were wrong, at least the visible file would normally be returned. The scanner needs instrumentation around root enumeration and recursive entry to prove whether the unique test root is seen and whether the canonical-path guard rejects it.

The `Android` exclusion rule itself is narrow: it skips a directory named `Android` only at recursion depth zero for each scanned app root. That behavior is consistent with the test's intent, but it should be documented as an app-root policy rather than treated as a universal path rule.

## 5. `readableImage_rendersPreviewAndOverlayBlocks`

### Observed evidence

The test creates a readable temporary PNG and asserts:

```kotlin
onNodeWithContentDescription("OCR Image Preview").assertIsDisplayed()
```

The current composable uses:

```kotlin
contentDescription = stringResource(R.string.ocr_image_preview)
```

The current English resource is:

```xml
<string name="ocr_image_preview">OCR image preview</string>
```

The capitalization differs: the test expects `OCR Image Preview`, while production renders `OCR image preview`. Compose content-description matching is exact by default.

### Root cause assessment

**Primary root cause: stale test assertion caused by a resource/content-description contract mismatch.** The source does not contain the exact string expected by the test. This is not evidence that bitmap decoding or overlay drawing is broken.

There is a secondary fixture concern: the test writes the PNG using `imageFile.outputStream()` without an explicit `use` block before composing. The stream is likely closed by the bitmap compression path only if the underlying implementation closes it, which is not a contract the test should rely on. The test should use `outputStream().use { bitmap.compress(...) }` and wait for Compose idle before asserting. However, the decisive mismatch visible in source is the content description.

### Minimal remediation

Use `stringResource(R.string.ocr_image_preview)` in the test, or deliberately change the resource if the capitalized accessibility label is the product contract. The test should also close the output stream explicitly. Do not alter production rendering solely to satisfy an outdated hard-coded English assertion.

## 6. `duplicateCleanerRendersProvidedGroupsAndSections`

### Observed evidence

The test searches for:

```text
Level 1-2: Exact Hash Duplicates (1 sets)
Level 3-4: Visual & Semantic AI Duplicates (1 sets)
```

The current resources and composable render:

```xml
Level 1–2: Exact Hash Duplicates (%1$d sets)
Level 3–4: Visual & Semantic AI Duplicates (%1$d sets)
```

The production strings use an en dash `–`, while the tests use a hyphen-minus `-`. The composable obtains both headers from `stringResource`, so the hard-coded test text cannot match exactly.

### Root cause assessment

**Primary root cause: stale hard-coded assertions, specifically Unicode punctuation mismatch.** The section data and group titles are supplied to the composable, and the failure occurs while scrolling to the header text. This is not evidence that duplicate grouping is empty or that the selected file state is wrong.

The test has an additional semantic risk: it uses `onNode(hasScrollToIndexAction())` without a stable test tag, which can become ambiguous when the screen contains multiple lazy containers. A stable list tag would make the test more deterministic, but that is secondary to the confirmed string mismatch.

### Minimal remediation

Assert using `stringResource`-equivalent constants or use the exact en-dash text. Prefer a stable semantics/test tag for the duplicate list and keep the test assertion tied to the resource contract. Do not replace the production en dash with a hyphen merely to preserve a stale test.

## 7. `duplicateCleanerEmptyStateRemainsFailClosed`

### Observed evidence

The test expects the same hyphenated headers:

```text
Level 1-2: Exact Hash Duplicates (0 sets)
Level 3-4: Visual & Semantic AI Duplicates (0 sets)
```

The current resources use en dashes. The exact-hash empty-state resource is present and matches the test text, but the test fails first while trying to scroll to the mismatched header. The visual empty-state resource and semantic availability are separately resource- and runtime-driven.

### Root cause assessment

**Primary root cause: the first failure is the same stale Unicode punctuation assertion as test 6.** The artifact does not prove that fail-closed behavior is broken. The production code explicitly renders an exact-hash empty state when the list is empty. It renders the semantic “Coming Soon” state when the model is unavailable, so the test's unconditional expectation of an enabled semantic search input is a separate contract risk that must be checked after the header assertion is corrected.

### Minimal remediation

Correct the header assertion to use the production resource value, then assert the runtime-appropriate semantic state. If the model is intentionally not bundled in the test APK, the test should expect the disabled/coming-soon state rather than require an enabled search input. If an enabled input is the contract, inject an explicit semantic model fixture and assert that fixture is loaded.

## 8. `dashboardRendersHealthRoadmapQuickActionsAndCategoryNavigation`

### Observed evidence

The test expects:

```text
System Health: 94% Excellent
```

The current resource is:

```xml
<string name="system_health">System Health: 94%% Excellent</string>
```

Android string resources use `%` formatting rules. When resolved, `%%` represents a literal percent sign, so the intended displayed value is `System Health: 94% Excellent`. The test failure says that exact text is not displayed, which means the failure is not explained by the visible source string alone and needs one additional runtime check.

### Root cause assessment

**Primary root cause classification: UI text is resource-driven, but the artifact does not yet distinguish formatting/runtime visibility from a stale assertion.** Unlike the duplicate and OCR cases, the test's expected text appears semantically identical to the resolved resource. The dashboard source renders `stringResource(R.string.system_health)` in the health card, and the test confirms that the health card itself is displayed. Therefore the remaining branches are:

| Branch | Likelihood | Required proof |
|---|---|---|
| The rendered text differs because `%%` is not being resolved as expected in this call path | Possible | Dump the actual semantics tree text or assert the resource-resolved value in the same test |
| The text exists but is outside the current semantics/visibility tree | Possible | Query by resource-derived text after scrolling or inspect the health-card subtree |
| The runtime resource configuration differs from the inspected default `values/strings.xml` | Possible | Record locale/configuration and resolved resource value on the emulator |
| Test state or asynchronous composition hides/replaces the line | Possible but less likely | Wait for idle and inspect the health-card subtree |
| Production dashboard logic is wrong | Not yet supported | Requires actual semantics evidence showing an incorrect rendered value |

This failure must not be “fixed” by deleting the health assertion or hard-coding another string until the actual semantics text is captured.

## Independent Fake DAO verification verdict

### What the fix correctly addresses

The `eefb0ae` diff fixes the previously stale fake state model. Repository lookups can now find recycle-bin rows, and `updateFile` correctly performs a state transition instead of merely recording a callback. `deleteFileById` now removes the row from all fake collections, preventing a deleted row from remaining visible through another query path.

### What the fix does not address

The Fake DAO does not own the persisted file-operation journal. `SmartManagerRepository` still obtains a real Room-backed `FileOperationStore` through its lazy default because the test does not provide `fileOperationStoreOverride`. The physical move can fail before `TestFileDao.updateFile` executes. Consequently, a green Fake DAO state transition cannot be inferred from this red test, and a red test cannot by itself refute the Fake DAO state transition code.

### Required decisive verification

The Fake DAO fix should be considered **partially verified by source inspection, not verified by CI** until the following test is added or the existing test is isolated:

1. Inject a fake `FileOperationStore` into the repository, or explicitly clear the deterministic operation row before each test.
2. Assert that `prepareFileOperation` receives the current temporary source path.
3. Assert that the fake operation store transitions through `PREPARED`, `PHYSICAL_COMPLETED`, and `COMMITTED`.
4. Assert that `TestFileDao.updateFile` is called exactly once with `isRecycleBin = true` and the generated trash path.
5. Assert that restore calls `updateFile` exactly once with `isRecycleBin = false`, the original path, and blank `originalPath`.
6. Assert that the operation store has no open row after each committed operation.
7. Run the test alone, twice in the same emulator installation, and in the full instrumentation suite.

## Prioritized next actions

| Priority | Action | Why |
|---:|---|---|
| P0 | Isolate or reset `FileOperationStore` in the recycle-bin instrumentation fixture | Removes cross-test persisted state and directly verifies the Fake DAO fix |
| P1 | Add scanner diagnostics/counters and run the three scanner tests with hashes disabled first | Separates root traversal from filtering, hashing, and batching |
| P1 | Correct OCR and duplicate tests to use resource-derived strings and explicit stream closure | Removes confirmed stale assertions without weakening production contracts |
| P1 | Capture the dashboard health-card semantics tree and resolved resource value | Determines whether the dashboard failure is formatting, visibility, configuration, or stale assertion |
| P2 | Re-run the complete Android workflow and retain the new JUnit artifact | Required terminal evidence after each minimal change |
| P2 | Do not proceed to release PASS, Room closure, or clean AAB claims while Android checks remain red | Preserves the release gate discipline |

## Final verdict

The eight failures are real current failures, but they are not one defect. The Fake DAO fix is **correct in its local state-transition logic and unverified as an end-to-end repository fixture**. The strongest currently supported root-cause classifications are:

| Failure area | Current classification |
|---|---|
| Recycle-bin test | Real-operation fixture isolation defect or stale persisted operation; Fake DAO fix not disproven |
| Three StorageScanner tests | App-private discovery failure cluster, exact branch not yet isolated |
| OCR preview | Confirmed stale content-description assertion, with fixture stream closure worth hardening |
| Two duplicate-cleaner tests | Confirmed stale hard-coded Unicode punctuation assertions; semantic availability requires a second check |
| Dashboard | Resource-driven rendering mismatch not yet fully isolated; actual semantics value required |

> **No production-ready claim is justified. Android verification remains red, and the release remains NO-GO.**

## References

[1]: https://github.com/praisesai-hub/The-VVF-Search/pull/47 "PR #47"
[2]: https://github.com/praisesai-hub/The-VVF-Search/actions/runs/32535993914 "Android CI/CD run 32535993914"
[3]: https://github.com/praisesai-hub/The-VVF-Search/commit/eefb0aeee16fcf835c960bbd713bf2e35dfc4115 "eefb0ae Fake DAO fix commit"
[4]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/androidTest/java/com/example/data/SmartManagerRepositoryInstrumentedTest.kt "Current SmartManagerRepository instrumentation test"
[5]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/main/java/com/example/data/SmartManagerRepository.kt "Current SmartManagerRepository implementation"
[6]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/main/java/com/example/storage/StorageScanner.kt "Current StorageScanner implementation"
[7]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/main/res/values/strings.xml "Current English resource strings"
[8]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/main/java/com/example/ui/components/OcrOverlayImage.kt "Current OCR overlay composable"
[9]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/main/java/com/example/ui/screens/AiDuplicatesScreen.kt "Current duplicate-cleaner composable"
[10]: https://github.com/praisesai-hub/The-VVF-Search/blob/dfee303fec84394dd60f2f0bed953d1365b447a8/app/src/main/java/com/example/data/FileOperationStore.kt "Current persisted file-operation store"
