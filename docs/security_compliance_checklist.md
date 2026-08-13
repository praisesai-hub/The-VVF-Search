# Semantic-Search Android — Automated Security & Compliance Checklist

## उद्देश्य और सीमा

यह checklist Android repository के लिए repeatable security/compliance gate प्रदान करती है। इसका automated runner source code और configuration का read-only निरीक्षण करता है और प्रत्येक control को **PASS**, **PARTIAL** या **FAIL** evidence के साथ रिपोर्ट करता है। यह अपने-आप runtime security, penetration testing, dependency CVE status, release signing validity, privacy-law legal compliance या Google Play approval सिद्ध नहीं करता।

Control families को OWASP MASVS के storage, cryptography, authentication, network, platform, code, resilience और privacy समूहों के अनुरूप रखा गया है [1]। Android की official security checklist authentication, data storage, permissions, networking, input validation, user data, API-key management, cryptography और interprocess communication को अलग review areas के रूप में रखती है [2]।

## चलाने का तरीका

Repository root से:

```bash
python3 scripts/security_compliance_check.py . \
  --json security-compliance-report.json \
  --markdown security-compliance-report.md
```

Runner केवल static checks करता है। यदि कोई critical static failure मिलता है तो उसका exit code non-zero होगा। Generated JSON और Markdown को CI artifact के रूप में सुरक्षित रखें। Source implementation में remediation किए बिना भी यह runner audit evidence तैयार कर सकता है।

## Automated controls

| Control | Automated check | PASS का अर्थ | PARTIAL/FAIL का अर्थ | Manual evidence required |
|---|---|---|---|---|
| MASVS-NETWORK-1 | `http://` references | App source/resources में match नहीं मिला | Cleartext endpoint/config मिला | Network Security Config, TLS interception test, certificate policy |
| MASVS-STORAGE-1 | Credential-like literals और token preference references | Hardcoded literal नहीं मिला; sensitive persistence match नहीं | Token/plain preference review आवश्यक | Secret scan, token lifecycle, encrypted-storage test, rotation/revocation |
| MASVS-CRYPTO-1 | Key fallback patterns | Fallback pattern नहीं मिला | Key lifecycle review आवश्यक | Keystore invalidation, process death, restore, hardware-backed behavior |
| MASVS-STORAGE-2 | Backup files और explicit exclusions | Backup policy के लिए evidence मौजूद | Exclusion/policy अस्पष्ट | Merged manifest, backup/restore, device transfer, vault/key behavior |
| MASVS-PLATFORM-1 | Manifest exported declarations | Components explicitly reviewed | Exported component मिला; intent validation review आवश्यक | Intent fuzzing, URI validation, merged manifest |
| MASVS-PLATFORM-2 | Storage/media permissions | Broad permission match नहीं | Runtime/API-level permission review आवश्यक | API 24–current permission denial/revocation/partial access |
| MASVS-RESILIENCE-1 | R8/minification configuration | Release shrinking config मौजूद | Release config अस्पष्ट | Signed release AAB/APK, mapping controls, tamper checks |
| MASVS-CODE-1 | TODO/mock/stub markers | Main sources में marker नहीं | Unfinished behavior review आवश्यक | Requirement traceability and code-owner sign-off |
| MASVS-CODE-2 | `local.properties`, keystore/JKS artifacts | Extracted tree में material नहीं मिला | Credential/signing material detected | Full Git-history secret scan and credential rotation |
| MASVS-CODE-3 | Test inventory | Tests detected | No tests detected | Executed unit, instrumentation, integration and security tests |
| BUILD-1 | Gradle wrapper | Wrapper exists | Reproducibility risk | Clean CI build with pinned JDK/SDK/build tools |

## Required manual gates before production

### Authentication and authorization

Verify OAuth redirect URI restrictions, state/nonce validation, token expiry and refresh failure handling, logout revocation, account switching, least privilege scopes, and authorization on every cloud operation. Do not treat the presence of an auth manager as proof that the authentication flow is secure.

### Sensitive storage and vault

Verify that refresh tokens, PIN metadata, vault indexes, encrypted files and logs do not expose secrets. Test Keystore initialization failure, key invalidation, process death, app upgrade, uninstall/reinstall, backup restore, device migration and locked-device behavior. Any inability to decrypt user data must be surfaced as a controlled recovery state, not silently replaced with a new key.

### Network and cloud sync

Verify TLS-only endpoints, hostname validation, certificate behavior, timeout policy, retry backoff, duplicate request idempotency, pagination, conflict resolution, remote deletion semantics and offline recovery. Test expired credentials and partial upload/download failures.

### Filesystem, permissions and destructive operations

Verify scoped-storage behavior across supported API levels, permission denial and revocation, symlink/path traversal handling, inaccessible files, low-storage conditions, concurrent modification, duplicate-selection determinism, Recycle Bin restore and crash recovery between filesystem mutation and database update.

### Build, supply chain and release

Run dependency vulnerability scanning, license review, secret scanning over the full Git history, lint, unit tests, instrumentation tests, release R8 build, signing verification, merged-manifest review and APK/AAB static inspection. Keep signing credentials outside the repository and in a controlled CI secret store.

### Privacy and compliance evidence

Maintain a data inventory showing each collected datum, purpose, storage location, retention period, cloud destination, deletion path and user disclosure. Map the inventory to the applicable privacy policy and jurisdictional requirements with qualified legal review. This checklist is technical evidence; it is not legal advice or a legal compliance certification.

## CI acceptance policy

A release candidate should be blocked when any **CRITICAL** or **HIGH** control is `FAIL`, when generated reports are missing, or when Level 2–4 verification has not executed in a clean CI environment. `PARTIAL` findings must have an owner, remediation ticket, risk acceptance or compensating control. `PASS` from the static runner must not be promoted to runtime verification without device/emulator evidence.

## Verification levels

| Level | Meaning | Required evidence |
|---|---|---|
| Level 1 | Source Verified | Code/config inspection and generated runner evidence |
| Level 2 | Build Verified | Clean Gradle build and release-oriented artifact build |
| Level 3 | Static Analysis Verified | Lint, dependency/secret/license scans, APK/AAB inspection |
| Level 4 | Automated Test Verified | Unit, instrumentation, integration and security regression tests |
| Level 5 | Runtime Verified | Physical/emulated device matrix and operational scenarios |

## References

[1]: https://mas.owasp.org/MASVS/ "OWASP MASVS — Mobile Application Security Verification Standard"
[2]: https://developer.android.com/privacy-and-security/security-tips "Android Developers — Security checklist"
[3]: https://developer.android.com/privacy-and-security/keystore "Android Developers — Android Keystore system"
[4]: https://developer.android.com/identity/data/autobackup "Android Developers — Back up user data with Auto Backup"
[5]: https://developer.android.com/studio/publish/app-signing "Android Developers — Sign your app"
