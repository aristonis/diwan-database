# 0012. Android driver

## Status

Accepted

## Context

The first application's mobile edition runs on Android 9 (API 28) and later, with SQLite. Android
has no JDBC, so the core's JDBC driver cannot run there. Android 9 ships SQLite 3.22, older than
anything the JDBC tests use.

## Decision

1. A module, `diwan-database-android`, holds `AndroidSqliteDriver`, a `Driver` over Android's own
   `SQLiteDatabase`. It is a plain JAR compiled against `android.jar`, not an Android library
   (AAR), and has no runtime dependency. The build includes it only where an Android SDK is found.
2. All sessions share the one database the application opened; a session never closes it.
   Transactions use Android's own transaction calls, savepoints raw SQL. Discarding a session ends a
   transaction it began without committing it.
3. Values are stored as the JDBC driver stores them on SQLite: booleans as 1 and 0, `BigDecimal`
   as text, `java.time` values as ISO text.
4. Android's exceptions map to the same failure kinds as on JDBC; the code in Android's message is
   the vendor code, and the database's text is kept apart from the message.
5. Tests run in `check` on Robolectric, which runs Android's API over a native SQLite, and the
   blog migrations run on an emulator through a sandbox app before a release.

6. Android ties a transaction to the thread that began it. A session records that thread and
   refuses to commit, roll back or close from another one, leaving the transaction for its owner,
   so a transaction callback must not move to another thread (a coroutine context switch, an
   executor). `of(SQLiteOpenHelper)` opens the database on first use, so it runs the helper's
   upgrade off the thread that built the driver.

## Consequences

- The same migrations and connection code run on desktop and phone.
- Running on Android 9's SQLite found a grammar bug: the table-valued
  `pragma_foreign_key_check(?)` takes no argument before SQLite 3.26. The grammar now uses the
  `pragma foreign_key_check("table")` statement, which works on every version.
- Robolectric pulls Android libraries (AAR) and Google's repository into the test build only.
- The emulator check is manual; it is not part of `check`.
- Results are read whole into memory, and Android reads a cursor in 2 MB windows: page large reads
  with `limit`, and keep large blobs out of rows that are queried in bulk.
- `beginTransaction` is exclusive; without write-ahead logging (`enableWriteAheadLogging`) a long
  transaction blocks reads from other threads.
- Decimals come back as SQLite stores them: a `decimal` column has numeric affinity, so `10.10`
  reads as the double `10.1`, as with the JDBC driver.
