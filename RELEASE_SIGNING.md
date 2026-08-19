# Android Release Signing and GitHub Actions

The signed Android App Bundle workflow is defined in `.github/workflows/signed-release.yml`. It can only be manually started from the `main` branch and uses the protected GitHub `production` environment. Normal push and pull-request CI never receives release-signing credentials.

> Use the **Google Play upload key**, not an exported Play App Signing certificate. Google Play uses the upload key to authenticate an uploaded AAB and then applies the app-signing key managed in Play Console.

## Required GitHub Secrets

Create the following secrets in the repository's **production** environment. Do not commit any of these values or send them in a pull request.

| Secret | Value | Notes |
|---|---|---|
| `KEYSTORE_BASE64` | Single-line Base64 encoding of the upload-key `.jks` or `.keystore` file | This is decoded only to the ephemeral GitHub runner's temp directory. |
| `STORE_PASSWORD` | Keystore password | The password protecting the upload-key keystore. |
| `KEY_ALIAS` | Upload-key alias | The alias shown by `keytool -list`. |
| `KEY_PASSWORD` | Upload-key entry password | Often the same as `STORE_PASSWORD`, but it must be supplied separately. |

Create the Base64 value without adding line breaks.

```bash
# Linux
base64 -w 0 upload-key.jks

# macOS
base64 < upload-key.jks | tr -d '\n'
```

Before storing it as a secret, verify the keystore locally without exposing its password in shell history where possible.

```bash
keytool -list -keystore upload-key.jks -alias YOUR_KEY_ALIAS
```

## Creating a Signed AAB

Open **Actions**, select **Signed Android Release**, choose the `main` branch, and select **Run workflow**. Provide the intended version name, for example `1.0.42`, and a new positive version code, for example `42`.

Before any signing secret is used, the workflow runs a blocking release dependency gate. It resolves the root and app build environments plus release/debug runtime graphs, validates the checked-out revision and explicit versions, scans the resolved Maven inventory against OSV (including GHSA/CVE aliases), enforces the forbidden-dependency and critical-version policy, applies the runtime security policy, and generates a CycloneDX SBOM. Any vulnerability, license violation, forbidden dependency, outdated critical dependency, unresolved graph, or invalid policy result stops the release.

Only after that gate passes does the workflow run JVM quality checks (`testDebugUnitTest`, lint, and detekt), run Android emulator smoke tests with the connected debug test suite and instrumented coverage gate, validate all four signing secrets, validate the keystore and alias, build `:app:bundleRelease`, verify the resulting AAB signature, generate a GitHub artifact attestation containing SLSA provenance and the SBOM predicate, and upload release artifacts plus a separate evidence bundle containing the runtime reports and digests.

| File | Purpose |
|---|---|
| `app-release-VERSION_NAME-VERSION_CODE.aab` | Signed Android App Bundle for Play Console upload. |
| `app-release-VERSION_NAME-VERSION_CODE.sha256` | SHA-256 integrity checksum. |
| `dependencies.cdx.json` | CycloneDX 1.5 SBOM for the resolved release dependency graph. |

Release artifacts are retained for 30 days and the evidence bundle is retained for 90 days. Download the AAB only from the successful workflow run and verify the checksum before uploading it to Play Console. The `release-evidence.json` manifest records the commit, workflow run, version inputs, tool versions, artifact sizes, and SHA-256 digests for the AAB, checksum, SBOM, dependency evidence, test reports, lint/detekt output, and instrumented coverage output.

## Security Controls

The workflow uses read-only repository access plus the minimal `id-token: write` and `attestations: write` permissions required for GitHub's signed provenance attestation. It disables persisted checkout credentials, permits one release at a time, and cleans up the decoded keystore and local artifact directory even if a build step fails. The repository `.gitignore` blocks keystores and local release artifacts from being tracked.
