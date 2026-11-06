---
name: diwan-android
description: Run Diwan Database on Android (API 28 and later) over SQLiteDatabase. Use when setting up the Android driver, handling threads and transactions, shrinking with R8, or hitting an API-level limit such as upsert.
---

# Diwan on Android

## Steps

1. Add `diwan-database`, `diwan-database-android`, `diwan-database-sqlite`, and
   `diwan-database-eloquent` if you use models.
2. In your `SQLiteOpenHelper.onConfigure`, call `setForeignKeyConstraintsEnabled(true)` and
   `enableWriteAheadLogging()`.
3. Build the connection with `AndroidSqliteDriver.of(helper)`. Make the first call off the main thread.
4. Run migrations with `SQLiteSchema.of(db)`, queries with `SQLiteQuery.of(db)`.
5. Prepare R8: explicit names or a keep rule.

## Example

```java
Connection db = Connection.builder()
        .name("app")
        .driver(AndroidSqliteDriver.of(new AppDb(context)))
        .build();
Migrator.builder(SQLiteSchema.of(db)).migrations(migrations).build().run();
QueryFactory query = SQLiteQuery.of(db);
```

```
-keepnames class * extends org.aristonis.diwan.database.eloquent.Model
```

## Pitfalls

- A transaction and a cursor belong to the thread that opened them. Keep a whole
  `transaction(...)` callback on one thread: no coroutine context switch, no executor hand-off.
- Diwan does not leave the main thread for you. Run database work on a background thread.
- `upsert` needs SQLite 3.24, which is API 30. On API 28 and 29 it throws
  `UnsupportedOperationException`. Use a read-then-write inside a transaction there.
  Right joins need API 34.
- Without write-ahead logging a long transaction blocks reads from other threads.
- `cursor()` refills its window by re-running the query. Prefer `chunkById` or `lazyById`.
- R8 only breaks defaults taken from class names. Override `table()` on models, name relation keys,
  and give each migration a fixed `name()`. Use `checksum()`, not bytecode, to detect an edit.
- `decimal` reads as a double. Store money as integer minor units.
- Foreign keys are off on Android until you enable them.

Docs: Android on https://docs.aristonis.org/diwan-database/
