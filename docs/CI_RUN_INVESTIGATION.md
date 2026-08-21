# CI Run Investigation Notes

## User-provided Actions screenshot

The screenshot is a GitHub mobile Actions list showing **923 workflow runs** across all workflows. Its first visible Android entry is **Android CI/CD #355**, manually run by `praisesai-hub` on `fix/restore-ci-compile`, started today at `00:23` and shown with a neutral/exclamation status icon. This confirms the user is viewing a repository-wide history, not only a single branch or workflow result.

The remaining ordered tiles will be reconciled with GitHub run metadata before assigning causes to any status.

Tiles 3–4 show manual Android CI/CD runs #354, #353, #352, and #351 on the same `fix/restore-ci-compile` branch within approximately 23 minutes. Tile 4 begins to show Android CI/CD #350 with a red failure icon. The neutral/exclamation icons for #351–#355 and the red icon for #350 must be reconciled with API run conclusions; the screenshot alone does not state their failure steps.

Tiles 5–6 show Android CI/CD #350, #349, #348, #347, and #346. #350 through #347 visibly carry red failure icons and are manual runs on the same isolated branch. The runs are separated by only a few minutes, which visually confirms the undesirable repeated full-pipeline dispatch pattern.

Tiles 7–8 continue the same pattern: #345, #344, #343, and #342 are manual `fix/restore-ci-compile` Android CI/CD runs with red failure icons. The list confirms that the red outcomes are not a single isolated failure.

Tiles 9–10 show the same manual branch pattern for Android CI/CD #341, #340, #339, and #338, all with red failure icons. The screenshot therefore records at least thirteen consecutive manually dispatched Android CI/CD runs in this visible time window, most failed before the later neutral/exclamation entries.

Tiles 11–12 show Android CI/CD #337, #336, and #335 with red failure icons, while CodeQL #309 on the same branch has a green success icon. This visually matches the API finding that CodeQL was succeeding while Android CI/CD was failing.

Tile 13 shows Android CI/CD #334 and #333 with red failure icons, followed by another CodeQL green success marker. The complete visible sequence confirms a repeated Android-only manual validation failure pattern rather than a repository-wide GitHub Actions outage.

## Current active validation observation

GitHub run `32421178517` (Android CI/CD #355) remained in progress at the time of collection. Its JVM job had completed with failure, recording `Run Unit Tests`, `Upload JVM Unit Test Coverage Report`, `Run Lint`, and `Run Detekt` as failed steps. The instrumented job was still in progress, so GitHub had not yet made the completed JVM job log available. No cancellation or additional run was issued after this observation.

## Bounded follow-up validation evidence

| Run | Head | Terminal result | JVM gate evidence | Instrumented-job result |
|---|---|---|---|---|
| `32432388146` | `af6d2c0` | Cancelled after the completed JVM build had already failed coverage and Detekt. | Aggregate 49.45%, security 71.55%, data 67.65%, vault 85.63%, cloud 81.31%. | Cancelled as obsolete after the build job reached its terminal failure. |
| `32433776245` | `7a94c10` | Failed: JVM coverage and Detekt. | Aggregate 49.90%, security 76.22%, data 68.46%, vault 89.57%, cloud 84.54%. | Skipped by the new job dependency; no emulator was provisioned. |
| `32434615029` | `c12bc5e` | Failed: JVM coverage and Detekt. | Aggregate 50.01%, security 76.22%, data 68.65%, vault 90.21%, cloud 85.18%. | Skipped by the new job dependency; no emulator was provisioned. |
| `32438766220` | `bccf9bc` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 50.12%, security 76.22%, data 68.64%, vault 90.21%, cloud 85.18%. | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32439552753` | `e402eed` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 50.12%, security 76.22%, data 68.65%, vault 90.21%, cloud 85.18%. | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32440000409` | `8b49184` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 50.37%, security 76.22%, data 69.35%, vault 90.36%, cloud 90.44% (cloud floor passed). | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32440986821` | `1507dd2` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 50.82%, security 76.45%, data 70.56%, vault 90.36%, cloud 90.44% (cloud floor passed). | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32441466581` | `0216834` | Failed: JVM coverage and Detekt. | Failed-step-log verified: aggregate 50.81%, security 76.45%, data 70.56%, vault 90.36%, cloud 90.44% (cloud floor passed); Detekt 138 weighted issues. Artifact download timed out during TLS handshake. | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32442093960` | `7afc16f` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 50.96%, security 80.22%, data 70.60%, vault 90.62%, cloud 90.44% (cloud floor passed). | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32442620986` | `befe6d8` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 50.93%, security 80.22%, data 70.60%, vault 90.62%, cloud 90.44% (cloud floor passed). | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32443202510` | `809c9c4` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 51.54%, security 80.22%, data 72.26%, vault 90.62%, cloud 90.49% (cloud floor passed). | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32444584455` | `550a689` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 51.58%, security 80.73%, data 73.14%, vault 91.08%, cloud 90.49% (cloud floor passed); Detekt 116 weighted issues. | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32445177140` | `39a22e6` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 52.34%, security 80.90%, data 75.21%, vault 91.08%, cloud 90.49% (cloud floor passed); Detekt 116 weighted issues. | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32445711354` | `4b1fc64` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 52.34%, security 80.90%, data 75.18%, vault 91.08%, cloud 90.49% (cloud floor passed); Detekt 102 weighted issues. | Skipped by the successful-build dependency; no emulator was provisioned. |
| `32446189823` | `c9e8ed3` | Failed: architecture-boundary gate and Detekt. | No JVM coverage artifact: the early architecture failure prevented coverage generation; downstream coverage/dependency artifact uploads then reported no files. Detekt independently measured 101 weighted issues. | Skipped by the failed build dependency; no emulator was provisioned. |
| `32447176928` | `05f50d7` | Failed: JVM coverage and Detekt. | Artifact-verified: aggregate 53.05%, security 81.78%, data 76.67%, vault 91.08%, cloud 91.46% (cloud floor passed); Detekt 101 weighted issues. | Skipped by the successful-build dependency; no emulator was provisioned. |

The `Run Instrumented Android Tests` job now declares a successful `Build & Test Android App` dependency. This changes the prior failure mode: a JVM coverage failure prevents emulator allocation rather than requiring a later cancellation. The earlier cancellation used the workflow-run operation because GitHub documents cancellation at run scope rather than as a per-job REST operation.[1]

Run `32438766220` completed **all JVM unit tests successfully** before coverage enforcement failed. Its `jvm-unit-test-coverage` artifact was downloaded and checked locally with `scripts/check_coverage_floor.py`; the latest table row is therefore artifact-derived rather than inferred from a workflow log. The report contained 42,312 covered and 84,422 total instructions for aggregate coverage, and all five configured JVM package floors remained below target.

Run `32439552753` independently reproduced the same gate failures after the Keystore/Vault test batch: 42,304 covered out of 84,404 aggregate instructions, with no package-specific floor increase at the displayed two-decimal precision. The subsequent locally verified commits therefore target other recorded high-miss paths—Cloud queue persistence and generated adapter defaults—rather than claiming an unmeasured improvement.

Run `32440000409` measured the first cloud-gate success: 3,527 of 3,900 cloud-sync instructions covered (**90.44%**) against the 90% floor. It also raised repository/data coverage by 0.70 percentage points and Vault coverage by 0.15 points. Aggregate, Security, Data, Vault, and Detekt gates still failed, so this remains a no-merge result.

Run `32440986821` raised aggregate coverage by 0.45 percentage points and repository/data coverage by 1.21 points. The newly measured FileItem generated-adapter tests removed that adapter from the top eight repository/data misses, while the Microsoft null-user test raised FirebaseAuthManager from 63.25% to 65.09%. Security and Vault remain materially below their 90% and 95% floors, respectively; no green or release-ready claim is made.

Run `32441466581` reproduced the coverage values at the displayed two-decimal precision and measured Detekt at **138 weighted issues**. The coverage artifact endpoint timed out during its signed blob download; the row is intentionally marked log-derived and does not claim an unavailable artifact-level class analysis. The current branch has later unvalidated Security coverage commits, so this run is not evidence for them.

Run `32442093960` verified the Security test seams and Firebase result-branch tests: Security coverage rose from **76.45%** to **80.22%**, FirebaseAuthManager rose to **87.66%**, and AndroidKeystoreCrypto became directly covered at **70.00%**. Vault rose to **90.62%**. These are measurable improvements, but Security remains 9.78 points and Vault 4.38 points below their enforced floors; aggregate and repository/data gates also remain failed, so the branch is not eligible for merge.

Run `32442620986` included duplicate-cleanup and physical-storage source refactors plus a cloud-retry repository test. The run added covered instructions but also increased the aggregate instruction denominator from 84,442 to 84,608, so aggregate coverage rounded down by 0.03 points. Security, data, Vault, and cloud values were unchanged at displayed precision. This is evidence that the batch preserved test execution but did not yet advance the remaining JVM floors; it remains a no-merge result.

Run `32443202510` produced the largest data-layer gain so far: repository/data coverage rose from **70.60%** to **72.26%**, aggregate coverage rose to **51.54%**, and SmartManagerRepository moved from 36.75% to 43.40%. The physical recycle-move test exercised 137 additional SmartManagerRepository instructions, while CloudSyncEngine coverage reached 96.22%. Security and Vault were unchanged, all non-cloud JVM floors remain failed, and the branch remains ineligible for merge.

Run `32444584455` independently verified the locally focused FileDao/StorageScanner refactors plus the SecureKeyValueStore and VaultManagerEngine coverage batch. It raised aggregate coverage to **51.58%**, Security to **80.73%**, data to **73.14%**, and Vault to **91.08%**; Cloud remained passing at **90.49%**. Detekt fell to **116 weighted issues**. The later commits containing recycle restore/permanent-delete/retry coverage and the secure-store atomic-replacement regression were not part of this run, so its evidence is not attributed to those changes. All non-cloud JVM floors remain failed and `main` remains untouched.

Run `32445177140` independently verified the recycle restore/permanent-delete/retry tests and secure-store atomic-replacement regression on head `39a22e6`. Aggregate coverage rose to **52.34%**, Security to **80.90%**, and repository/data to **75.21%**; Vault stayed at **91.08%**, and Cloud remained passing at **90.49%**. Detekt remained **116 weighted issues** because the FileOperationStore typed-update refactor was committed after this run began. All non-cloud JVM floors remain failed, instrumented tests remain correctly gated, and `main` remains untouched.

Run `32445711354` independently validated the typed FileOperationStore transition refactor on head `4b1fc64`. Detekt fell from **116 to 102 weighted issues**. Aggregate and Security held at **52.34%** and **80.90%**; data rounded to **75.18%** because the refactor increased its instrumented instruction denominator, while Vault remained **91.08%** and Cloud remained passing at **90.49%**. The later CloudSyncWorker dependency-bundle and incremental document/image scan tests were not included in this run. All non-cloud JVM floors remain failed and `main` remains untouched.

Run `32446189823` validated the initial dependency-bundle commit `c9e8ed3` and exposed a static-policy compatibility regression: `check_architecture_boundaries.py` requires the durable CloudSyncWorker seam name `operationStoreOverride`, which the bundle had renamed. The corrected head `99e6040` restores that explicit field name and passed the local architecture checker plus the focused CloudSyncWorker test. Because the architecture gate failed before Gradle report generation, the coverage and dependency artifact-upload errors in this run are secondary missing-file symptoms, not independent artifact regressions. Detekt independently reported **101 weighted issues**. The branch remains ineligible for merge and `main` remains untouched.

Run `32447176928` independently validated the corrected CloudSyncWorker seam and the preceding coverage batch on head `05f50d7`. The architecture gate and JVM test suite completed. Artifact coverage improved to **53.05% aggregate**, **81.78% Security**, **76.67% data/repository**, and **91.46% Cloud**; Cloud remained above its 90% floor. Vault remained **91.08%**. All non-cloud JVM floors still failed, and Detekt remained **101 weighted issues**. Later source-formatting commits beginning with `d104b7c` were not included in this run. Instrumented tests remained correctly gated and `main` remains untouched.

## References

[1]: https://docs.github.com/en/rest/actions/workflow-runs#cancel-a-workflow-run "GitHub REST API: Cancel a workflow run"
