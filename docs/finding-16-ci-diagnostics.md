# Finding 16 CI Diagnostics

## Hosted run 32257259336

**Status:** completed, failed. The run executed commit `0dc373a1b0a284533a62ca909016b294e6086736`, which predates the ratchet-policy commits.

| Area | Verified diagnostic | Remediation implication |
|---|---|---|
| JVM compilation | `VaultRepositoryTest.kt:183` invokes MockK `verify` without importing it. | Restore the missing import before any coverage measurement. |
| Database conversion | `DatabaseEncryptionMigratorInstrumentedTest.ensureEncrypted_convertsPlaintextDatabaseAndPreservesRows` failed at encrypted database version verification. | Validate and preserve the source `PRAGMA user_version` during SQLCipher export without opening a lower-version database through a higher-version Room helper callback. |
| Room invalidation | `FileDao.searchFiles` failed because Room does not know the raw FTS5 virtual table. | Observe the authoritative `files` entity for the Flow query; it is the table whose triggers update the FTS index. |
| FTS migration test | `SearchIndexMigrationInstrumentedTest` used framework SQLite and failed with `no such module: fts5`. | Run the FTS5 migration test using the production SQLCipher engine, not framework SQLite. |

## Vendor and platform evidence

Android Gradle Plugin supports separate instrumentation coverage through `enableAndroidTestCoverage` and the `create<Variant>AndroidTestCoverageReport` task. The project already enables this in `app/build.gradle.kts`; Finding 17 should use that supported task and a separate policy report.

SQLCipher's official Android documentation supports direct `SQLiteDatabase` opening as well as Room integration through `SupportOpenHelperFactory`. Direct SQLCipher access is the appropriate non-Room lifecycle path for read-only encrypted database validation.

## Subsequent hosted JVM evidence

| Run | Revision | Verified diagnostic | Corrective action |
|---|---|---|---|
| `32262193017` | `51a626d` | `CloudSyncContractTest` assumed that an unknown Robolectric content URI has no stream, while the runtime provider behavior is not a deterministic absence contract. `VaultCryptoSessionTest` also showed provider-dependent acceptance of a two-byte AES-GCM IV. | Make the SAF test provide an explicit resolver that returns `null`; require an exact 96-bit IV in production before constructing the GCM cipher. |
| `32263529180` | `2c3349f` | `SmartManagerRepositoryJvmTest.kt:107` used ordinary MockK `verify` around the suspend DAO method `insertCloudSyncItem`, causing `compileDebugUnitTestKotlin` to fail. | Use coroutine-aware `coVerify` and validate the corrective revision `4837745`. |

The outstanding validation run is `32264005040` at revision `4837745a5aa62f81c870433fad51c4a995598650`. Its final JVM coverage and device-test outcomes are intentionally not asserted until their artifacts are available.

## References

1. Android Developers, [View code coverage reports](https://developer.android.com/studio/test/coverage-report).
2. Zetetic, [SQLCipher for Android Migration](https://www.zetetic.net/sqlcipher/sqlcipher-for-android-migration/).
3. SQLCipher, [SQLCipher for Android README](https://github.com/sqlcipher/sqlcipher-android).
