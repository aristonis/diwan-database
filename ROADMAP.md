# Roadmap

What is planned after 0.3.0. Order and scope can change; an item a user asks for moves up. To ask
for one, open a [feature request](https://github.com/aristonis/diwan-database/issues/new/choose).

## Versions

| Version | Theme | Status |
|---|---|---|
| 0.1.0 | First release: connections, schema, migrations, query builder, models and relations on SQLite, PostgreSQL and Android. | Released 2026-11-07 |
| 0.2.0 | Fixes and Laravel ports from the first app reports. | Released 2026-11-08 |
| 0.3.0 | Strict decimals, escaped search, error codes, lock wait, testing module, schema listing. | Released 2026-11-09 |
| 0.4 | App fixes and model conveniences: builder, model and relation helpers, casts, factories, migration lock. | Planned |
| 0.5 | The command line: `diwan-database` with migrate, make and db commands. | Planned |
| 0.6 | Build-time annotations: migration discovery, relations, scopes, `@RefreshDatabase`. | Planned |
| 0.7 | Connections and queries: isolation, hooks, retries, events, read and write hosts, JSON and full-text wheres. | Planned |
| 0.8 | Advanced schema and loading: schema dump, Java 21, spatial columns, automatic eager loading. | Planned |
| Later | Not scheduled: typed schema, schema diff, MySQL, MariaDB and SQL Server. | Later |

## Release and tooling

| Feature | Description | Version |
|---|---|---|
| API reference site | One searchable API reference for all modules, published per version with a version switch. | 0.4 |
| Build-time migration discovery | An annotation processor finds migration classes at compile time and fixes their names before R8 renames anything. | 0.6 |
| Pool wiring recipe | A documented HikariCP and Agroal setup for the database manager. | 0.7 |
| Java 21 overlays | Pattern matching in the grammars through a multi-release JAR, while Java 17 stays supported. | 0.8 |

## CLI

The command is `diwan-database`, with the short alias `diwan-db`.

| Feature | Description | Version |
|---|---|---|
| Migration commands | `migrate`, `migrate:rollback`, `migrate:reset`, `migrate:refresh`, `migrate:fresh` and `migrate:status`. | 0.5 |
| Generators | `make:migration` from stubs (create, table, blank), plus `make:model`, `make:factory` and `make:seeder`. | 0.5 |
| Data commands | `db:seed`, `db:wipe` and `model:prune`. | 0.5 |
| Launcher and Gradle task | Launcher scripts, a Gradle task that runs with the project classpath, a release zip and install docs. | 0.5 |

## Models

| Feature | Description | Version |
|---|---|---|
| Strict decimal cast | `Casts.decimal` and `Casts.decimalText` refuse extra digits instead of rounding silently. | ✅ 0.3.0 |
| Factories | Model factories with states and relations, for test data. | 0.4 |
| Collection and encrypted casts | `AsCollection`, `AsArrayObject` and encrypted casts with a key provider. | 0.4 |
| Hashed and binary casts | A hashed cast for passwords, and binary UUID and ULID casts. | 0.4 |
| Model collection helpers | `toQuery`, `only`, `except`, `diff`, `intersect`, `unique` and `makeHidden` on model collections. | 0.4 |
| Pruning | Prunable and mass-prunable models for scheduled cleanup. | 0.4 |
| Relation query helpers | `withWhereHas` and `whereAttachedTo`. | 0.4 |
| Relation extras | `is` and `isNot` on relations, `withAttributes`, `incrementOrCreate`, `restoreOrCreate`, and `chunkMap` and `eachById` on relations. | 0.4 |
| Model extras | `incrementEach`, `decrementEach`, `valueOrFail`, `soleValue`, strict missing-attribute access and `hasNamedScope`. | 0.4 |
| Relation and scope annotations | `@HasOne`, `@HasMany`, `@BelongsTo`, `@BelongsToMany`, the morph and through annotations, and `@Scope`; the processor generates the registration, with no reflection at run time. | 0.6 |
| Thread-bound transactions | Model writes join the active transaction, and listeners can run after commit. | 0.7 |
| Many-to-many through | Through relations across a pivot table. | 0.8 |
| Automatic eager loading | Batched loading of relations, to avoid N+1 queries without the strict guard. | 0.8 |

## Query builder and connections

| Feature | Description | Version |
|---|---|---|
| Escaped search | `whereContains`, `whereStartsWith` and `whereEndsWith` treat the search value as literal text. | ✅ 0.3.0 |
| Exact decimal sums | An exact decimal column on every driver, and `sumDecimal` on the builder and the model builder. | ✅ 0.3.0 |
| Error codes and constraint names | Failures carry a stable `DWN-` code, the constraint or index name and the columns. | ✅ 0.3.0 |
| Lock wait | A lock wait timeout per transaction, a timed row lock, and the `lock_wait` connection option. | ✅ 0.3.0 |
| Truncate many tables | `truncate` accepts a list of tables, with an optional cascade. | ✅ 0.3.0 |
| Error table name | The failed table name on the error, when the driver reports it. | 0.4 |
| Builder helpers | `inOrderOf`, the `wherePast` / `whereFuture` / `whereToday` family, `sole`, `firstOrFail`, `findOr`, `existsOr`, `cloneWithout` and index hints. | 0.4 |
| Search options argument | One options argument for the escaped search methods. | 0.4 |
| Dates and times | Edge cases such as `timetz`, daylight saving time and infinity, and SQLite text storage gaps. | 0.4 |
| Android: deferred foreign key commit | A failed commit on a deferred foreign key rolls back cleanly. | 0.4 |
| JSON wheres | `->` paths, `whereJsonContains` and `whereJsonLength`, PostgreSQL first. | 0.7 |
| Full-text, lateral join, update from | `whereFullText`, `joinLateral` and `updateFrom`. | 0.7 |
| Query hooks and explain | `beforeQuery`, `afterQuery` and `explain`. | 0.7 |
| Query timeout | `queryTimeout(Duration)` on the builder. | 0.7 |
| Transaction isolation and read-only | An isolation level and a read-only flag per transaction. | 0.7 |
| Retry after a lost connection | Run a statement again once after a lost connection outside a transaction. | 0.7 |
| Retry backoff | A configurable, jittered wait between transaction retry attempts. | 0.7 |
| Statement and busy events | Statement-prepared and database-busy events, and queued listeners. | 0.7 |
| Read and write connections | Separate read and write hosts, with sticky writes. | 0.7 |

## Schema and migrations

| Feature | Description | Version |
|---|---|---|
| Schema listing and drop all | `getTables`, `getTableListing`, `getViews`, `dropAllTables`, `dropAllViews` and `Migrator.fresh()`. | ✅ 0.3.0 |
| Schema inspection | `getColumns`, `hasIndex` and `hasColumns`. | 0.4 |
| Blueprint extras | `foreignIdFor`, temporary tables, `rawIndex` and table comments. | 0.4 |
| Partial and descending indexes | An index `where` clause on PostgreSQL and SQLite, and SQLite rebuilds that keep index collation and `DESC`. | 0.4 |
| Migration lock | A database lock so two processes do not migrate at once. | 0.4 |
| Schema dump and load | Dump the schema and load it before running migrations, for projects with a long history. | 0.8 |
| Spatial, vector and computed columns | `geometry`, `geography`, `vector` and computed columns, with full-text and spatial indexes. | 0.8 |
| SQLite rebuild extras | A SQLite table rebuild keeps `CHECK` constraints, generated columns, triggers and views. | 0.8 |
| Version and name-length helpers | A database version-at-least check, and the PostgreSQL 63-byte limit check for names. | 0.8 |
| Typed schema | Generate typed table and column constants from the migrations, so a wrong column name fails at compile time. | Later |
| Schema diff | Compare a live database with the migrations and generate the missing migration. | Later |

## Testing

| Feature | Description | Version |
|---|---|---|
| Testing module | `RefreshDatabase` for JUnit 5 (migrate once per run, roll back or truncate per test) on every driver. | ✅ 0.3.0 |
| Database assertions | `assertHas`, `assertMissing`, `assertCount`, `assertEmpty`, `assertModelExists`, `assertModelMissing`, `assertSoftDeleted` and `assertNotSoftDeleted`. | ✅ 0.3.0 |
| `@RefreshDatabase` annotation | The same refresh through an annotation and a test config file, using build-time migration discovery. | 0.6 |

## Databases

| Feature | Description | Version |
|---|---|---|
| MySQL and MariaDB | Connection, schema and query grammars, table options. Built on request. | Later |
| SQL Server | Connection, schema and query grammars. Built on request. | Later |
