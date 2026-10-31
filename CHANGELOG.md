# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - unreleased

### Added

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
