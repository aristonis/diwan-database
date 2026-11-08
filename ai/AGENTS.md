# Diwan Database rules

Diwan Database (`org.aristonis:diwan-database`) is a Java port of Laravel's database layer:
connections and transactions, query builder, schema builder, migrations, and Eloquent-style models.
It runs on Java 17 or later and on Android 9 (API 28) or later. Databases: SQLite and PostgreSQL.
Method names follow Laravel (`where`, `orderBy`, `firstOrCreate`). Java idioms replace PHP ones:
`Optional`, `java.time`, try-with-resources, unchecked exceptions.

## Entry points

Start from a `DatabaseManager`. It builds the query factory, schema and migrator from the
connection's configuration, so your code never names a driver class and switching the database is a
configuration change.

```java
QueryFactory db = manager.query();                 // default connection; manager.query("name") for another
Schema schema = manager.schema();
Migrator migrator = manager.migrator("main").add(new CreateUsersTable()).build();
db.table("users").where("id", 1).first();
```

Models: `User.query.on(manager.query())`.

Without a manager, take the factories from the grammar module: `SQLiteQuery.of(connection)`,
`SQLiteSchema.of(connection)`, `PostgresQuery.of(connection)`, `PostgresSchema.of(connection)`. On
Android, `AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of)` is registered once
with the manager. Inside a transaction, call `db.on(tx)` so the work runs on the transaction.

## Rules

Order of use: (1) models; (2) the query builder where a model cannot express the query;
(3) `*Raw` methods only for a feature Diwan lacks, with values as bindings, and report the gap
(see the `diwan-report-issue` skill). For the schema, use builder methods only, with the same
fallback.

Always:

- Close a `QueryCursor` with try-with-resources. A `stream()` from it releases the connection only
  when the stream is closed.
- Check any sort column that comes from a user against a fixed allowlist before `orderBy`.
  Identifiers are quoted, not bound.
- Run row locks (`lockForUpdate`, `sharedLock`, `lock`) only inside a transaction, on the
  connection the callback gives you. PostgreSQL throws outside one.
- Declare `checksum()` on every migration as a fixed constant set once (not a version), and name
  each migration with a fixed string in `name()`.
- Declare each model as `public static final ModelQuery<T> query = ModelQuery.of(T.class, T::new)`.
- On Android, override `table()` on models and name relation keys explicitly, or keep model class
  names in R8.
- Use the `tx` connection inside `db.transaction(tx -> ...)`.
- Keep an Android transaction, and a cursor, on the thread that opened it.
- Import exceptions from `org.aristonis.diwan.database.exception` and
  `org.aristonis.diwan.database.eloquent.exception`. `DatabaseException` is the parent of every
  database, driver, migration and model failure. Never import from an `internal` package: those
  classes are `@Internal`, not exported from the Java module, and may change in any release.

Never:

- Build SQL by joining strings with user input, in `whereRaw`, `orderByRaw`, `fromRaw` or anywhere.
- Edit a migration that has been deployed, or change its checksum. Change the schema with a new
  migration. `repairChecksums` is for recovery only (a local database that was never deployed, or a
  known accident).
- Write two versions of one rule by branching on the database driver in a migration.
- Call `upsert` on SQLite older than 3.24 (Android API 28 and 29). It throws
  `UnsupportedOperationException`.
- Use reflection or annotations to register models, relations or migrations. Diwan scans nothing.
- Call the root connection from inside
  one of its own transactions. Strict transactions refuse it with `IllegalStateException`.
- Put a connection string with a password, or binding values with personal data, in logs or issues.

## Docs

- Guides: https://aristonis.org/open-source/diwan-database/docs/main/home (getting started, connections, query builder,
  schema, migrations, models, relations, Android, errors, API).
- Source and issues: https://github.com/aristonis/diwan-database (public once released).
- Skills: the `skills/` folder next to this file.
