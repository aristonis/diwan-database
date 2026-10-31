# 0009. Migrator transactions and records

## Status

Accepted

## Context

Laravel's migrator runs each migration in a transaction when the database can undo schema changes,
and writes the migration's record in the migrations table after that transaction commits. A crash
between the two leaves a change applied and unrecorded, and the next run tries it again. Java
applications often migrate at startup from several places, use more than one connection, and may
call the migrator inside a transaction of their own.

## Decision

1. `Migration.up` and `down` receive a `Schema` bound to the migration's connection and
   transaction, so the migration never reaches for a connection outside it.
2. A migration runs in its own transaction when it asks for one and the driver reports
   transactional DDL. When it runs on the connection that holds the migrations table, its record is
   written in that same transaction; otherwise the record is written right after it.
3. The migrations table takes Laravel's layout through the schema builder: `id`, a unique
   `migration` of up to 191 characters and `batch`.
4. Every call refuses to start while one of the migrator's connections is in a transaction.
5. A failure stops the call with `MigrationFailedException`, which names the migration, the
   direction, and the phase (the migration or its record), and lists what finished before it.
6. Migrations are an explicit list, sorted by name; other connections are added by name to the
   builder.
7. A `MigrationLock` is held around every run, rollback and reset. The default does nothing.
8. Pretend respects `shouldRun()`, so a skipped migration lists no SQL.
9. Rollback and reset refuse when a record to undo has no migration in the migrator, because older
   code undoing part of a newer batch can drop tables the rest still needs. Laravel's
   skip-and-continue is available through `allowMissingOnRollback()`.

## Consequences

- On SQLite and PostgreSQL a migration and its record are kept or undone together.
- On MySQL, and for migrations on another connection, a crash between change and record can still
  leave the change unrecorded; the exception's phase shows when that happened.
- Two processes can still migrate at once until a database lock is plugged in.
- `Migration` changed from taking a `Connection` to taking a `Schema` before the first release.
