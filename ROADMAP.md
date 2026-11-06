# Roadmap

What comes after 0.1.0. Order and scope can change; an item a user asks for moves up. To ask for
one, open a [feature request](https://github.com/aristonis/diwan-database/issues/new/choose).

| Status | Meaning |
|---|---|
| Next | Planned for the next releases. |
| Planned | Wanted, not scheduled yet. |

## Release and tooling

| Feature | Description | Status |
|---|---|---|
| Migration CLI | `migrate`, `rollback`, `reset`, `refresh`, `fresh`, `status` and `make:migration` from stubs. | Next |
| Build-time migration discovery | An annotation processor finds migration classes at compile time and fixes their names before R8 renames anything. | Next |
| Annotations for relations and scopes | Declare relations and scopes with annotations; the same processor generates the registration, with no reflection at run time. | Next |
| Pool wiring recipe | A documented HikariCP and Agroal setup for the database manager. | Planned |
| Java 21 overlays | Pattern matching in the grammars through a multi-release JAR, while Java 17 stays supported. | Planned |

## Models

| Feature | Description | Status |
|---|---|---|
| Factories | Model factories with states and relations, for test data. | Next |
| Collection and encrypted casts | `AsCollection`, `AsArrayObject` and encrypted casts with a key provider. | Planned |
| Hashed and binary casts | A hashed cast for passwords, and binary UUID and ULID casts. | Planned |
| Model collection helpers | `toQuery`, `only`, `except`, `diff`, `intersect` and `unique` on model collections. | Planned |
| Pruning | Prunable and mass-prunable models for scheduled cleanup. | Planned |
| Automatic eager loading | Batched loading of relations, to avoid N+1 queries without the strict guard. | Planned |
| Relation query helpers | `withWhereHas`, `whereBelongsTo` and `whereAttachedTo`. | Planned |
| Many-to-many through | Through relations across a pivot table. | Planned |
| Relation and model extras | Smaller Laravel methods such as `incrementEach`, `valueOrFail`, `restoreOrCreate` and `withAttributes`. | Planned |

## Query builder and connections

| Feature | Description | Status |
|---|---|---|
| JSON wheres | `->` paths, `whereJsonContains` and `whereJsonLength`, PostgreSQL first. | Planned |
| Full-text, lateral join, update from | `whereFullText`, `joinLateral` and `updateFrom`. | Planned |
| Builder helpers | `inOrderOf`, the `wherePast` / `whereFuture` / `whereToday` family, `sole`, `findOr` and index hints. | Planned |
| Query hooks and explain | `beforeQuery`, `afterQuery` and `explain`. | Planned |
| Transaction isolation and read-only | An isolation level and a read-only flag per transaction. | Planned |
| Retry after a lost connection | Run a statement again once after a lost connection outside a transaction. | Planned |
| Statement and busy events | Statement-prepared and database-busy events, and queued listeners. | Planned |
| Read and write connections | Separate read and write hosts, with sticky writes. | Planned |

## Schema and migrations

| Feature | Description | Status |
|---|---|---|
| Schema inspection | `getColumns`, `getTables`, `getViews`, `hasIndex` and `hasColumns`. | Planned |
| Blueprint extras | `foreignIdFor`, temporary tables, `rawIndex` and table comments. | Planned |
| Spatial, vector and computed columns | `geometry`, `geography`, `vector` and computed columns, with full-text and spatial indexes. | Planned |
| Schema dump and load | Dump the schema and load it before running migrations, for projects with a long history. | Planned |
| Typed schema | Generate typed table and column constants from the migrations, so a wrong column name fails at compile time. | Planned |
| Schema diff | Compare a live database with the migrations and generate the missing migration. | Planned |

## Databases

| Feature | Description | Status |
|---|---|---|
| MySQL and MariaDB | Connection, schema and query grammars, table options. | Planned |
| SQL Server | Connection, schema and query grammars. | Planned |
