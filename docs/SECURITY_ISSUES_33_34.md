# Security Issues 33 and 34 Audit

## Executive disposition

Both issues should remain open. Issue #33 is an automatically managed weekly security-health issue whose report still identifies Dependabot alert #50. Issue #34 tracks an upstream compatibility gap between the patched Kotlin compiler required by the security advisory and the hosted CodeQL Kotlin extractor. The repository has compensating controls, but neither issue’s closure criteria has been independently satisfied.

## Issue 33 — Weekly Dependabot and CI risks

Issue #33 is managed by the weekly security-health workflow. Its August 18 report identified Dependabot alert #50 for `org.jetbrains.kotlin:kotlin-gradle-plugin`, advisory `GHSA-r937-wjx7-w2jp` / `CVE-2026-53914`, with first patched version `2.4.20-Beta1`.

The production version catalog currently declares Kotlin `2.4.20-RC`, which is at or above the advisory’s first patched version. Production Gradle task-output caching and Kotlin task caching remain disabled. The isolated CI branch additionally restricts Gradle User Home caching to dependency/wrapper-oriented content by excluding `caches/build-cache-1` and `caches/keyrings`; this does not by itself close a Dependabot alert.

Issue #33 must remain open until GitHub’s authoritative Dependabot alert state confirms that alert #50 is closed after a resolved dependency graph submission. A local version comparison or compensating cache control is not sufficient evidence to close an automatically managed security-health issue.

## Issue 34 — Kotlin and CodeQL compatibility

Issue #34 documents that the hosted CodeQL Kotlin extractor rejected patched Kotlin versions at or above `2.4.20` with a message that supported versions were below `2.4.20`. The production graph now uses patched Kotlin `2.4.20-RC`, while the CodeQL workflow creates a workspace-only compatibility copy using Kotlin `2.3.21` before the analysis build. This preserves Java/Kotlin CodeQL extraction without disabling CodeQL and does not change the production dependency graph or shipped artifacts.

The compatibility helper is fail-closed and regression-tested. The latest recorded isolated-branch CodeQL verification passed on run `32373418809` at commit `ca698e1`. The later Android-workflow-only cache changes do not modify the CodeQL workflow, but a fresh CodeQL result for the current tip should still be obtained before any main-branch release claim.

Issue #34 should remain open until one of the following is true: hosted CodeQL supports the patched Kotlin compiler used by production; or the project formally accepts and documents the workspace-only compatibility fallback as the permanent closure control, with successful Android, JVM, instrumented, and CodeQL verification on the same release candidate.

## Required closure evidence

| Issue | Keep open because | Closure evidence required |
|---|---|---|
| #33 | Dependabot alert #50 is still reported as open by the weekly health issue. | GitHub Dependabot confirms alert #50 closed and the resolved dependency graph is successfully submitted. |
| #34 | The external CodeQL/Kotlin compatibility gap remains; the repository currently uses a compensating fallback. | Hosted CodeQL accepts the production Kotlin version, or the fallback is explicitly accepted as the release control and all required CI checks pass on the same release candidate. |

## References

1. [Issue 33](https://github.com/praisesai-hub/The-VVF-Search/issues/33)
2. [Issue 34](https://github.com/praisesai-hub/The-VVF-Search/issues/34)
3. [Advisory GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp)
4. [CodeQL build options for compiled languages](https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages)
