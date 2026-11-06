# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - unreleased

### Added

- `QueryFactory.on(Connection)` and `Schema.on(Connection)`: the same factory or schema builder bound to
  another connection of the same database, such as a transaction's. Another kind of database is
  refused with `IllegalArgumentException`.
- Row locks on the query builder and the model query: `lockForUpdate()`, `sharedLock()` and
  `lock(LockStrength[, LockWait])`, with `UPDATE`, `NO_KEY_UPDATE`, `SHARE` and `KEY_SHARE`, and
  `WAIT`, `NOWAIT` and `SKIP_LOCKED`. PostgreSQL writes `for update`, `for share` and the others
  after the offset; SQLite ignores the plain locks and refuses the PostgreSQL-only ones. A locked
  select outside a transaction on PostgreSQL throws `IllegalStateException`. Aggregates, `exists`
  and the pagination count leave the lock out; a union with a lock is refused. Unlike Laravel,
  there is no raw string lock.
- `insertOrIgnore`, `upsert` and `updateOrInsert` on the query builder, and `upsert` and
  `insertOrIgnore` on the model query. SQLite writes `insert or ignore` and
  `on conflict (...) do update set "c" = "excluded"."c"`; PostgreSQL writes `on conflict do nothing`
  and the same upsert form. `upsert` takes the unique columns and an optional list of columns or
  map of column to value. Unlike Laravel, an empty update list leaves a conflicting row as it is.
  `upsert` is refused with `UnsupportedOperationException` on SQLite older than 3.24 (Android
  before API 30). `updateOrInsert` reads, then writes, and is not atomic. Model upserts fill
  unique ids, one timestamp for all rows and `updated_at` in the updated columns, and run no events.
- Eloquent models in a new module, `diwan-database-eloquent`: `Model` configured by overridable
  methods, a static `ModelQuery` entry per model built from a constructor reference (no
  reflection, works on Android), `EloquentBuilder` with Laravel's finders, creators, chunking,
  cursors and pages returning models, `ModelCollection`, `ModelNotFoundException` and
  `MassAssignmentException` with Laravel's messages. Dirty tracking, fillable and guarded lists,
  timestamps as Laravel's UTC text or as instants, UUID version 7 and ULID keys, connection
  resolution by name and `ModelQuery.on(Connection)` for transactions. Unlike Laravel, `delete()`
  on a model not in the database returns `false`, `increment` on such a model is refused, and
  `saveOrFail()` throws when there is nothing to insert.
- Attribute casts for models, declared in `casts()` by Laravel's string form (`decimal:2`,
  `datetime:yyyy-MM-dd`), by the `Casts` factories or `CastMap`, or by a user `Cast` with optional
  `CastSerializer` and `CastComparator`: `int`, `float`, `decimal:N`, `string`, `bool`, `date`,
  `datetime`, `timestamp`, `json`, `array`, `json:unicode`, enums and the `immutable_*` aliases.
  `getRawAttribute`, `getRawOriginal`, `getCasts` and `hasCast`. Bad stored data throws
  `InvalidCastException` naming the model, key and cast, where Laravel reads `null`, `0` or `true`.
  Date formats are Java `DateTimeFormatter` patterns, not PHP letters.
- A dirty check that knows the cast. Unlike Laravel, JSON compares by structure, floats treat NaN
  as equal to NaN and allow 4 ULP, moments compare as instants, `date` compares as a date, every
  cast stores its storage form, reading a cast object never dirties the model, and a cast name
  keeps the case of its format. Decimals still compare after rounding, as in Laravel.
- Accessors and mutators registered in `accessors()` with `Attribute`, without reflection; the
  accessor cache is cleared on any write.
- `toMap()` and `toJson()` with `hidden`, `visible` and `appends`, and per-instance `makeHidden`,
  `makeVisible` and `append`. Moments serialize as ISO-8601 UTC with microseconds.
- `JsonCodec` with a strict built-in RFC 8259 codec that keeps numbers exact; a model can plug in
  Jackson or Gson through `jsonCodec()` without a library dependency.
- Model relations in `eloquent.relations`: `HasOne`, `HasMany` and `BelongsTo`, declared by a
  typed method and a `RelationMap` returned from `relations()`, checked once per model class
  without reflection (`RelationNotFoundException`, `InvalidRelationException`). Lazy reads with
  `getRelationValue`, `related` and `relatedMany`; `getRelation`, `setRelation`,
  `unsetRelation`, `relationLoaded` and `withoutRelations`. Eager loading with `with`, `without`,
  `withOnly`, `load` and `loadMissing` on builders, models and collections, with dot paths,
  column lists and constraints, also per chunk, page and `lazy()` page. Existence filters `has`,
  `orHas`, `doesntHave`, `orDoesntHave`, `whereHas`, `orWhereHas`, `whereDoesntHave`,
  `orWhereDoesntHave`, `whereRelation`, `orWhereRelation` and `whereGroup`. Writes through a
  relation (`make`, `save`, `saveMany`, `create`, `createMany`, `associate`, `dissociate`) and
  `withDefault`. Loaded relations appear in `toMap()` and `toJson()` under snake-case keys, and a
  cycle ends. `ModelQuery.preventLazyLoading` refuses lazy reads on models from multi-row results
  with `LazyLoadingViolationException`; `refresh()` reloads loaded relations.
- Unlike Laravel, relations: drop null keys from eager loads (Laravel can read them as `0` and
  fetch orphan rows); split long eager key lists to stay under SQLite's and PostgreSQL's parameter
  limits; refuse an inexact integer key instead of truncating it; number self-join aliases per
  query, so the SQL is the same every time; refuse an eager constraint with `limit` or `offset`;
  add the matching key to a `with("posts:id,title")` column list; refuse a write through a
  relation whose parent has no key; require the foreign key of `belongsTo`; never lazy load from
  `getAttribute`; run both constraints when `with` names the same relation twice; and always
  group a `whereHas` constraint. `cursor()` with eager loads is refused; use `lazy()`.
- `whereIntegerInRaw` on the query builder: integer values written inline, anything that is not
  exactly an integer refused with `IllegalArgumentException`.
- Query builder chunking, streaming and pagination: `chunk`, `each`, `chunkMap`, `chunkById`,
  `chunkByIdDesc`, `eachById`, `lazy`, `lazyById`, `lazyByIdDesc`; `cursor()` returning a
  `QueryCursor` that streams on SQLite, PostgreSQL and Android and must be closed; `paginate`
  (`LengthAwarePage`), `simplePaginate` (`SimplePage`), `cursorPaginate` (`CursorPage` with a
  `Cursor` token) and `getCountForPagination`. Chunking runs on copies, so the builder is
  unchanged. `DriverSession.cursor`, `Connection.cursor` and
  `DriverCapability.STREAMS_IN_TRANSACTION` for drivers that stream only inside a transaction.
- While a cursor is open in a session or transaction, any other statement there is refused with
  `IllegalStateException` until it is closed.
- The pagination count of a `distinct` query counts distinct rows (Laravel counts every row).
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
  `Blueprint.instant` stores them.
- `ForeignKeyViolationException`, and the failure kinds `FOREIGN_KEY_VIOLATION`, `CANCELLED` and
  `CONNECTION_LOST`, and `COMMIT_OUTCOME_UNKNOWN` for a connection lost during a commit, which may or
  may not have landed; a lock that is not available (PostgreSQL 55P03) counts as a concurrency
  failure and is retried.
- Package `values` with `Instants`; `Blueprint.instant` stores moments as whole microseconds on SQLite,
  checked to be integers, and `timestamptz` on PostgreSQL. `Instants.now()` is truncated to
  microseconds.

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
- Relation aggregates on builders: `withCount`, `withMax`, `withMin`, `withSum`, `withAvg`,
  `withExists` and `withAggregate`, with constraints, `as` aliases and self relations; `exists`
  aliases read as booleans. The same aggregates load onto models and collections that are already
  fetched with `loadCount`, `loadMax`, `loadMin`, `loadSum`, `loadAvg`, `loadExists` and
  `loadAggregate`, in one key-only query. Unlike Laravel, a model whose row is gone is skipped and
  an empty result changes nothing, where Laravel fails on a null.
- A per-model cast overlay with `mergeCasts`, and `withCasts` on the builder to cast selected
  columns for one query.
- Owner timestamps: `touches()` names the relations to touch after a save, `touchOwners()` touches
  them on demand, and `withoutTouching` suspends touching for a model class within a block.
- `push()` saves a model and every loaded relation, depth first, each model once even through a
  cycle. As in Laravel, a loaded `withDefault` placeholder is inserted.
- `chaperone()` (alias `inverse()`) on `hasOne` and `hasMany` sets the parent on each loaded child
  without a query.
- Many-to-many relations, read side: `belongsToMany` declares the relation with Laravel's joining
  table and key names, and each related row carries its joining row as a `Pivot` under `pivot` (or
  the name given by `as`), read with `Pivot.of`. Joining columns are chosen with `withPivot`,
  `withTimestamps` and `as`, a joining class with `using`, and the joining table is filtered and
  sorted with `wherePivot`, `orderByPivot` and `withPivotValue`. Lazy and eager reads, `has`,
  `whereHas`, `withCount` and the other aggregates, `load*`, the finders, iteration and the three
  page kinds work on the relation, and `chaperone` links a joining row to both sides when a joining
  class is given. Unlike Laravel, a read never changes the relation, a related column named with
  the `pivot_` prefix is moved into the joining row, `cursorPaginate` refuses an order on a
  joining-table column, and a joining row with no key refuses `update` and `delete`. Writes through
  the relation (`attach`, `detach`, `sync`) are not available yet.
- Migration checksums. `Migration.checksum()` (empty by default) lets a migration declare a value
  that is recorded when it runs and compared on later runs; `MigrationChecksum.of(String)` gives
  the SHA-256 of a text with line endings and Unicode form normalized. A run (and a pretend run)
  stops before anything runs with a `MigrationChecksumException` that lists every migration whose
  recorded and declared checksums differ. A migration with no checksum on either side is never
  checked, and one that starts declaring a checksum gets it recorded on the next run.
  `Migrator.repairChecksums(List)` accepts the current values and `clearChecksums()` removes them,
  both under the migration lock. The migrations table gains a nullable `checksum varchar(64)`
  column, `MigrationEntry.checksum()`, and `MigrationRepository.log` takes the checksum.

- `Builder.cursor()` and `Connection.cursor(...)` return the public `QueryCursor<Map<String, Object>>`,
  and `EloquentBuilder.cursor()` returns `QueryCursor<T>`. A `QueryCursor` is `Iterable` and
  `AutoCloseable`: `iterator()` works once (a second call throws `IllegalStateException`),
  `stream()` is lazy and closing the stream closes the cursor, and `map(fn)` gives a cursor of the
  mapped type that shares the close. A stream releases its connection only when it is closed, so
  use it in try-with-resources.
- SQLite stores dates as `yyyy-MM-dd HH:mm:ss` (a fraction only when there is one) on both the JDBC
  and Android drivers, and `Instant` and `OffsetDateTime` as UTC with `+00:00`. Years outside 0000
  to 9999 are refused on SQLite.
- `has` and `whereHas` wrap the wheres of a closure in brackets only when they contain an `or`.
  The exists form drops orders from the subquery; the count form replaces the selected columns
  with `count(*)` and refuses `distinct` and unions. `withCount`, `withExists`, `withSum`,
  `withAvg`, `withMin` and `withMax` closures follow the rules of `whereHas`: joins are allowed,
  columns, orders, limit and offset are cleared, and `groupBy`, `having`, `distinct` and unions
  throw `IllegalArgumentException`.
- Relation existence and aggregate queries on a table with an alias (`from("users as u")`)
  correlate on the alias.
- `cursorPaginate` quotes an order column that contains parentheses as a name; only a select alias
  is written as an expression.
- A statement given more values than placeholders fails with a `QueryException` on SQLite.
- `touch()` returns `false` and saves nothing when the model has no `updated_at` column;
  `increment` and `decrement` write their extra columns through the model's casts; after-rollback
  callbacks run deepest first.
- PostgreSQL identifier limits are counted in UTF-8 bytes, an empty comment removes a column
  comment, statements that cannot run in a transaction name `withinTransaction()` in the error,
  and the migration lock times its wait with a monotonic clock.
- SQLite: `unprepared` runs every statement of a script, on JDBC and Android. `BigDecimal` is bound
  in plain form, never `1E+3`. A `decimal()` column still has numeric affinity, which turns the
  text into a floating-point number; use an integer column of minor units for exact amounts.
- Dates read through `java.time` by column type: dates before 1582, times in a daylight-saving gap
  and `timestamptz` offsets read back unchanged; PostgreSQL `infinity` reads as `MAX` / `MIN`.
- PostgreSQL: text binds into `uuid`, `jsonb` and `inet`, nulls into any column; `json` and `inet`
  read back as text.
- NaN and infinite numbers are refused before any SQL. A session whose rollback failed or whose
  connection was lost is discarded, not handed back.
