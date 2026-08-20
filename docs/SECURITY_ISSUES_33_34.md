# Security Issues 33 and 34 Audit

## Executive disposition

Both issues should remain open. Issue #33 is an automatically managed weekly security-health issue whose report still identifies Dependabot alert #50. Issue #34 tracks an upstream compatibility gap between the patched Kotlin compiler required by the security advisory and the hosted CodeQL Kotlin extractor. The repository has compensating controls, but neither issue’s closure criteria has been independently satisfied.

## Issue 33 — Weekly Dependabot and CI risks

Issue #33 is managed by the weekly security-health workflow. Its August 18 report identified Dependabot alert #50 for `org.jetbrains.kotlin:kotlin-gradle-plugin`, advisory `GHSA-r937-wjx7-w2jp` / `CVE-2026-53914`, with first patched version `2.4.20-Beta1`.

On August 20, the enabled GitHub connector returned `403 Resource not accessible by integration` for the Dependabot alerts endpoint. The response explicitly advertised the required GitHub App permission as `X-Accepted-Github-Permissions: vulnerability_alerts=read`; it did not expose a historical record of whether that permission had previously been granted. The alert's current state is therefore **unverified from this integration**, not assumed open or closed.

The production version catalog currently declares Kotlin `2.4.20-RC`, which is at or above the advisory’s first patched version. Production Gradle task-output caching and Kotlin task caching remain disabled. The isolated CI branch additionally restricts Gradle User Home caching to dependency/wrapper-oriented content by excluding `caches/build-cache-1` and `caches/keyrings`; this does not by itself close a Dependabot alert.

Issue #33 must remain open until GitHub’s authoritative Dependabot alert state confirms that alert #50 is closed after a resolved dependency graph submission. A local version comparison or compensating cache control is not sufficient evidence to close an automatically managed security-health issue.

## Issue 34 — Kotlin and CodeQL compatibility

Issue #34 documents that the hosted CodeQL Kotlin extractor rejected patched Kotlin versions at or above `2.4.20` with a message that supported versions were below `2.4.20`. The production graph now uses patched Kotlin `2.4.20-RC`, while the CodeQL workflow creates a workspace-only compatibility copy using Kotlin `2.3.21` before the analysis build. This preserves Java/Kotlin CodeQL extraction without disabling CodeQL and does not change the production dependency graph or shipped artifacts.

The compatibility helper is fail-closed and regression-tested. The latest accessible isolated-branch CodeQL verification passed on run `32387992905` at commit `3b59740391799c03772e024ce9a7b0418f6e5031`. The user-provided CodeQL configuration URL returned a page-not-found response in the unauthenticated browser session, while the code-scanning API returned `403 Resource not accessible by integration`; neither result changes the successful workflow-run evidence, but both prevent independent inspection of that configuration record through the current session.

Android CI run `32389660487` verified that application-scoped JaCoCo instrumentation raises JVM instruction coverage from the earlier 6.00% baseline to 23.06% (`19,321/83,797`). Subsequent real Room DAO and Compose-render coverage work raised the aggregate further to 38.58% (`32,333/83,797`) in run `32397946218`. The required JVM aggregate 70.00% and all scoped JVM gates still fail: security-critical 44.70%/90.00%, repository/data 53.72%/85.00%, vault 38.70%/95.00%, and cloud sync 49.79%/90.00%. The same run's instrumented aggregate is expected to retain its previously observed passing aggregate, but its scoped security, storage, vault, and cloud-sync gates must be re-verified after the final test additions. These are release blockers and are not grounds to weaken the production coverage policy.

Issue #34 should remain open until one of the following is true: hosted CodeQL supports the patched Kotlin compiler used by production; or the project formally accepts and documents the workspace-only compatibility fallback as the permanent closure control, with successful Android, JVM, instrumented, and CodeQL verification on the same release candidate.

## Required closure evidence

| Issue | Keep open because | Closure evidence required |
|---|---|---|
| #33 | The weekly health issue identifies alert #50, but the connector cannot currently read Dependabot alerts to independently verify its state. | GitHub Dependabot confirms alert #50 closed and the resolved dependency graph is successfully submitted. |
| #34 | The external CodeQL/Kotlin compatibility gap remains; the repository currently uses a compensating fallback. | Hosted CodeQL accepts the production Kotlin version, or the fallback is explicitly accepted as the release control and all required CI checks pass on the same release candidate. |

## References

1. [Issue 33](https://github.com/praisesai-hub/The-VVF-Search/issues/33)
2. [Issue 34](https://github.com/praisesai-hub/The-VVF-Search/issues/34)
3. [Advisory GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp)
4. [CodeQL build options for compiled languages](https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages)
