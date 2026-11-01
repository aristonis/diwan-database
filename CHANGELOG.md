# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - unreleased

### Added

- Query builder joins, subqueries and unions: `join`, `leftJoin`, `rightJoin`, `crossJoin`, their
  `Where` and `Sub` forms and `JoinClause`; `fromSub`, `fromRaw`, `selectSub`, subqueries in
  `where`, `whereIn`, `whereExists` and `orderBy`, and `whereRowValues`; `union` and `unionAll`
  with order, limit and aggregates over the whole union; `insertUsing`, `insertOrIgnoreUsing`, an
  update with a subquery value, and update or delete through a join. A subquery is copied when
  attached, so its SQL and values never drift apart. A right join needs SQLite 3.39 or later.
- A write on a query that reads from a subquery, or has no table, is refused with
  `IllegalStateException` before any SQL is compiled.
- Query builder: `QueryFactory` and `Builder` with Laravel's names for select, where (including
  nested, `in`, `null`, `between`, `like`, date and `whereInstantBetween`), order, group, having,
  limit, reads, aggregates, insert, `insertGetId`, update, increment, delete and truncate;
  `toSql`, `getBindings` and `toRawSql`. Grammars and processors ship in the database modules:
  `SQLiteQuery.of` (also for Android) and `PostgresQuery.of`. Instants are stored as
  `Blueprint.instant` stores them; money is bound explicitly.
- `ForeignKeyViolationException`, and the failure kinds `FOREIGN_KEY_VIOLATION`, `CANCELLED` and
  `CONNECTION_LOST`, and `COMMIT_OUTCOME_UNKNOWN` for a connection lost during a commit, which may or
  may not have landed; a lock that is not available (PostgreSQL 55P03) counts as a concurrency
  failure and is retried.
- Package `values` with `Money`, `MoneyAmount` (reads a money column the same way on SQLite and
  PostgreSQL) and `Instants`; `Blueprint.instant` stores moments as whole microseconds on SQLite,
  checked to be integers, and `timestamptz` on PostgreSQL. `Instants.now()` is truncated to
  microseconds.

- Money: `Blueprint.money(column, scale)` for whole minor units, `asDecimal(precision)` for an exact
  decimal (PostgreSQL `numeric`, checked text on SQLite), and the `Money` converters.

- Android module `diwan-database-android`: `AndroidSqliteDriver` over Android's `SQLiteDatabase`
  for Android 9 and later, with transactions, savepoints and the JDBC driver's failure kinds.
- PostgreSQL module `diwan-database-pgsql`: `PostgresSchemaGrammar` with Laravel's PostgreSQL types
  and modifiers, every blueprint command including `change()`, schema reads, and
  `PostgresMigrationLock` on an advisory lock. `DatabaseVersion` compares database versions for
  grammars.
- Check constraints: `ColumnDefinition.check`, `Blueprint.check(name, sql)` and `dropCheck`.
  Generated columns: `virtualAs` and `storedAs`. `Blueprint.rawColumn`, `Schema.statement` and
  `Schema.driverName` for SQL the builder cannot write. The SQLite table rebuild now carries checks
  and generated columns instead of refusing the table.
- `ColumnDefinition.change()` redefines an existing column, as in Laravel 11 and later: the new
  definition replaces the old one in full. `unique(false)`, `index(false)` and `primary(false)`
  drop the column's conventional index. On SQLite the change runs as a table rebuild.
- Every common Laravel column type: tiny, small and medium integers and `unsigned*` forms,
  `*Increments`, `floatColumn`, `doubleColumn`, `charColumn`, `tinyText`, `mediumText`,
  `longText`, `dateTime`, `dateTimeTz`, `time`, `timeTz`, `timestampTz`, `year`, `binary`, `uuid`,
  `ulid`, `ipAddress`, `macAddress`, `jsonb`, `enumColumn`, `set`; precision on float, time and
  timestamp columns; `ColumnDefinition.precision`, `allowed` and `usesCurrent`.
- Helpers: `timestampsTz`, `nullableTimestamps`, `datetimes`, `softDeletes` (three forms),
  `rememberToken`, `foreignUuid`, `foreignUlid`, the `morphs` family, and their drop helpers.
- SQLite: enum as a checked `varchar` that table rebuilds carry over.

- Indexes and keys: `Blueprint.index`, `unique`, `primary`, `foreign`, `foreignId` with
  `ForeignIdColumnDefinition.constrained`, `ForeignKeyDefinition` and `ForeignKeyAction`; the drops
  `dropIndex`, `dropUnique`, `dropPrimary`, `dropForeign`, `dropConstrainedForeignId`;
  `renameIndex`; column modifiers `index` and `primary`.
- `Blueprint.dropColumn` and `renameColumn`; a column still used by an index or key is refused.
- `Schema.indexes`, `foreignKeys` (`IndexInfo`, `ForeignKeyInfo`), `withoutForeignKeyConstraints`
  and `pretend`.
- SQLite table rebuild for key changes, keeping rows, indexes and the auto-increment counter;
  `ForeignKeyCheckException`.
- `Connection.session` to hold one physical connection; `DriverSession.discard`.

- `MissingMigrationsException`: rollback and reset refuse when records have no migration in the
  migrator; `Migrator.Builder.allowMissingOnRollback()` restores Laravel's behaviour.
- `DriverException.databaseMessage()`; `QueryException` shows the database's reason with the values
  (`showBindingsInErrors`) and always shows the vendor code.
- `Migrator`: `run`, `run(step)`, `rollback`, `rollbackSteps`, `rollbackBatch`, `reset`, `status`
  and pretend forms; one transaction per migration with its record inside it where the database
  allows; named connections; `MigrationLock` hook; `MigrationResult`, `MigrationStatus` and
  `MigrationFailedException`.
- Schema builder (`Schema`, `Blueprint`, `ColumnDefinition`): `create`, `table`, `drop`,
  `dropIfExists`, `rename`, `hasTable` and `hasColumn`, with the columns `id`, `string`, `text`,
  `integer`, `bigInteger`, `booleanColumn`, `decimal`, `date`, `timestamp`, `json` and
  `timestamps`, and the modifiers `nullable`, `defaultValue`, `useCurrent`, `unique`,
  `autoIncrement`, `unsigned` and `comment`.
- `SchemaGrammar` for database modules to extend, with `Command`, `Expression` and `SchemaQuery`.
- Module `diwan-database-sqlite` with `SQLiteSchemaGrammar` and `SQLiteSchema`.
- `Migration` contract: `name`, `up(Schema)`, `down(Schema)`, `withinTransaction`, `shouldRun` and `connection`.
- `MigrationRepository` and `DatabaseMigrationRepository`: the migrations table with Laravel's
  layout, operations and ordering, kept through the schema builder; `MigrationEntry` for its rows.
- `DatabaseFamily` to tell databases apart from the driver's product name.
- `Connection.getDriverInfo()`.
- `Connection` with Laravel's method names: `statement`, `affectingStatement`, `insert`, `update`,
  `delete`, `select`, `selectOne`, `scalar`, `unprepared`, `transaction`, `beginTransaction`,
  `pretend`, `transactionLevel`, `getName` and `getTablePrefix`, with bindings as varargs or a list.
- `ConnectionBuilder` from a `DataSource`, a `JdbcConnectionProvider` or any `Driver`, with table
  prefix, error-message, strict-transaction and cleanup-failure settings.
- `selectOne` and `scalar` return `Optional`; rows are unmodifiable maps in select order.
- Nested transactions through savepoints, and `TransactionScope` for try-with-resources.
- Retry of the whole transaction on a deadlock or serialization failure raised on its own
  connection; a failed commit is never retried.
- Rollback-only state once the database may have dropped the transaction.
- Joining a transaction owned by someone else through a savepoint, without ending it.
- Strict transactions, on by default: the outer connection refuses calls from inside its own
  transaction.
- Pretend mode that records each statement as a `LoggedQuery`.
- `QueryException`, `UniqueConstraintViolationException` and `MultipleColumnsSelectedException`;
  binding values hidden from messages by default, messages escaped and shortened.
- Driver interface (`Driver`, `DriverSession`, `DriverInfo`, `DriverCapability`,
  `DriverException`, `DriverFailureKind`) for drivers that do not use JDBC.
- JDBC adapter (`JdbcDriver`, `DataSourceConnectionProvider`) with failure classification per
  database family, plain Java values for large objects, arrays and dates, and correct handling of
  pools that hand out connections with auto-commit off.
- Build: one Java 17 JAR tested on JDK 17, 21 and 25, checked against the Android 28 API, with
  80% line coverage and Javadoc under `-Xdoclint:all` enforced.

### Changed

- SQLite: dates are stored as `yyyy-MM-dd HH:mm:ss` (a fraction only when there is one) by both
  the JDBC and Android drivers; `Instant` and `OffsetDateTime` as UTC with `+00:00`. Text written by
  earlier builds (`2026-01-02T03:04`, local offsets) does not sort or compare with the new text.
  Years outside 0000 to 9999 are refused on SQLite.

### Fixed

- PostgreSQL identifier limits are counted in UTF-8 bytes; catalog reads work in a mixed-case
  current schema; an empty comment removes a column comment.
- SQLite: `unprepared` runs every statement of a script, on JDBC and Android; renaming a decimal
  money column on SQLite before 3.25 no longer fails on its own check.
- PostgreSQL statements that cannot run in a transaction name `withinTransaction()` in the error.
- The PostgreSQL migration lock times its wait with a monotonic clock.

- Dates read through `java.time` by column type: dates before 1582, times in a daylight-saving gap
  and `timestamptz` offsets read back unchanged; PostgreSQL `infinity` reads as `MAX` / `MIN`.
- SQLite: `BigDecimal` is bound in plain form, never `1E+3`. A `decimal()` column still has numeric
  affinity, which turns the text into a floating-point number; use `money(...)` for exact amounts.
- PostgreSQL: text binds into `uuid`, `jsonb` and `inet`, nulls into any column; `json` and `inet`
  read back as text.
- NaN and infinite numbers are refused before any SQL.
- A session whose rollback failed or whose connection was lost is discarded, not handed back.
- SQLite: the foreign key check after a table rebuild used the table-valued
  `pragma_foreign_key_check(?)`, which SQLite before 3.26 (Android 9) rejects; it now uses the
  pragma statement.
