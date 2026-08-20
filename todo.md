# CI Repair TODO

- [x] Collect one bounded status for the latest isolated-branch CI run.
- [x] Apply only a CI-confirmed targeted correction, if evidence identifies one.
- [x] Cover verified SmartManagerRepository cloud-queue guard and retry-state branches with a real Room DAO.
- [x] Run one final bounded isolated-branch validation without merging to main.
- [ ] Resolve the remaining enforced JVM coverage gates before any merge to main.
- [x] Inventory Android Lint, Detekt/ktlint, and external code-quality tooling configuration.
- [x] Run available static-analysis checks and collect reproducible output.
- [x] Assess SonarQube/CodeClimate integration readiness and report verified gaps.
- [x] Inventory JUnit, mocking, Kotlin Flow/StateFlow, and AI semantic test tooling.
- [x] Collect hosted JVM unit-test result and coverage artifact evidence.
- [x] Assess ViewModel, repository, AI semantic logic, and Flow test gaps.
- [x] Inventory Robolectric Activity, Room, and Android component integration tests.
- [x] Audit Hilt or alternative dependency-injection test configuration and execution evidence.
- [x] Verify JVM integration-test execution results from hosted CI artifacts.
- [x] Inventory Gradle build, test, Detekt, and ktlintCheck task availability.
- [x] Collect hosted compilation and JVM test execution evidence.
- [x] Assess build and quality-gate outcomes for remediation priorities.
- [ ] Report verified coverage results and unresolved release blockers.
