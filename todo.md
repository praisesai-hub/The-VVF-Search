# CI Repair TODO

- [x] Collect one bounded status for the latest isolated-branch CI run.
- [x] Apply only a CI-confirmed targeted correction, if evidence identifies one.
- [x] Cover verified SmartManagerRepository cloud-queue guard and retry-state branches with a real Room DAO.
- [x] Run one final bounded isolated-branch validation without merging to main.
- [ ] Resolve the remaining enforced JVM coverage gates before any merge to main.
- [x] Inventory Android Lint, Detekt/ktlint, and external code-quality tooling configuration.
- [x] Run available static-analysis checks and collect reproducible output.
- [x] Assess SonarQube/CodeClimate integration readiness and report verified gaps.
- [ ] Report verified coverage results and unresolved release blockers.
