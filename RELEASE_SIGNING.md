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

The workflow validates all four signing secrets, validates the keystore and alias, builds `:app:bundleRelease`, verifies the resulting AAB signature, and uploads an artifact containing:

| File | Purpose |
|---|---|
| `app-release-VERSION_NAME-VERSION_CODE.aab` | Signed Android App Bundle for Play Console upload. |
| `app-release-VERSION_NAME-VERSION_CODE.aab.sha256` | SHA-256 integrity checksum. |

Artifacts are retained for 30 days. Download the AAB only from the successful workflow run and verify the checksum before uploading it to Play Console.

## Security Controls

The workflow uses `contents: read` only, disables persisted checkout credentials, permits one release at a time, and cleans up the decoded keystore and local artifact directory even if a build step fails. The repository `.gitignore` blocks keystores and local release artifacts from being tracked.
