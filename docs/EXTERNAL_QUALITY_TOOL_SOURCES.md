# External Quality Tool Sources

This audit used the following official sources to assess external code-quality integration readiness.

| Tool | Source | Verified relevance |
|---|---|---|
| SonarQube | https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner-for-gradle | The Gradle scanner supports Android/AGP analysis, requires compiled bytecode for Java/Android analysis, and runs through the `sonar` Gradle task with token-based authentication. |
| Qlty | https://docs.qlty.sh/what-is-qlty | Qlty, from the makers of Code Climate, provides code smells, duplication, complexity, coverage, security scanning, and PR quality gates. |
