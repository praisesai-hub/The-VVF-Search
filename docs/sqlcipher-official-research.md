# SQLCipher Integration Research

Date checked: 2026-08-23.

Zetetic’s official migration guide states that the current Community Edition artifact is `net.zetetic:sqlcipher-android:4.18.0`, replacing the legacy `net.zetetic:android-database-sqlcipher:4.5.4`, and that the current package provides `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` for Room. The legacy library uses a different `net.sqlcipher.database.SupportFactory` API; these pairs must not be mixed.

The official SQLCipher Android repository documents Room 2 integration using `System.loadLibrary("sqlcipher")`, a UTF-8 passphrase byte array, `SupportOpenHelperFactory`, and `Room.databaseBuilder(...).openHelperFactory(factory)`. It also states support for Android API 23 and newer across the documented ABIs.

The official SQLCipher 4.18.0 release note states that 4.18.0 is a maintenance release and adds Room 3 support; this application currently uses Room 2 APIs, so the Room 2 `SupportOpenHelperFactory` path remains the relevant integration.

References:

1. https://www.zetetic.net/sqlcipher/sqlcipher-for-android-migration/
2. https://github.com/sqlcipher/sqlcipher-android
3. https://www.zetetic.net/blog/2026/08/18/sqlcipher-4.18.0-release/
