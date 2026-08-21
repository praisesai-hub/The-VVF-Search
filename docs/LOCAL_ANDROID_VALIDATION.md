# Local Android Validation Environment

## Purpose

This document records the local, non-secret toolchain used to catch compilation errors before dispatching a hosted Android workflow. It is intentionally separate from release signing and Firebase credentials.

## Installed Toolchain

| Component | Local value | Rationale |
|---|---:|---|
| JDK compiler | OpenJDK 17.0.19 | Matches hosted CI's JDK 17 contract. |
| Android platform | `platforms;android-37.0` | Supplies the project's `compileSdk = 37` API surface. |
| Build tools | `build-tools;36.0.0`, `build-tools;37.0.0` | The project currently resolves Build Tools 36.0.0 while compiling against API 37. |
| Platform tools | Current SDK package | Supplies standard Android command-line tooling. |

The Android SDK is configured only through ignored `local.properties` (`sdk.dir=/home/ubuntu/android-sdk`). No secret, signing key, or `google-services.json` is stored in version control.

## Verified Constraints

The Android SDK command-line tools documentation requires packages to be installed through `sdkmanager` and recommends `ANDROID_HOME` for command-line use. [1] The package manager documentation specifies the package-path format used above and the headless license-acceptance flow. [2]

The first offline compile resolved the local SDK but could not find the uncached AGP AAPT2 artifact. After the artifact bootstrap was allowed online, the debug compile reached the Google Services plugin and reported a missing `google-services.json`. CI deliberately treats this file as optional for validation, so the pending local-build task is to align the plugin's no-secret behavior with that policy without adding credentials or disabling consent protections.

## 2026-08-21 Validation Evidence

| Command scope | Outcome | Interpretation |
|---|---|---|
| `:app:compileDebugKotlin` | Passed in 25 seconds with an explicit 1.5 GiB Gradle cap after the cloud-result type repair. | The current Kotlin sources compile locally. |
| `VaultManagerEngineTest` | Passed in 11 seconds with one worker and in-process Kotlin compilation. | A security-critical focused JVM test is locally executable. |
| `ExampleRobolectricTest` after runner removal | Passed in 10 seconds. | The temporary-file test no longer boots Robolectric despite not using Android APIs. |
| Full `:app:testDebugUnitTest` | Controlled stop after one five-minute observation: it had completed zero tests while starting the former Robolectric temporary-file test. | This is not a test failure or a passing result; it was stopped to comply with the bounded-wait rule. |
| `:app:lintDebug` | Gradle daemon disappeared before a Lint report was produced. | This is a local resource/runtime limitation, not evidence that Lint passes or fails. Hosted CI remains the authoritative Lint check. |

The cloud worker repair was required because `CloudSyncResult` is a sealed base type: only its `Success` and `Error` variants expose remote checkpoint fields. The worker now maps each variant explicitly to `CloudTransferProgress` before persisting the durable transfer state, which restores type-safe Kotlin compilation.

## References

[1]: https://developer.android.com/tools "Android command-line tools"
[2]: https://developer.android.com/tools/sdkmanager "Android SDK Manager"
