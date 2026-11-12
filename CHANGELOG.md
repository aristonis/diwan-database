# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `SearchOptions`, the settings of an escaped text search, and a `SearchOptions` overload of the 12 escaped search methods on the query builder and the model builder.
- `QueryGrammar.searchPattern(String, SearchWhere.Anchor, SearchOptions)` and a `validateSearchOptions` hook a grammar overrides to refuse a setting.
- `Casts.collection()` and `Casts.object()`, and the `collection` and `object` cast strings: a stored JSON array reads as a `List`, a stored JSON object as a `Map`, a value of the other shape throws `InvalidCastException`, and null stores SQL NULL. For typed elements, a typed map or a DTO, implement `Cast<T>` yourself.

### Deprecated
- The 24 `boolean caseSensitive` overloads of the escaped search methods (12 on the query builder, 12 on the model builder) and `QueryGrammar.searchPattern(String, SearchWhere.Anchor, boolean)`, for removal in 0.6.0. Use `SearchOptions.caseSensitive()` in place of `true`.

## [0.4.0] - 2026-11-11

### Added
- Partial indexes: `where`, `whereNull` and `whereNotNull` on the `IndexDefinition` that `index` and `unique` return.
- Index order and collation: `asc`, `desc` and `collate` on `IndexDefinition`.
- `IndexInfo.orders()`, `IndexInfo.collations()` and `IndexInfo.where()`.
- `DiwanError.table()`, the table a failure concerns, where the driver can name it.
- Schema inspection: `Schema.getColumns` (with `ColumnInfo` and `ColumnGeneration`), `getColumnListing`, `hasColumns`, `getIndexListing` and four `hasIndex` overloads with the `IndexType` filter (`PRIMARY`, `UNIQUE`), on SQLite, PostgreSQL and Android.
- `OffsetTime` as a binding on every driver (SQLite and Android store it as UTC text, `HH:mm:ss+00:00`) and as a cursor value, so cursor pagination works on a PostgreSQL `timetz` column.
- `DriverCapability.ERROR_ABORTS_TRANSACTION`, declared by the PostgreSQL driver for a driver whose failed statement aborts the transaction.
- PostgreSQL `timezone` connection option (for example `UTC`), off by default: sets the session time zone. Use it, or a `timestamptz` column, when `Instant` values go into plain `timestamp` columns.
- `FileMigrationLock`, a `MigrationLock` that locks a `<database>.migrate.lock` file beside a file database.
- SQLite and Android connectors lock file databases during migrations, so two processes do not migrate one file at once. The `lock_wait` option sets the wait, 60 seconds by default; in-memory databases have no lock.
- `MigrationLock.explain(RuntimeException)`, a default method a lock uses to add a note to a failed run.
- `QueryFactory.restartIdentities(tables)`, which sets the identity and `serial` counters of PostgreSQL tables to the value after the highest committed id, or to the start value of an empty table. SQLite and Android do nothing. Needs PostgreSQL 10 or later.
- `RefreshDatabase.rollback(...).restartingIdentities()`, which restarts those counters before each test, so rollback-mode tests get the same ids on every run. Refused with parallel tests and in truncate mode.
- `Blueprint.temporary()`, which makes `schema.create` write `create temporary table` on SQLite and PostgreSQL. A temporary table lives on one connection; on SQLite a change that needs a table rebuild is refused with `IllegalStateException`; plain additions work.
- `Blueprint.rawIndex(expression, name)`, an index over a raw SQL expression such as `lower(email)`, which returns an `IndexDefinition` so `where` can make it partial. The expression is written as given, so it must never come from user input. SQLite table rebuilds keep the index.
- `Blueprint.comment(text)`, a table comment on PostgreSQL. SQLite skips it without an error.
- `Blueprint.foreignIdFor(ForeignKeySource)` and `foreignIdFor(ForeignKeySource, column)`, which add the column for another table's key, typed by the key kind; `constrained()` then references the key's table and column.
- `ForeignKeySource`, the interface that describes another table's key, with `KeyKind` (`INTEGER`, `UUID`, `ULID`), and `ModelKeys.of(...)` in the eloquent module, which builds one from a model.
- `Schema.createDatabase(name)`, `createDatabase(name, template)` and `createDatabase(name, template, CopyStrategy)`, and `dropDatabaseIfExists(name)` and `dropDatabaseIfExists(name, force)`, on PostgreSQL and SQLite. `CopyStrategy` (`FILE_COPY`, `WAL_LOG`) picks how PostgreSQL 15 and later copy a template; `force` needs PostgreSQL 13 and later. On SQLite a name is a file path, and the template is a file that is copied.
- `RefreshDatabase.template(DataSource, Function<String, DatabaseManager>, Function<Connection, Schema>, List<Migration>)`, template mode: each test gets its own copy of a migrated template, so it may commit and run in parallel. The options `strategy(CopyStrategy)` and `dropStaleTemplates()` (PostgreSQL) and `directory(Path)` (SQLite, required there) tune it. PostgreSQL and SQLite file databases are supported; in-memory SQLite and Android are not yet.

### Changed
- `index` and `unique` return `IndexDefinition` instead of `void`. Code compiled against 0.3.0 must be recompiled.
- PostgreSQL: after a failed statement, the next statement, commit or savepoint release in the same transaction is refused with a clear `IllegalStateException` ("The transaction was aborted by a failed statement; roll back (or roll back to the savepoint) first", the first failure as cause) instead of server error 25P02. Rolling back to a savepoint clears it.
- PostgreSQL: when a pool is too small for the migration lock and the migrator, a failed run says the pool needs two sessions, in a suppressed note on the error.

### Fixed
- Android: a commit that fails on a deferred foreign key now rolls back instead of leaving the transaction open.
- PostgreSQL: reading `NaN` or `Infinity` from a `numeric` column now fails with an error that names the column and never shows the value; `float` columns still return `NaN`.

### Internal
- Large classes split by responsibility (schema grammars, JDBC session, SQLite table state); public API unchanged. New `codeSize` task lists main files over 500 code lines.

## [0.3.1] - 2026-11-09

### Fixed
- SQLite and Android: `truncate()` and `truncate(List)` failed with a foreign key violation on a
  table whose key is declared `restrictOnDelete()` and refers to its own table, which also broke
  `RefreshDatabase` truncate mode. When a restrict key links listed tables, the deletes now run outside a
  transaction with enforcement switched off, and `pragma foreign_key_check` refuses any new orphan
  before the commit. Other keys keep their cascade and set-null actions.
- PostgreSQL: SQL state 23001 (`restrict_violation`) is now a foreign key violation, with the
  constraint name in `DriverException.error().key()`.

## [0.3.0] - 2026-11-09

### Added
- `Casts.decimalText(precision, scale)` and `decimalText(precision, scale, RoundingMode)`: the strict decimal cast for a text column, without the SQLite 15-digit limit.

- `Casts.decimal(precision, scale)` and `Casts.decimal(precision, scale, RoundingMode)`, with the
  `decimal:P,S` and `decimal:P,S,MODE` cast strings. The strict decimal refuses too many integer
  digits, and refuses extra decimals unless a rounding mode is given.
- `whereContains`, `whereStartsWith` and `whereEndsWith` on the query builder and the model builder,
  with `or`, `Not` and `orNot` forms and a `caseSensitive` flag. The search value is always literal.
- `DriverException.error()`, which returns a `DiwanError` with a stable `DWN-` code, the constraint
  or index name (`key()`) and the columns (`columns()`).
- `TransactionOptions` with `lockWait(Duration)`, `attempts(int)` and `withLockWait` / `withAttempts`,
  and `Connection.transaction(callback, options)`. A lock wait applies to the outermost transaction
  only: PostgreSQL runs `SET LOCAL lock_timeout` after `BEGIN`, and SQLite and Android set
  `busy_timeout` before `BEGIN` and restore it afterwards. A timeout is a concurrency failure, so
  attempts retry it.
- `lock(LockStrength, Duration)` on the query builder and the model builder, a timed row lock for
  one statement on PostgreSQL.
- `lock_wait` connection option (`5s`, `500ms` or milliseconds), also the `busy_timeout` alias on
  SQLite and a `PRAGMA` on Android.
- `sumDecimal(column, scale)` and `sumDecimal(column, scale, RoundingMode)` on the query builder and
  the model builder.
- Driver SPI: `DriverSession` lock-wait methods and `LockWaitGuard`, and `Driver.exactDecimalDigits()`,
  all with defaults, so an existing driver keeps compiling.
- `DriverFailureKind.CHECK_VIOLATION` and `NOT_NULL_VIOLATION`, with the codes `DWN-CST-0003` and
  `DWN-CST-0004`.

- Testing module `org.aristonis:diwan-database-testing`: the `RefreshDatabase` JUnit 5 extension
  (`rollback` and `truncate` modes, migrations once per run) and `DatabaseAssertions` (`assertHas`,
  `assertMissing`, `assertCount`, `assertEmpty`, `assertModelExists`, `assertModelMissing`,
  `assertSoftDeleted`, `assertNotSoftDeleted`).
- `Schema.getTables()`, `getTableListing()`, `getViews()`, `dropAllTables()` and `dropAllViews()`,
  with the `TableInfo` and `ViewInfo` values, and `Migrator.fresh()`.
- `QueryFactory.truncate(List)` and `truncate(List, boolean cascade)`: several tables in one call,
  native on PostgreSQL, emulated on SQLite and Android.
- `Connection.beginJoinedTransaction`, an `@Internal` hook the testing module uses.

### Fixed

- SQLite `truncate` failed when the `sqlite_sequence` table did not exist.

### Changed

- Driver failure messages and query exception messages end with the Diwan error code, such as
  `[DWN-CST-0001 unique violation: users_email_unique (email)]`. The rest of each message is
  unchanged.
- The strict decimal cast refuses a value with more than 15 significant digits on SQLite, where a
  decimal column is a double. The `InvalidCastException` names the column, not the value. For
  longer exact values use a text column with `Casts.decimalText(precision, scale)`. PostgreSQL is
  unchanged.
- `Connection.transaction(callback, int)` is now a default method that delegates to
  `transaction(callback, TransactionOptions.attempts(n))`.

### Known limitations

- On PostgreSQL the `lock_wait` option bounds every lock wait of the session, including migration
  DDL; the migration lock itself is exempt. PgBouncer transaction pooling may reject the `options`
  startup parameter, so use `TransactionOptions.lockWait` there.
- A transaction opened outside Diwan gets no lock wait.
- A timed lock on a locking cursor bounds only the opening of the cursor.
- On SQLite `sumDecimal` is a double rounded to the scale: approximate for very large sums.
- `RefreshDatabase` rollback mode: work on other threads, executors and `@Timeout(SEPARATE_THREAD)`
  commits for real, and `afterCommit` callbacks never fire. Truncate mode must not run under parallel
  JUnit and does not empty tables a test creates itself.
- `dropAllTables` on PostgreSQL leaves types, sequences and functions. On SQLite before 3.37 the
  table listing is built from `sqlite_master`.

## [0.2.0] - 2026-11-08

### Added

- `Collation` and `ColumnDefinition.collation(...)`, on PostgreSQL and SQLite, for create, add and
  change. `Collation.BINARY` is a portable byte order that each grammar maps to its own name, and
  `Collation.of(name)` names a database collation.
- `constrained(table, column, indexName)` and `references(column, indexName)` to name a foreign key.
- `fillAndInsert`, `fillAndInsertOrIgnore` and `fillAndInsertGetId` on the model builder.
- `whereBelongsTo` and `orWhereBelongsTo` on the model builder.
- Keyed `pluck(column, key)` on the model builder, which returns a map.
- `Casts.instant()`, a cast that stores an `Instant` whatever the model's timestamp format.
- `transaction_mode` SQLite option, on JDBC and Android, choosing how a transaction begins:
  `deferred`, `immediate` or `exclusive`.

### Changed

- SQLite accepts the `NO_KEY_UPDATE` and `KEY_SHARE` row locks as no-ops.
- The SQLite table rebuild keeps a column's collation instead of refusing the table.
- `MigrationChecksumException` advises restoring the migration and adding a new one.
  `repairChecksums` is for recovery only.
- Driver failure messages name the recognised kinds and the SQLite result code, such as
  `Database failure (SQL state unknown, vendor code 5): concurrency, SQLITE_BUSY`.
- `busy_timeout` applies to SQLite file databases when it is set.
- A model whose instant timestamp columns carry the instant cast while it uses the TEXT timestamp
  format fails at first use, naming the column.

### Fixed

- `cursorPaginate` ordered by a date or time column. A cursor can now hold `LocalDate` and
  `LocalTime`.
- SQLite commit no longer starts a new transaction after committing. A commit that landed could be
  reported as busy under `immediate` mode.
- A commit failure that may have landed says "commit outcome unknown" in its message.

## [0.1.0] - 2026-11-07

The first release: a hand port of Laravel's `illuminate/database` to Java 17 and Android, with
Laravel's method names where they read well in Java. Where Diwan behaves differently from Laravel,
the entry says so; the full list is in the
[differences guide](https://aristonis.org/open-source/diwan-database/docs/main/differences-from-laravel).

### Added

#### Modules and platforms

- `diwan-database` (core), `diwan-database-sqlite`, `diwan-database-pgsql`,
  `diwan-database-eloquent` and `diwan-database-android`. No runtime dependencies.
- One Java 17 JAR, tested on JDK 17, 21 and 25, and checked against the Android 28 API.
- Databases: SQLite, PostgreSQL, and Android's own SQLite on Android 9 (API 28) and later.
- Java modules: `org.aristonis.diwan.database`, `.sqlite`, `.pgsql` and `.eloquent` ship a
  `module-info` that exports only the API; `internal` packages stay closed. The Android module is
  named `org.aristonis.diwan.database.android` in its manifest.
- Package layout: API packages for users, `event` for listeners and events, `exception` for
  failures, `*.grammar` for dialect grammars, and `internal` for implementation classes marked
  `@Internal`.

#### Connections and transactions

- `Connection` with Laravel's method names: `statement`, `affectingStatement`, `insert`, `update`,
  `delete`, `select`, `selectOne`, `scalar`, `unprepared`, `transaction`, `beginTransaction`,
  `pretend`, `transactionLevel`, `getName` and `getTablePrefix`, with bindings as varargs or a
  list. `selectOne` and `scalar` return `Optional`; rows are unmodifiable maps in select order.
- `ConnectionBuilder` from a `DataSource`, a `JdbcConnectionProvider` or any `Driver`, with table
  prefix, error-message, strict-transaction and cleanup-failure settings.
- `DatabaseManager` with configuration, so application code names a driver instead of importing a
  database module. Configuration comes from `MapConfigSource`, `PropertiesConfigSource`,
  `EnvConfigSource` or a `LayeredConfigSource` of them, with `${...}` interpolation.
- Nested transactions through savepoints, and `TransactionScope` for try-with-resources.
- Retry of the whole transaction on a deadlock, a serialization failure or a lock that is not
  available (PostgreSQL 55P03). A failed commit is never retried, and a connection lost during a
  commit reports `COMMIT_OUTCOME_UNKNOWN`.
- Rollback-only state once the database may have dropped the transaction; after-rollback
  callbacks run deepest first.
- Joining a transaction owned by someone else, such as Spring's, through a savepoint without ending
  it.
- Strict transactions, on by default: the outer connection refuses calls from inside its own
  transaction. `QueryFactory.on(Connection)`, `Schema.on(Connection)` and
  `ModelQuery.on(Connection)` carry a factory into the transaction; another kind of database is
  refused with `IllegalArgumentException`.
- `Connection.session` to hold one physical connection. A session whose rollback failed or whose
  connection was lost is discarded, not handed back.
- Pretend mode that records each statement as a `LoggedQuery`.
- Events: `listen` for executed and failed queries and for transaction begin, commit and rollback,
  the query log (`enableQueryLog`, `getQueryLog`), `totalQueryDuration` and
  `whenQueryingForLongerThan`; migration, manager and connection-established events.

#### Drivers

- JDBC adapter (`JdbcDriver`, `DataSourceConnectionProvider`) with failure classification per
  database family, plain Java values for large objects, arrays and dates, and correct handling of
  pools that hand out connections with auto-commit off.
- `AndroidSqliteDriver` over Android's `SQLiteDatabase`, with transactions, savepoints and the same
  failure kinds as JDBC.
- A driver interface (`Driver`, `DriverSession`, `DriverInfo`, `DriverCapability`,
  `DriverException`, `DriverFailureKind`) for drivers that do not use JDBC, and `DatabaseFamily` and
  `DatabaseVersion` to tell databases and versions apart.
- Dates read through `java.time` by column type. SQLite stores dates as `yyyy-MM-dd HH:mm:ss`, and
  `Instant` and `OffsetDateTime` as UTC with `+00:00`; `Blueprint.instant` stores moments as whole
  microseconds on SQLite and `timestamptz` on PostgreSQL. PostgreSQL `infinity` reads as `MAX` /
  `MIN`.

#### Errors

- `DatabaseException`, the parent of every database, driver, migration and model failure, and
  `EloquentException` under it for models. All exceptions live in
  `org.aristonis.diwan.database.exception` and `org.aristonis.diwan.database.eloquent.exception`.
  Mistakes in the calling code keep the JDK's `IllegalArgumentException` and
  `IllegalStateException`.
- `QueryException`, `UniqueConstraintViolationException`, `ForeignKeyViolationException` and
  `MultipleColumnsSelectedException`. The message shows the database's reason and vendor code;
  binding values are hidden by default (`showBindingsInErrors` to show them), and messages are
  escaped and shortened.
- NaN and infinite numbers are refused before any SQL.

#### Schema builder

- `Schema` and `Blueprint`: `create`, `table`, `drop`, `dropIfExists`, `rename`, `hasTable`,
  `hasColumn`, `indexes`, `foreignKeys`, `withoutForeignKeyConstraints`, `statement` and `pretend`.
- Every common Laravel column type, the `unsigned*` and `*Increments` forms, `uuid`, `ulid`,
  `jsonb`, `enumColumn`, `set`, and the helpers `timestamps`, `timestampsTz`, `softDeletes`,
  `rememberToken`, `foreignId`, `foreignUuid`, `foreignUlid` and the `morphs` family.
- Modifiers `nullable`, `defaultValue`, `useCurrent`, `unique`, `index`, `primary`,
  `autoIncrement`, `unsigned`, `comment`, `precision`, `virtualAs` and `storedAs`, and check
  constraints with `check` and `dropCheck`.
- Indexes and keys: `index`, `unique`, `primary`, `foreign` and `foreignId().constrained()`, their
  drops, and `renameIndex`, `dropColumn` and `renameColumn`. A column still used by an index or key
  is refused.
- `ColumnDefinition.change()` redefines a column in full, as in Laravel 11 and later.
- On SQLite, key and column changes run as a table rebuild that keeps rows, indexes, checks,
  generated columns and the auto-increment counter.

#### Migrations

- `Migration` contract and `Migrator` with `run`, `rollback`, `rollbackSteps`, `rollbackBatch`,
  `reset`, `status` and pretend forms; one transaction per migration with its record inside it
  where the database allows; named connections.
- The migrations table in Laravel's layout, through `MigrationRepository`.
- `PostgresMigrationLock` on an advisory lock, and a `MigrationLock` hook for others.
- Optional checksums: `Migration.checksum()` and `MigrationChecksum.of(String)`. A run stops
  before anything runs when a recorded and a declared checksum differ;
  `Migrator.repairChecksums(List)` and `clearChecksums()` fix the records.
- Unlike Laravel, rollback and reset refuse when a record has no migration in the migrator
  (`MissingMigrationsException`); `allowMissingOnRollback()` restores Laravel's behaviour.

#### Seeding

- `Seeder`, `SeedRunner` with `run` and `call`, `SeedContext`, `SeedOptions` and `SeederListener`.

#### Query builder

- `QueryFactory` and `Builder` with Laravel's names for select, where (nested, `in`, `null`,
  `between`, `like`, dates, `whereInstantBetween`, `whereIntegerInRaw`, `whereRowValues`), order,
  group, having, limit, aggregates, insert, `insertGetId`, update, increment, delete and truncate;
  `toSql`, `getBindings` and `toRawSql`.
- Joins, subqueries and unions: every join kind and `JoinClause`, `fromSub`, `selectSub`,
  subqueries in where and order, `union` and `unionAll`, `insertUsing`, and update or delete through
  a join.
- `insertOrIgnore`, `upsert` and `updateOrInsert`.
- Row locks: `lockForUpdate()`, `sharedLock()` and `lock(LockStrength[, LockWait])`. SQLite ignores
  the plain locks and refuses the PostgreSQL-only ones.
- Chunking and streaming: `chunk`, `each`, `chunkById`, `lazy`, `lazyById` and the descending
  forms, and `cursor()` returning a `QueryCursor` that streams and must be closed.
- Pagination: `paginate`, `simplePaginate` and `cursorPaginate`. The count of a `distinct` query
  counts distinct rows (Laravel counts every row).

#### Models

- `Model` configured by overridable methods, with a static `ModelQuery` entry per model built from a
  constructor reference: no reflection, so it works with R8 on Android.
- `EloquentBuilder` with Laravel's finders, creators, chunking, cursors and pages returning models,
  and `ModelCollection`.
- Dirty tracking, fillable and guarded lists, timestamps, and UUID version 7 and ULID keys.
- Scopes: local scopes and global scopes with `addGlobalScope`, `withoutGlobalScope` and
  `withoutGlobalScopes`.
- Soft deletes with `withTrashed`, `onlyTrashed`, `withoutTrashed`, `restore` and `forceDelete`.
- Model events (`retrieved`, `creating`, `created`, `updating`, `updated`, `saving`, `saved`,
  `deleting`, `deleted`, `restoring`, `restored`, `forceDeleting`, `forceDeleted`, `replicating`),
  `ModelObserver`, and `afterCommit` to run a listener once the transaction commits.
- Casts: `int`, `float`, `decimal:N`, `string`, `bool`, `date`, `datetime`, `timestamp`, `json`,
  `array`, enums, the `immutable_*` aliases and user `Cast` classes; `mergeCasts` and `withCasts`.
  Bad stored data throws `InvalidCastException`, where Laravel reads `null`, `0` or `true`.
- Accessors and mutators with `Attribute`, and `toMap()` / `toJson()` with `hidden`, `visible` and
  `appends`.
- `JsonCodec` with a strict built-in RFC 8259 codec; Jackson or Gson can plug in without a library
  dependency.
- `touches()`, `touchOwners()`, `withoutTouching` and `push()`.

#### Relations

- `hasOne`, `hasMany`, `belongsTo` and `belongsToMany` with `Pivot`, `withPivot`, `wherePivot`,
  `withTimestamps` and `using`.
- Many-to-many writes: `attach`, `detach`, `sync`, `syncWithoutDetaching`, `syncWithPivotValues`,
  `toggle` and `updateExistingPivot`, with `SyncResult`.
- Through relations: `hasOneThrough` and `hasManyThrough`.
- Polymorphic relations: `morphTo`, `morphOne`, `morphMany`, `morphToMany` and `morphedByMany`,
  the `MorphMap`, morph queries and per-type eager loading options.
- One-of-many relations: `latestOfMany`, `oldestOfMany` and `ofMany`.
- Eager loading with `with`, `load` and `loadMissing`, with dot paths, column lists and constraints;
  `preventLazyLoading`.
- Existence filters `has`, `whereHas`, `doesntHave`, `whereRelation` and their `or` forms.
- Aggregates `withCount`, `withSum`, `withAvg`, `withMin`, `withMax`, `withExists` and the `load*`
  forms for models already fetched.
- Writes through a relation: `save`, `create`, `associate`, `dissociate` and `withDefault`;
  `chaperone()` links children to their parent without a query.
- Unlike Laravel, eager loads drop null keys, split long key lists to stay under the parameter
  limits, and refuse an inexact integer key instead of truncating it.

### Security

- Binding values are hidden from error messages by default.
- Every value is bound as a parameter; `whereIntegerInRaw` refuses anything that is not exactly an
  integer.
- `cursorPaginate` quotes an order column as a name; only a select alias is written as an
  expression.

### Known limitations

- `upsert` needs SQLite 3.24 or later (Android API 30 and later); a right join needs SQLite 3.39.
- `updateOrInsert` reads, then writes, and is not atomic.
- On SQLite a `decimal()` column turns values into floating-point numbers; store exact amounts as
  integer minor units.

[Unreleased]: https://github.com/aristonis/diwan-database/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/aristonis/diwan-database/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/aristonis/diwan-database/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/aristonis/diwan-database/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/aristonis/diwan-database/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/aristonis/diwan-database/releases/tag/v0.1.0
