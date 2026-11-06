# Diwan Database rules

Diwan Database (`org.aristonis:diwan-database`) is a Java port of Laravel's database layer:
connections and transactions, query builder, schema builder, migrations, and Eloquent-style models.
It runs on Java 17 or later and on Android 9 (API 28) or later. Databases: SQLite and PostgreSQL.
Method names follow Laravel (`where`, `orderBy`, `firstOrCreate`). Java idioms replace PHP ones:
`Optional`, `java.time`, try-with-resources, unchecked exceptions.

## Entry points

| Database | Query builder | Schema builder |
|---|---|---|
| SQLite | `SQLiteQuery.of(db)` | `SQLiteSchema.of(db)` |
| PostgreSQL | `PostgresQuery.of(db)` | `PostgresSchema.of(db)` |
| Android | `AndroidSqliteDriver.of(helper)` as the driver, then `SQLiteQuery` and `SQLiteSchema` | same |

`db` is a `Connection` built with `Connection.builder().name(...).dataSource(...).build()`.
`SQLiteQuery.of(db).table("users")` starts a query. Models: `User.query.on(SQLiteQuery.of(db))`.

## Rules

Always:

- Close a `QueryCursor` with try-with-resources. A `stream()` from it releases the connection only
  when the stream is closed.
- Check any sort column that comes from a user against a fixed allowlist before `orderBy`.
  Identifiers are quoted, not bound.
- Run row locks (`lockForUpdate`, `sharedLock`, `lock`) only inside a transaction, on the
  connection the callback gives you. PostgreSQL throws outside one.
- Pass values as bindings. Use `whereRaw(sql, bindings)` and `selectRaw(sql, bindings)`.
- Declare `checksum()` on every migration, and name each migration with a fixed string in `name()`.
- Declare each model as `public static final ModelQuery<T> query = ModelQuery.of(T.class, T::new)`.
- On Android, override `table()` on models and name relation keys explicitly, or keep model class
  names in R8.
- Use the `tx` connection inside `db.transaction(tx -> ...)`.
- Keep an Android transaction, and a cursor, on the thread that opened it.

Never:

- Build SQL by joining strings with user input, in `whereRaw`, `orderByRaw`, `fromRaw` or anywhere.
- Edit a migration that already ran. Add a new migration. Use `repairChecksums` only on purpose,
  after an edit you meant to make.
- Call `upsert` on SQLite older than 3.24 (Android API 28 and 29). It throws
  `UnsupportedOperationException`.
- Use reflection or annotations to register models, relations or migrations. Diwan scans nothing.
- Call the root connection from inside
  one of its own transactions. Strict transactions refuse it with `IllegalStateException`.
- Put a connection string with a password, or binding values with personal data, in logs or issues.

## Docs

- Guides: https://docs.aristonis.org/diwan-database/ (getting started, connections, query builder,
  schema, migrations, models, relations, Android, errors, API).
- Source and issues: https://github.com/aristonis/diwan-database (public once released).
- Skills: the `skills/` folder next to this file.
