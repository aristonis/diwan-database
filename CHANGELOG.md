# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - unreleased

### Added

- Schema builder (`Schema`, `Blueprint`, `ColumnDefinition`): `create`, `table`, `drop`,
  `dropIfExists`, `rename`, `hasTable` and `hasColumn`, with the columns `id`, `string`, `text`,
  `integer`, `bigInteger`, `booleanColumn`, `decimal`, `date`, `timestamp`, `json` and
  `timestamps`, and the modifiers `nullable`, `defaultValue`, `useCurrent`, `unique`,
  `autoIncrement`, `unsigned` and `comment`.
- `SchemaGrammar` for database modules to extend, with `Command`, `Expression` and `SchemaQuery`.
- Module `photisma-database-sqlite` with `SQLiteSchemaGrammar` and `SQLiteSchema`.
- `Migration` contract: `name`, `up`, `down`, `withinTransaction`, `shouldRun` and `connection`.
- `MigrationRepository` and `DatabaseMigrationRepository`: the migrations table with Laravel's
  operations and ordering, on SQLite, PostgreSQL and MySQL; `MigrationEntry` for its rows.
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
