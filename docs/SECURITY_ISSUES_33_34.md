# Security Issues 33 and 34 Audit

## Issue 33 — Weekly Dependabot and CI risks

Issue 33 is an automatically managed weekly-health report. Its August 18 report identified Dependabot alert #50 for `org.jetbrains.kotlin:kotlin-gradle-plugin` and recorded the first patched version as `2.4.20-Beta1`. The repository now declares patched Kotlin `2.4.20-RC`, and the release dependency policy requires at least `2.4.20-Beta1`. The dependency-submission workflow completed successfully for commit `45e505c`.

The alert endpoint is not readable by the current repository token (`403 Resource not accessible by integration`), so the alert cannot be independently marked closed from this environment. The weekly workflow must remain the source of truth for alert visibility and should continue tracking the issue until GitHub confirms that the resolved dependency graph closes alert #50.

## Issue 34 — Kotlin and CodeQL compatibility

The hosted CodeQL Kotlin extractor currently rejects Kotlin `2.4.20-RC` with the message that supported versions are below `2.4.20`. CodeQL documentation confirms that Kotlin requires a build for Java/Kotlin analysis; switching to `build-mode: none` would omit Kotlin analysis and is therefore not an acceptable mitigation.

The CodeQL workflow now runs `scripts/prepare_codeql_compatibility.py` after CodeQL initialization. The script changes only the checked-out workspace's version catalog from production Kotlin `2.4.20-RC` to the known CodeQL-compatible `2.3.21` before the analysis build. Android CI, dependency submission, release gates, and shipped artifacts continue to use the patched production Kotlin version. This preserves full Java/Kotlin CodeQL extraction without disabling CodeQL or weakening the production dependency policy.

The compatibility script is covered by `scripts/test_prepare_codeql_compatibility.py`, including fail-closed behavior when the Kotlin declaration is absent and idempotence when the supported version is already selected.

## References

1. [GitHub CodeQL build options for compiled languages](https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages)
2. [GitHub CodeQL scanning for compiled languages](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages)
3. [Issue 33](https://github.com/praisesai-hub/The-VVF-Search/issues/33)
4. [Issue 34](https://github.com/praisesai-hub/The-VVF-Search/issues/34)
