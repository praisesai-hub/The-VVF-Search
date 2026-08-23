# Security Exceptions

## Kotlin Gradle Plugin and CodeQL extractor compatibility

**Status:** Formally open under the single exception permitted by the remediation plan.

**Security advisory:** `GHSA-r937-wjx7-w2jp` / `CVE-2026-53914`, affecting Kotlin Gradle plugin versions below `2.4.20-Beta1` because of unsafe build-cache deserialization.

**Patched version boundary:** The first patched Kotlin Gradle plugin version identified by the repository's security triage is `2.4.20-Beta1`. The current production graph is `2.4.20-RC`, which is also patched.

**CodeQL compatibility evidence:** The official CodeQL 2.26.2 release note states that Java/Kotlin analysis supports Kotlin versions up to `2.4.10` [1]. The current CodeQL supported-languages documentation lists Kotlin support through the `2.4.1x` family [2], which does not establish support for `2.4.20-Beta1` or `2.4.20-RC`. The previously observed hosted extractor error was:

> Kotlin version 2.4.20-Beta1 is too recent. CodeQL currently supports versions below 2.4.20.

**Current repository evidence:** The current PR #47 head is `f8d1fb5639653e23a3cd12b220867216c7e16586`. CodeQL run `32603515808` was terminal `SUCCESS` for that SHA, but the workflow first rewrote a workspace copy of `gradle/libs.versions.toml` from production Kotlin `2.4.20-RC` to compatibility Kotlin `2.3.21`. That run therefore proves the compatibility workspace path, not CodeQL extraction of the patched production Kotlin graph. PR #41 was freshly verified by Android run `32613126464` at head `effc1c88fa8a52aef67bdbae148401fea4c3ac4b`; PR #42 was freshly verified by Android run `32614852971` at head `9e88619623c00c13ffa9226f89257457ace7c5f7`.

**Why the exception remains:** As of 2026-08-23, the available official CodeQL evidence does not show support for the patched `2.4.20` line. Downgrading Kotlin would reintroduce the security advisory, while disabling CodeQL, reducing its scope, or claiming the compatibility-copy run validates the production graph would violate the remediation constraints.

**Automated recheck:** `.github/workflows/kotlin-codeql-recheck.yml` runs monthly and on manual dispatch. It resolves the latest patched Kotlin Gradle plugin version from Maven metadata, temporarily applies that version in the checked-out workspace, builds it, runs hosted CodeQL against that same candidate workspace, and posts the exact build and CodeQL outcomes to Issue #34. When CodeQL begins supporting a patched candidate, the workflow reports both stages as successful so this exception can be removed through a normal reviewed upgrade.

**Next review date:** 2026-09-23, and monthly thereafter through the scheduled workflow.

## References

[1]: https://github.blog/changelog/2026-08-04-codeql-2-26-2-adds-swift-6-3-3-and-kotlin-2-4-10-support/ "CodeQL 2.26.2 adds Swift 6.3.3 and Kotlin 2.4.10 support"
[2]: https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/ "CodeQL supported languages and frameworks"
