# 0011. PostgreSQL grammar

## Status

Accepted

## Context

The first application on Diwan Database runs its desktop edition on a local PostgreSQL and its
mobile edition on SQLite, from the same migrations. PostgreSQL can alter most of a table in place
and rolls back schema changes inside a transaction, so it needs none of the SQLite table rebuild.
Laravel's PostgreSQL grammar is the reference for the SQL it writes.

## Decision

1. A separate module, `diwan-database-pgsql`, holds `PostgresSchemaGrammar`, `PostgresSchema` and
   `PostgresMigrationLock`, like the SQLite module. It has no runtime dependency; the application
   brings the PostgreSQL JDBC driver.
2. Types, modifiers and their order follow Laravel's PostgreSQL grammar. Auto-increment columns are
   `serial`, `smallserial` or `bigserial` with an inline primary key; generated columns use
   `generated always as (...)`, virtual ones only on PostgreSQL 18 and later.
3. Primary keys, unique constraints and foreign keys are added with `alter table` after the
   `create table`, as Laravel does. A blueprint's statements run in one transaction, so a failure
   leaves nothing behind.
4. `change()` writes one `alter table` with the type (converting with `using "<column>"::<type>`),
   the nullability and the default. Enum, raw and generated columns and new checks are refused,
   since PostgreSQL would need the old constraints dropped by name first.
5. `dropPrimary` reads the key's name from `pg_constraint`, since PostgreSQL keeps `<old>_pkey`
   after a table rename; whatever name was asked for is ignored, as in Laravel.
6. Schema reads use `information_schema` and `pg_catalog`, limited to `current_schema()`.
7. `PostgresMigrationLock` takes a session advisory lock on its own connection from the data
   source, polling `pg_try_advisory_lock` up to a wait (60 seconds by default) and naming the
   holder's pid when it gives up. It checks the unlock result and drops every advisory lock of the
   session before closing it, so a pool never gets the lock back. It needs a data source that
   hands out real sessions, and a second connection besides the migrator's.
8. Version checks go through `DatabaseVersion`, shared with the SQLite grammar.

## Consequences

- Migrations written for SQLite run on PostgreSQL unchanged, within the features both grammars
  support.
- Column checks get PostgreSQL's system names (`<table>_<column>_check`); `dropCheck` is for named
  table checks on both databases.
- Tests start a throwaway PostgreSQL from the local binaries and are skipped where none is
  installed; `DIWAN_PG_REQUIRED` turns the skip into a failure for CI.
- Schema reads use `pg_catalog` only, so a role without rights on a table still sees it, and a
  `search_path` that names no existing schema is reported instead of reading as an empty database.
